package shiori

import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/** The numbers a bookseller looks at, all computed from the journal. */
class Reports(private val shop: Shop) {
    data class Day(val date: LocalDate, val sales: Int, val books: Long, val revenue: Long, val tax: Long)
    data class Ranked(val isbn: String, val qty: Long, val revenue: Long)
    data class Sleeping(val isbn: String, val qty: Long, val value: Long, val days: Long)

    private val zone get() = shop.settings.zoneId

    private fun liveSales(from: LocalDate, to: LocalDate) = shop.sales.filter {
        !it.refunded && it.at.atZone(zone).toLocalDate().let { d -> !d.isBefore(from) && !d.isAfter(to) }
    }

    fun days(from: LocalDate, to: LocalDate): List<Day> {
        val by = liveSales(from, to).groupBy { it.at.atZone(zone).toLocalDate() }
        return generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }.map { d ->
            val ss = by[d].orEmpty()
            Day(d, ss.size, ss.sumOf { s -> s.lines.filter { it.kind == LineKind.BOOK }.sumOf { it.qty } },
                ss.sumOf { it.total }, ss.sumOf { s -> s.byRate().sumOf { it.third } })
        }.toList()
    }

    fun bestsellers(from: LocalDate, to: LocalDate, n: Int = 10): List<Ranked> =
        liveSales(from, to).flatMap { it.lines }.filter { it.kind == LineKind.BOOK }
            .groupBy { it.ref }
            .map { (isbn, ls) -> Ranked(isbn, ls.sumOf { it.qty }, ls.sumOf { it.total }) }
            .sortedWith(compareByDescending<Ranked> { it.qty }.thenByDescending { it.revenue }.thenBy { it.isbn })
            .take(n)

    /** Stock at cost: what the shelves are worth to the shop. */
    fun stockValue(): Long = shop.stock.values.filter { it.qty > 0 }.sumOf { it.qty * it.avgCost }

    fun copiesInStock(): Long = shop.stock.values.sumOf { maxOf(it.qty, 0) }

    /**
     * Books on the shelf that have not sold for `days` days (counting from their last sale,
     * or from when they arrived if they never sold): candidates for a return to the publisher,
     * a table display, or — in France, once the law allows it — a discount.
     */
    fun sleeping(days: Long = 90, at: Instant = shop.now()): List<Sleeping> =
        shop.stock.filter { it.value.qty > 0 }.mapNotNull { (isbn, s) ->
            val since = s.lastSale ?: s.firstIn ?: return@mapNotNull null
            val d = Duration.between(since, at).toDays()
            if (d >= days) Sleeping(isbn, s.qty, s.qty * s.avgCost, d) else null
        }.sortedByDescending { it.value }

    /**
     * Sell-through over the period: copies sold divided by copies sold plus copies still on hand.
     * Booksellers watch it per section to decide what to reorder.
     */
    fun sellThrough(from: LocalDate, to: LocalDate): Map<String, Double> {
        val sold = liveSales(from, to).flatMap { it.lines }.filter { it.kind == LineKind.BOOK }.groupBy { shop.books[it.ref]?.section ?: "" }
            .mapValues { e -> e.value.sumOf { it.qty } }
        val onHand = shop.books.values.groupBy { it.section }.mapValues { e -> e.value.sumOf { maxOf(shop.stock[it.isbn]?.qty ?: 0, 0) } }
        return (sold.keys + onHand.keys).associateWith { k ->
            val s = sold[k] ?: 0
            val h = onHand[k] ?: 0
            if (s + h == 0L) 0.0 else s.toDouble() / (s + h)
        }.toSortedMap()
    }

    /** Gross margin on books sold: revenue without tax minus the average cost of the copies. */
    fun bookMargin(from: LocalDate, to: LocalDate): Pair<Long, Long> {
        var net = 0L
        var cost = 0L
        for (s in liveSales(from, to)) for (l in s.lines) if (l.kind == LineKind.BOOK) {
            net += l.total - taxIncluded(l.total, l.taxBp)
            cost += l.qty * (shop.stock[l.ref]?.avgCost ?: 0)
        }
        return net to cost
    }

    // ---------- for the accountant ----------

    /** One line per receipt and tax rate. A refund is a negative line on the day it happened, so a closed day never changes. */
    data class LedgerLine(val date: LocalDate, val type: String, val receipt: String, val method: String, val rateBp: Int, val gross: Long, val tax: Long) {
        val net: Long get() = gross - tax
    }

    fun ledger(from: LocalDate, to: LocalDate): List<LedgerLine> {
        fun inside(at: Instant) = at.atZone(zone).toLocalDate().let { !it.isBefore(from) && !it.isAfter(to) }
        val out = ArrayList<LedgerLine>()
        for (s in shop.sales) {
            if (inside(s.at)) for ((bp, g, t) in s.byRate()) out += LedgerLine(s.at.atZone(zone).toLocalDate(), "sale", s.id, s.method, bp, g, t)
            val r = s.refundedAt
            if (r != null && inside(r)) for ((bp, g, t) in s.byRate()) out += LedgerLine(r.atZone(zone).toLocalDate(), "refund", s.id, s.method, bp, -g, -t)
        }
        return out.sortedWith(compareBy<LedgerLine> { it.date }.thenBy { it.receipt }.thenBy { it.type }.thenByDescending { it.rateBp })
    }

    /** The end-of-day summary (Z report): totals by payment method and by tax rate, refunds included. */
    data class Closing(val date: LocalDate, val sales: Int, val refunds: Int, val byMethod: Map<String, Long>, val byRate: Map<Int, Pair<Long, Long>>) {
        val total: Long get() = byMethod.values.sum()
    }

    fun closing(day: LocalDate): Closing {
        val es = ledger(day, day)
        return Closing(
            day, es.filter { it.type == "sale" }.map { it.receipt }.distinct().size, es.filter { it.type == "refund" }.map { it.receipt }.distinct().size,
            es.groupBy { it.method }.mapValues { e -> e.value.sumOf { it.gross } }.toSortedMap(),
            es.groupBy { it.rateBp }.mapValues { e -> e.value.sumOf { it.gross } to e.value.sumOf { it.tax } }.toSortedMap(compareByDescending { it }),
        )
    }

    /** Deliveries and returns to suppliers over the period: what the shop owes and what it is owed. */
    data class Purchase(val date: LocalDate, val type: String, val doc: String, val supplier: String, val isbn: String, val qty: Long, val unitCost: Long) {
        val total: Long get() = qty * unitCost
    }

    fun purchases(from: LocalDate, to: LocalDate): List<Purchase> {
        fun day(at: Instant) = at.atZone(zone).toLocalDate()
        fun inside(d: LocalDate) = !d.isBefore(from) && !d.isAfter(to)
        val out = ArrayList<Purchase>()
        for (d in shop.deliveries) day(d.at).let { if (inside(it)) out += Purchase(it, "delivery", d.po ?: "", d.supplier, d.isbn, d.qty, d.cost) }
        for (r in shop.returns.values) day(r.at).let { if (inside(it)) for (l in r.lines) out += Purchase(it, "return", r.id, r.supplier, l.isbn, -l.qty, l.cost) }
        return out.sortedWith(compareBy<Purchase> { it.date }.thenBy { it.type }.thenBy { it.doc })
    }
}

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
}

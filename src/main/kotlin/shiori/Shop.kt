package shiori

import java.io.File
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

enum class Condition { NEW, USED }

data class Settings(
    val name: String = "My bookshop",
    val market: Market = Market.FR,
    /** Japan: invoice registration number, T followed by 13 digits. */
    val registration: String = "",
    val address: String = "",
    val zone: String = "UTC",
) {
    val zoneId: ZoneId get() = runCatching { ZoneId.of(zone) }.getOrDefault(ZoneOffset.UTC)
}

data class Book(
    val isbn: String,
    /** Titles by language code: the original and any translations or readings the shop wants to search on. */
    val titles: Map<String, String>,
    val authors: List<String>,
    val publisher: String,
    /** Language of the text: fr, en, ja, zh-Hant, ko… */
    val language: String,
    /** Publisher's price, tax included. */
    val price: Long,
    val condition: Condition,
    /** Publication date, when known; France's discount rule depends on it. */
    val published: LocalDate?,
    val section: String,
) {
    fun title(lang: String): String = titles[lang] ?: titles[language] ?: titles.values.firstOrNull() ?: isbn
}

data class Product(val id: String, val names: Map<String, String>, val price: Long) {
    fun name(lang: String): String = names[lang] ?: names["en"] ?: names.values.firstOrNull() ?: id
}

data class Stock(
    var qty: Long = 0,
    /** Weighted average cost of the copies in stock. */
    var avgCost: Long = 0,
    var firstIn: Instant? = null,
    var lastIn: Instant? = null,
    var lastSale: Instant? = null,
    var sold: Long = 0,
    /** Who delivered last: the supplier to reorder from. */
    var lastSupplier: String = "",
)

enum class LineKind { BOOK, CAFE }

data class SaleLine(
    val kind: LineKind,
    val ref: String,
    val qty: Long,
    /** Shelf price for one unit. */
    val unit: Long,
    /** Price charged for one unit, after any discount. */
    val price: Long,
    val taxBp: Int,
    val takeaway: Boolean,
) {
    val total: Long get() = price * qty
}

data class Sale(
    val id: String,
    val at: Instant,
    val lines: List<SaleLine>,
    val method: String,
    /** Customer orders handed over with this sale. */
    val orders: List<String> = emptyList(),
    var refunded: Boolean = false,
    var refundedAt: Instant? = null,
) {
    val total: Long get() = lines.sumOf { it.total }

    /** Totals by tax rate: gross amount and tax, with one rounding per rate, as Japan's invoice rules require. */
    fun byRate(): List<Triple<Int, Long, Long>> =
        lines.groupBy { it.taxBp }.toSortedMap(compareByDescending { it })
            .map { (bp, ls) -> val g = ls.sumOf { it.total }; Triple(bp, g, taxIncluded(g, bp)) }
}

/** A request to sell: what, how many, at which price (null = shelf price), and for which customer order, if any. */
data class CartLine(val kind: LineKind, val ref: String, val qty: Long, val price: Long? = null, val takeaway: Boolean = false, val order: String? = null)

class ShopError(val key: String, vararg val args: Any) : RuntimeException(key)

/**
 * The shop: everything is rebuilt from the journal when Shiori starts,
 * and every change goes through the journal first.
 */
class Shop(val journal: Journal, val clock: Clock = Clock.systemUTC()) : AutoCloseable {
    var settings = Settings(); private set
    val books = LinkedHashMap<String, Book>()
    val products = LinkedHashMap<String, Product>()
    val stock = HashMap<String, Stock>()
    val sales = ArrayList<Sale>()
    private val salesById = HashMap<String, Sale>()
    val suppliers = LinkedHashMap<String, Supplier>()
    val purchaseOrders = LinkedHashMap<String, PurchaseOrder>()
    val returns = LinkedHashMap<String, SupplierReturn>()
    val orders = LinkedHashMap<String, CustomerOrder>()
    /** Receipts by "isbn|supplier", to know what may go back to whom. */
    val lots = HashMap<String, Lot>()
    /** Every delivery, for the purchases export: when, from whom, what, how many, at what cost. */
    val deliveries = ArrayList<Delivery>()

    data class Delivery(val at: Instant, val supplier: String, val isbn: String, val qty: Long, val cost: Long, val po: String?)

    init {
        journal.entries.forEach(::apply)
    }

    companion object {
        fun open(dir: File, clock: Clock = Clock.systemUTC()) = Shop(Journal.open(File(dir, "journal.jsonl"), clock), clock)
        val TITLE_LANGS = listOf("fr", "en", "ja", "zh-Hant", "ko")
        private val REGISTRATION = Regex("T\\d{13}")
        private val SLUG = Regex("[a-z0-9-]{1,40}")
        val ADJUST_REASONS = listOf("count", "damaged", "lost", "gift")
    }

    override fun close() = journal.close()

    fun now(): Instant = clock.instant()

    fun stockOf(isbn: String) = stock.getOrPut(isbn) { Stock() }

    /** The supplier a free-text name or an id refers to, so that old receipts and new ones land in the same lot. */
    fun supplierKey(s: String): String {
        val t = s.trim()
        return suppliers[t]?.id ?: suppliers.values.firstOrNull { it.name.equals(t, ignoreCase = true) }?.id ?: t
    }

    fun lot(isbn: String, supplier: String) = lots.getOrPut("$isbn|${supplierKey(supplier)}") { Lot() }

    // ---------- reading the journal ----------

    private fun deliver(isbn: String, qty: Long, cost: Long, supplier: String, at: Instant, po: String?) {
        val s = stockOf(isbn)
        s.avgCost = if (s.qty + qty > 0) (s.avgCost * maxOf(s.qty, 0) + cost * qty) / (maxOf(s.qty, 0) + qty) else cost
        s.qty += qty
        if (s.firstIn == null) s.firstIn = at
        s.lastIn = at
        val key = supplierKey(supplier)
        if (key.isNotEmpty()) s.lastSupplier = key
        val l = lot(isbn, key)
        l.received += qty
        l.lastIn = at
        l.lastCost = cost
        deliveries.add(Delivery(at, key, isbn, qty, cost, po))
    }

    @Suppress("UNCHECKED_CAST")
    private fun maps(v: List<Any?>) = v.map { it as Map<String, Any?> }

    private fun apply(e: Entry) {
        val d = e.data
        when (e.type) {
            "settings" -> settings = Settings(
                d.str("name"), Market.of(d.str("market")) ?: Market.FR, d.strOrNull("registration") ?: "",
                d.strOrNull("address") ?: "", d.strOrNull("zone") ?: "UTC",
            )
            "book" -> {
                @Suppress("UNCHECKED_CAST")
                val titles = (d.obj("titles") as Map<String, Any?>).mapValues { it.value as String }
                books[d.str("isbn")] = Book(
                    d.str("isbn"), titles, d.list("authors").map { it as String }, d.strOrNull("publisher") ?: "",
                    d.strOrNull("language") ?: "", d.long("price"), Condition.valueOf(d.str("condition")),
                    d.strOrNull("published")?.let { LocalDate.parse(it) }, d.strOrNull("section") ?: "",
                )
            }
            "product" -> {
                @Suppress("UNCHECKED_CAST")
                val names = (d.obj("names") as Map<String, Any?>).mapValues { it.value as String }
                products[d.str("id")] = Product(d.str("id"), names, d.long("price"))
            }
            "supplier" -> suppliers[d.str("id")] = Supplier(
                d.str("id"), d.str("name"), d.int("discountBp"), d["returnable"] == true, d.int("returnDays"), d.strOrNull("contact") ?: "",
            )
            "receive" -> deliver(d.str("isbn"), d.long("qty"), d.long("cost"), d.strOrNull("supplier") ?: "", e.at, null)
            "po" -> purchaseOrders[d.str("id")] = PurchaseOrder(
                d.str("id"), e.at, d.str("supplier"),
                maps(d.list("lines")).map { PoLine(it.str("isbn"), it.long("qty"), it.long("cost")) },
                d.list("orders").map { it as String },
            )
            "po.receive" -> {
                val po = purchaseOrders[d.str("po")] ?: return
                for (l in maps(d.list("lines"))) {
                    val line = po.lines.firstOrNull { it.isbn == l.str("isbn") } ?: continue
                    line.received += l.long("qty")
                    deliver(line.isbn, l.long("qty"), line.cost, po.supplier, e.at, po.id)
                }
            }
            "po.cancel" -> purchaseOrders[d.str("po")]?.cancelled = e.at
            "return" -> {
                val r = SupplierReturn(d.str("id"), e.at, d.str("supplier"), maps(d.list("lines")).map { ReturnLine(it.str("isbn"), it.long("qty"), it.long("cost")) }, d.strOrNull("reason") ?: "")
                returns[r.id] = r
                for (l in r.lines) {
                    stockOf(l.isbn).qty -= l.qty
                    lot(l.isbn, r.supplier).returned += l.qty
                }
            }
            "order" -> orders[d.str("id")] = CustomerOrder(d.str("id"), e.at, d.str("customer"), d.str("isbn"), d.long("qty"))
            "order.notified" -> orders[d.str("order")]?.notified = e.at
            "order.cancel" -> orders[d.str("order")]?.cancelled = e.at
            "adjust" -> stockOf(d.str("isbn")).qty += d.long("qty")
            "count" -> {
                @Suppress("UNCHECKED_CAST")
                for ((isbn, delta) in d.obj("deltas") as Map<String, Any?>) stockOf(isbn).qty += (delta as Number).toLong()
            }
            "sale" -> {
                val lines = maps(d.list("lines")).map { l ->
                    SaleLine(LineKind.valueOf(l.str("kind")), l.str("ref"), l.long("qty"), l.long("unit"), l.long("price"), l.int("taxBp"), l["takeaway"] == true)
                }
                val sale = Sale(d.str("id"), e.at, lines, d.str("method"), d.list("orders").map { it as String })
                sales.add(sale)
                salesById[sale.id] = sale
                for (l in lines) if (l.kind == LineKind.BOOK) {
                    val s = stockOf(l.ref)
                    s.qty -= l.qty
                    s.sold += l.qty
                    s.lastSale = e.at
                }
                for (o in sale.orders) orders[o]?.let { it.collected = e.at; it.sale = sale.id }
            }
            "refund" -> {
                val sale = salesById[d.str("sale")] ?: return
                sale.refunded = true
                sale.refundedAt = e.at
                for (l in sale.lines) if (l.kind == LineKind.BOOK) {
                    val s = stockOf(l.ref)
                    s.qty += l.qty
                    s.sold -= l.qty
                }
            }
        }
    }

    private fun record(type: String, data: Map<String, Any?>): Entry {
        val e = journal.append(type, data)
        apply(e)
        return e
    }

    // ---------- changes ----------

    fun configure(s: Settings) {
        if (s.name.isBlank() || s.name.length > 120) throw ShopError("error.name")
        if (s.market.needsRegistrationNumber && s.registration.isNotEmpty() && !REGISTRATION.matches(s.registration)) throw ShopError("error.registration")
        if (runCatching { ZoneId.of(s.zone) }.isFailure) throw ShopError("error.zone")
        record("settings", linkedMapOf("name" to s.name.trim(), "market" to s.market.name, "registration" to s.registration, "address" to s.address.trim(), "zone" to s.zone))
    }

    fun saveBook(b: Book): Book {
        val isbn = Isbn.normalize(b.isbn) ?: throw ShopError("error.isbn")
        val titles = b.titles.filter { it.key in TITLE_LANGS && it.value.isNotBlank() }.mapValues { it.value.trim().take(300) }
        if (titles.isEmpty()) throw ShopError("error.title")
        if (b.price <= 0) throw ShopError("error.price")
        val book = b.copy(isbn = isbn, titles = titles, authors = b.authors.map { it.trim() }.filter { it.isNotEmpty() }.take(10))
        record("book", linkedMapOf(
            "isbn" to isbn, "titles" to LinkedHashMap(titles), "authors" to book.authors, "publisher" to book.publisher.trim(),
            "language" to book.language, "price" to book.price, "condition" to book.condition.name,
            "published" to book.published?.toString(), "section" to book.section.trim(),
        ))
        return books.getValue(isbn)
    }

    fun saveProduct(p: Product) {
        if (!SLUG.matches(p.id)) throw ShopError("error.product")
        if (p.price <= 0) throw ShopError("error.price")
        if (p.names.values.none { it.isNotBlank() }) throw ShopError("error.title")
        record("product", linkedMapOf("id" to p.id, "names" to LinkedHashMap(p.names), "price" to p.price))
    }

    fun saveSupplier(s: Supplier): Supplier {
        if (!SLUG.matches(s.id)) throw ShopError("error.product")
        if (s.name.isBlank() || s.name.length > 120) throw ShopError("error.name")
        if (s.discountBp !in 0..9000) throw ShopError("error.discountbp")
        if (s.returnDays !in 0..3650) throw ShopError("error.days")
        record("supplier", linkedMapOf(
            "id" to s.id, "name" to s.name.trim(), "discountBp" to s.discountBp, "returnable" to s.returnable,
            "returnDays" to s.returnDays, "contact" to s.contact.trim().take(200),
        ))
        return suppliers.getValue(s.id)
    }

    fun receive(isbn: String, qty: Long, cost: Long, supplier: String) {
        val book = books[isbn] ?: throw ShopError("error.unknown")
        if (qty !in 1..10_000) throw ShopError("error.qty")
        if (cost < 0 || cost > book.price) throw ShopError("error.cost")
        record("receive", linkedMapOf("isbn" to isbn, "qty" to qty, "cost" to cost, "supplier" to supplierKey(supplier).take(120)))
    }

    fun adjust(isbn: String, delta: Long, reason: String) {
        if (isbn !in books) throw ShopError("error.unknown")
        if (delta == 0L || delta !in -10_000..10_000) throw ShopError("error.qty")
        if (reason !in ADJUST_REASONS) throw ShopError("error.reason")
        if (stockOf(isbn).qty + delta < 0) throw ShopError("error.stock", books.getValue(isbn).title("en"))
        record("adjust", linkedMapOf("isbn" to isbn, "qty" to delta, "reason" to reason))
    }

    // ---------- customer orders ----------

    /**
     * Which open customer orders have their copies on the shelf, oldest first. Nothing is stored:
     * when copies arrive, the oldest orders they can fill become ready by themselves.
     */
    fun readyOrders(): Set<String> {
        val left = HashMap<String, Long>()
        val ready = HashSet<String>()
        for (o in orders.values) if (!o.closed) {
            val have = left.getOrPut(o.isbn) { maxOf(stock[o.isbn]?.qty ?: 0, 0) }
            if (have >= o.qty) { ready += o.id; left[o.isbn] = have - o.qty }
        }
        return ready
    }

    fun orderState(o: CustomerOrder, ready: Set<String> = readyOrders()): OrderState = when {
        o.cancelled != null -> OrderState.CANCELLED
        o.collected != null -> OrderState.COLLECTED
        o.id in ready -> OrderState.READY
        purchaseOrders.values.any { it.open && o.id in it.orders } -> OrderState.ORDERED
        else -> OrderState.WAITING
    }

    /** Copies set aside for customers. */
    fun reserved(isbn: String, ready: Set<String> = readyOrders()): Long = ready.sumOf { id -> orders.getValue(id).let { if (it.isbn == isbn) it.qty else 0 } }

    /** Copies a walk-in customer may buy. */
    fun available(isbn: String, ready: Set<String> = readyOrders()): Long = maxOf(stock[isbn]?.qty ?: 0, 0) - reserved(isbn, ready)

    fun placeOrder(customer: String, isbn: String, qty: Long): CustomerOrder {
        if (!Regex("C\\d{5}").matches(customer)) throw ShopError("error.unknown")
        if (isbn !in books) throw ShopError("error.unknown")
        if (qty !in 1..100) throw ShopError("error.qty")
        val id = "O%05d".format(orders.size + 1)
        record("order", linkedMapOf("id" to id, "customer" to customer, "isbn" to isbn, "qty" to qty))
        return orders.getValue(id)
    }

    fun markNotified(id: String) {
        val o = orders[id] ?: throw ShopError("error.unknown")
        if (o.closed) throw ShopError("error.closed")
        record("order.notified", linkedMapOf("order" to id))
    }

    fun cancelOrder(id: String) {
        val o = orders[id] ?: throw ShopError("error.unknown")
        if (o.closed) throw ShopError("error.closed")
        record("order.cancel", linkedMapOf("order" to id))
    }

    // ---------- suppliers: purchase orders and returns ----------

    data class Suggestion(val isbn: String, val qty: Long, val forOrders: List<String>, val soldOut: Boolean, val supplier: String)

    /**
     * What to order: copies customers are waiting for that no open purchase order covers,
     * then titles that sold in the last 90 days and are no longer on the shelf.
     */
    fun suggestions(at: Instant = now()): List<Suggestion> {
        val ready = readyOrders()
        val covered = HashSet<String>()
        purchaseOrders.values.filter { it.open }.forEach { covered += it.orders }
        val onOrder = HashMap<String, Long>()
        purchaseOrders.values.filter { it.open }.forEach { po -> po.lines.forEach { onOrder.merge(it.isbn, it.remaining, Long::plus) } }
        val waiting = orders.values.filter { !it.closed && it.id !in ready && it.id !in covered }.groupBy { it.isbn }
        val out = ArrayList<Suggestion>()
        for ((isbn, os) in waiting) {
            val need = os.sumOf { it.qty } - maxOf(available(isbn, ready), 0)
            if (need > 0) out += Suggestion(isbn, need, os.map { it.id }, false, stock[isbn]?.lastSupplier ?: "")
        }
        val since = at.minus(Duration.ofDays(90))
        for ((isbn, s) in stock) {
            if (isbn in waiting || books[isbn]?.condition != Condition.NEW) continue
            if (s.qty <= 0 && s.lastSale?.isAfter(since) == true && (onOrder[isbn] ?: 0) == 0L)
                out += Suggestion(isbn, 1, emptyList(), true, s.lastSupplier)
        }
        return out.sortedWith(compareBy<Suggestion> { it.forOrders.isEmpty() }.thenBy { books[it.isbn]?.title("en") ?: it.isbn })
    }

    fun createPurchaseOrder(supplier: String, lines: List<Pair<String, Long>>, forOrders: List<String> = emptyList()): PurchaseOrder {
        val sup = suppliers[supplier] ?: throw ShopError("error.supplier")
        val merged = LinkedHashMap<String, Long>()
        for ((isbn, q) in lines) {
            if (isbn !in books) throw ShopError("error.unknown")
            if (q !in 1..10_000) throw ShopError("error.qty")
            merged.merge(isbn, q, Long::plus)
        }
        if (merged.isEmpty()) throw ShopError("error.empty")
        val serve = forOrders.filter { o -> orders[o]?.let { !it.closed && it.isbn in merged } == true }
        val id = "P%05d".format(purchaseOrders.size + 1)
        record("po", linkedMapOf(
            "id" to id, "supplier" to sup.id,
            "lines" to merged.map { (isbn, q) -> linkedMapOf("isbn" to isbn, "qty" to q, "cost" to sup.cost(books.getValue(isbn).price)) },
            "orders" to serve,
        ))
        return purchaseOrders.getValue(id)
    }

    /** Copies arrive for a purchase order; a partial delivery leaves the rest on order. */
    fun receivePurchaseOrder(id: String, qtys: Map<String, Long>) {
        val po = purchaseOrders[id] ?: throw ShopError("error.unknown")
        if (!po.open) throw ShopError("error.closed")
        val lines = ArrayList<Map<String, Any?>>()
        for (l in po.lines) {
            val q = qtys[l.isbn] ?: 0
            if (q < 0 || q > l.remaining) throw ShopError("error.qty")
            if (q > 0) lines += linkedMapOf("isbn" to l.isbn, "qty" to q)
        }
        if (lines.isEmpty()) throw ShopError("error.empty")
        record("po.receive", linkedMapOf("po" to id, "lines" to lines))
    }

    fun cancelPurchaseOrder(id: String) {
        val po = purchaseOrders[id] ?: throw ShopError("error.unknown")
        if (!po.open) throw ShopError("error.closed")
        record("po.cancel", linkedMapOf("po" to id))
    }

    /**
     * May this copy go back to this supplier? Only if the supplier accepts returns, the copies came from them,
     * the agreed window has not passed, and the copies are not set aside for a customer.
     */
    fun returnable(isbn: String, supplier: String, at: Instant = now(), ready: Set<String> = readyOrders()): Long {
        val sup = suppliers[supplier] ?: return 0
        if (!sup.returnable) return 0
        val l = lots["$isbn|${sup.id}"] ?: return 0
        if (sup.returnDays > 0 && l.lastIn?.plus(Duration.ofDays(sup.returnDays.toLong()))?.isBefore(at) != false) return 0
        return minOf(l.returnable, available(isbn, ready))
    }

    fun returnToSupplier(supplier: String, lines: List<Pair<String, Long>>, reason: String): SupplierReturn {
        val sup = suppliers[supplier] ?: throw ShopError("error.supplier")
        if (!sup.returnable) throw ShopError("error.noreturns", sup.name)
        val at = now()
        val ready = readyOrders()
        val merged = LinkedHashMap<String, Long>()
        for ((isbn, q) in lines) { if (q != 0L) merged.merge(isbn, q, Long::plus) }
        if (merged.isEmpty()) throw ShopError("error.empty")
        val out = ArrayList<Map<String, Any?>>()
        for ((isbn, q) in merged) {
            val b = books[isbn] ?: throw ShopError("error.unknown")
            if (q !in 1..10_000) throw ShopError("error.qty")
            if (q > returnable(isbn, sup.id, at, ready)) throw ShopError("error.return", b.title("en"), sup.name)
            out += linkedMapOf("isbn" to isbn, "qty" to q, "cost" to lots.getValue("$isbn|${sup.id}").lastCost)
        }
        val id = "R%05d".format(returns.size + 1)
        record("return", linkedMapOf("id" to id, "supplier" to sup.id, "lines" to out, "reason" to reason.trim().take(200)))
        return returns.getValue(id)
    }

    // ---------- stock count ----------

    /**
     * Applies a stock count: every book of the section (or of the shop) takes the counted quantity;
     * a book on the list that was not scanned is counted as missing. A copy of another section found on
     * these shelves is left out: its own shelf was not counted, so its total is not known. Returns the
     * differences applied.
     */
    fun applyCount(section: String?, counted: Map<String, Long>): Map<String, Long> {
        for ((isbn, q) in counted) {
            if (isbn !in books) throw ShopError("error.unknown")
            if (q !in 0..100_000) throw ShopError("error.qty")
        }
        val scope = books.values.filter { section == null || it.section == section }.map { it.isbn }.toSet()
        val deltas = LinkedHashMap<String, Long>()
        for (isbn in scope.sorted()) {
            val d = (counted[isbn] ?: 0) - (stock[isbn]?.qty ?: 0)
            if (d != 0L) deltas[isbn] = d
        }
        record("count", linkedMapOf("section" to section, "deltas" to LinkedHashMap<String, Any?>(deltas), "lines" to counted.size.toLong()))
        return deltas
    }

    // ---------- selling ----------

    /** The lowest price the law lets the shop charge for this book today, and why. */
    fun floorPrice(book: Book, at: Instant = now()): Pair<Long, String> {
        val m = settings.market
        if (book.condition == Condition.USED) return 0L to "rule.used"
        val cap = m.newBookMaxDiscountBp ?: return 0L to "rule.free"
        if (m == Market.FR && frenchDiscountFree(book, at)) return 0L to "rule.fr.old"
        return (book.price - maxDiscount(book.price, cap)) to "rule.${m.name.lowercase()}"
    }

    /**
     * Loi Lang, article 5: a shop may discount freely books published or imported more than
     * two years ago whose last delivery was more than six months ago.
     */
    fun frenchDiscountFree(book: Book, at: Instant): Boolean {
        val pub = book.published ?: return false
        val today = at.atZone(settings.zoneId).toLocalDate()
        val lastIn = stock[book.isbn]?.lastIn ?: return false
        return !pub.plusYears(2).isAfter(today) && Duration.between(lastIn, at) >= Duration.ofDays(183)
    }

    fun sell(cart: List<CartLine>, method: String): Sale {
        if (cart.isEmpty()) throw ShopError("error.empty")
        if (method !in setOf("cash", "card", "other")) throw ShopError("error.method")
        val m = settings.market
        val at = now()
        val ready = readyOrders()
        val lines = ArrayList<SaleLine>()
        val needed = HashMap<String, Long>()
        val handed = HashMap<String, Long>()
        val forOrders = ArrayList<String>()
        for (c in cart) {
            if (c.qty !in 1..1000) throw ShopError("error.qty")
            when (c.kind) {
                LineKind.BOOK -> {
                    val b = books[c.ref] ?: throw ShopError("error.unknown")
                    if (c.order != null) {
                        val o = orders[c.order] ?: throw ShopError("error.unknown")
                        if (o.id !in ready || o.isbn != b.isbn || o.qty != c.qty || o.id in forOrders) throw ShopError("error.notready", o.id)
                        forOrders += o.id
                        handed.merge(b.isbn, c.qty, Long::plus)
                    }
                    val price = c.price ?: b.price
                    if (price > b.price) throw ShopError("error.above", b.title("en"))
                    val (floor, rule) = floorPrice(b, at)
                    if (price < floor) throw ShopError("error.discount", b.title("en"), m.currency.format(floor), rule)
                    needed.merge(b.isbn, c.qty, Long::plus)
                    lines.add(SaleLine(LineKind.BOOK, b.isbn, c.qty, b.price, price, m.bookTaxBp, false))
                }
                LineKind.CAFE -> {
                    val p = products[c.ref] ?: throw ShopError("error.unknown")
                    val price = c.price ?: p.price
                    if (price < 0 || price > p.price) throw ShopError("error.price")
                    lines.add(SaleLine(LineKind.CAFE, p.id, c.qty, p.price, price, if (c.takeaway) m.cafeTakeawayBp else m.cafeEatInBp, c.takeaway))
                }
            }
        }
        // Copies set aside for a customer may only leave with that customer's order.
        for ((isbn, q) in needed) if (available(isbn, ready) + (handed[isbn] ?: 0) < q) throw ShopError("error.stock", books.getValue(isbn).title("en"))
        val id = "S%06d".format(sales.size + 1)
        record("sale", linkedMapOf(
            "id" to id, "method" to method,
            "lines" to lines.map { linkedMapOf("kind" to it.kind.name, "ref" to it.ref, "qty" to it.qty, "unit" to it.unit, "price" to it.price, "taxBp" to it.taxBp, "takeaway" to it.takeaway) },
        ).also { if (forOrders.isNotEmpty()) it["orders"] = forOrders })
        return salesById.getValue(id)
    }

    fun refund(saleId: String, reason: String) {
        val sale = salesById[saleId] ?: throw ShopError("error.unknown")
        if (sale.refunded) throw ShopError("error.refunded")
        record("refund", linkedMapOf("sale" to saleId, "reason" to reason.trim().take(200)))
    }

    fun sale(id: String) = salesById[id]

    fun search(q: String, lang: String): List<Book> {
        val isbn = Isbn.normalize(q)
        if (isbn != null) return listOfNotNull(books[isbn])
        return books.values.filter { b ->
            Text.matches(q, *b.titles.values.toTypedArray(), *b.authors.toTypedArray(), b.publisher, b.section, b.isbn)
        }.sortedBy { Text.fold(it.title(lang)) }
    }
}

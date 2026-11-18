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

data class Sale(val id: String, val at: Instant, val lines: List<SaleLine>, val method: String, var refunded: Boolean = false) {
    val total: Long get() = lines.sumOf { it.total }

    /** Totals by tax rate: gross amount and tax, with one rounding per rate, as Japan's invoice rules require. */
    fun byRate(): List<Triple<Int, Long, Long>> =
        lines.groupBy { it.taxBp }.toSortedMap(compareByDescending { it })
            .map { (bp, ls) -> val g = ls.sumOf { it.total }; Triple(bp, g, taxIncluded(g, bp)) }
}

/** A request to sell: what, how many, at which price (null = shelf price). */
data class CartLine(val kind: LineKind, val ref: String, val qty: Long, val price: Long? = null, val takeaway: Boolean = false)

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

    init {
        journal.entries.forEach(::apply)
    }

    companion object {
        fun open(dir: File, clock: Clock = Clock.systemUTC()) = Shop(Journal.open(File(dir, "journal.jsonl"), clock), clock)
        val TITLE_LANGS = listOf("fr", "en", "ja", "zh-Hant", "ko")
        private val REGISTRATION = Regex("T\\d{13}")
    }

    override fun close() = journal.close()

    fun now(): Instant = clock.instant()

    fun stockOf(isbn: String) = stock.getOrPut(isbn) { Stock() }

    // ---------- reading the journal ----------

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
            "receive" -> {
                val s = stockOf(d.str("isbn"))
                val qty = d.long("qty")
                val cost = d.long("cost")
                s.avgCost = if (s.qty + qty > 0) (s.avgCost * maxOf(s.qty, 0) + cost * qty) / (maxOf(s.qty, 0) + qty) else cost
                s.qty += qty
                if (s.firstIn == null) s.firstIn = e.at
                s.lastIn = e.at
            }
            "adjust" -> stockOf(d.str("isbn")).qty += d.long("qty")
            "sale" -> {
                val lines = d.list("lines").map {
                    @Suppress("UNCHECKED_CAST")
                    val l = it as Map<String, Any?>
                    SaleLine(LineKind.valueOf(l.str("kind")), l.str("ref"), l.long("qty"), l.long("unit"), l.long("price"), l.int("taxBp"), l["takeaway"] == true)
                }
                val sale = Sale(d.str("id"), e.at, lines, d.str("method"))
                sales.add(sale)
                salesById[sale.id] = sale
                for (l in lines) if (l.kind == LineKind.BOOK) {
                    val s = stockOf(l.ref)
                    s.qty -= l.qty
                    s.sold += l.qty
                    s.lastSale = e.at
                }
            }
            "refund" -> {
                val sale = salesById[d.str("sale")] ?: return
                sale.refunded = true
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
        if (!Regex("[a-z0-9-]{1,40}").matches(p.id)) throw ShopError("error.product")
        if (p.price <= 0) throw ShopError("error.price")
        if (p.names.values.none { it.isNotBlank() }) throw ShopError("error.title")
        record("product", linkedMapOf("id" to p.id, "names" to LinkedHashMap(p.names), "price" to p.price))
    }

    fun receive(isbn: String, qty: Long, cost: Long, supplier: String) {
        val book = books[isbn] ?: throw ShopError("error.unknown")
        if (qty !in 1..10_000) throw ShopError("error.qty")
        if (cost < 0 || cost > book.price) throw ShopError("error.cost")
        record("receive", linkedMapOf("isbn" to isbn, "qty" to qty, "cost" to cost, "supplier" to supplier.trim().take(120)))
    }

    fun adjust(isbn: String, delta: Long, reason: String) {
        if (isbn !in books) throw ShopError("error.unknown")
        if (delta == 0L || delta !in -10_000..10_000) throw ShopError("error.qty")
        if (reason !in setOf("count", "damaged", "lost", "gift")) throw ShopError("error.reason")
        if (stockOf(isbn).qty + delta < 0) throw ShopError("error.stock", books.getValue(isbn).title("en"))
        record("adjust", linkedMapOf("isbn" to isbn, "qty" to delta, "reason" to reason))
    }

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
        val lines = ArrayList<SaleLine>()
        val needed = HashMap<String, Long>()
        for (c in cart) {
            if (c.qty !in 1..1000) throw ShopError("error.qty")
            when (c.kind) {
                LineKind.BOOK -> {
                    val b = books[c.ref] ?: throw ShopError("error.unknown")
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
        for ((isbn, q) in needed) if (stockOf(isbn).qty < q) throw ShopError("error.stock", books.getValue(isbn).title("en"))
        val id = "S%06d".format(sales.size + 1)
        record("sale", linkedMapOf(
            "id" to id, "method" to method,
            "lines" to lines.map { linkedMapOf("kind" to it.kind.name, "ref" to it.ref, "qty" to it.qty, "unit" to it.unit, "price" to it.price, "taxBp" to it.taxBp, "takeaway" to it.takeaway) },
        ))
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

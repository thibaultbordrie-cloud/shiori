package shiori

import shiori.Check.eq
import shiori.Check.fails
import shiori.Check.ok
import shiori.Check.test
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

private val T0: Instant = Instant.parse("2026-06-01T10:00:00Z")

private fun newShop(market: Market, clock: HandClock = HandClock(T0)): Pair<Shop, File> {
    val dir = Check.tempDir()
    val shop = Shop.open(dir, clock)
    shop.configure(Settings("Test shop", market, if (market == Market.JP) "T1234567890123" else "", "", "UTC"))
    return shop to dir
}

private fun book(isbn: String, price: Long, published: LocalDate? = null, condition: Condition = Condition.NEW) =
    Book(isbn, mapOf("en" to "Book $isbn"), listOf("A. Author"), "P", "en", price, condition, published, "S")

private const val B1 = "9780306406157"
private val B2 = "97800" + "1111111" + Isbn.check13("978001111111")

fun journalTests() {
    test("the journal chain holds, and verify finds any changed, removed or reordered line") {
        val dir = Check.tempDir()
        val f = File(dir, "journal.jsonl")
        Journal.open(f, HandClock(T0)).use { j ->
            j.append("settings", linkedMapOf("name" to "A", "market" to "FR"))
            j.append("adjust", linkedMapOf("isbn" to B1, "qty" to 1L, "reason" to "count"))
            j.append("adjust", linkedMapOf("isbn" to B1, "qty" to 2L, "reason" to "count"))
        }
        eq(emptyList<String>(), Journal.verify(f))
        val lines = f.readLines()
        f.writeText(lines.joinToString("\n") { it.replace("\"qty\":2", "\"qty\":20") } + "\n")
        ok(Journal.verify(f).single().startsWith("line 3: content changed"), "changed line")
        f.writeText(listOf(lines[0], lines[2]).joinToString("\n") + "\n")
        ok(Journal.verify(f).first().startsWith("line 2"), "removed line")
        f.writeText(listOf(lines[0], lines[2], lines[1]).joinToString("\n") + "\n")
        ok(Journal.verify(f).isNotEmpty(), "reordered lines")
        fails<JournalError> { Journal.open(f) }
    }
    test("only one Shiori can write a journal at a time") {
        val dir = Check.tempDir()
        Shop.open(dir).use { fails<JournalError> { Shop.open(dir) } }
        Shop.open(dir).close()
    }
}

fun shopTests() {
    test("the shop is rebuilt identically from its journal") {
        val (shop, dir) = newShop(Market.FR)
        shop.saveBook(book(B1, 2000))
        shop.receive(B1, 5, 1200, "P")
        shop.receive(B1, 5, 1300, "P")
        val sale = shop.sell(listOf(CartLine(LineKind.BOOK, B1, 2)), "card")
        shop.close()
        Shop.open(dir).use { again ->
            eq(8L, again.stock.getValue(B1).qty)
            eq(1250L, again.stock.getValue(B1).avgCost, "weighted average cost")
            eq(4000L, again.sale(sale.id)!!.total)
            eq("Test shop", again.settings.name)
        }
    }
    test("France: a new book sells at 95 % at least") {
        val (shop, _) = newShop(Market.FR)
        shop.use {
            shop.saveBook(book(B1, 1999, LocalDate.of(2025, 1, 1)))
            shop.receive(B1, 3, 1000, "P")
            eq(1900L, shop.floorPrice(shop.books.getValue(B1)).first)
            shop.sell(listOf(CartLine(LineKind.BOOK, B1, 1, 1900)), "cash")
            val e = fails<ShopError> { shop.sell(listOf(CartLine(LineKind.BOOK, B1, 1, 1899)), "cash") }
            eq("error.discount", e.key)
            eq("error.above", fails<ShopError> { shop.sell(listOf(CartLine(LineKind.BOOK, B1, 1, 2500)), "cash") }.key)
        }
    }
    test("France: free discount only for books over 2 years old and last delivered over 6 months ago") {
        val clock = HandClock(T0)
        val (shop, _) = newShop(Market.FR, clock)
        shop.use {
            shop.saveBook(book(B1, 2000, LocalDate.of(2023, 1, 1)))
            shop.saveBook(book(B2, 2000, null))
            shop.receive(B1, 2, 1000, "P"); shop.receive(B2, 2, 1000, "P")
            eq("rule.fr", shop.floorPrice(shop.books.getValue(B1)).second, "delivered today")
            clock.now = T0.plusSeconds(200L * 86400)
            eq(0L to "rule.fr.old", shop.floorPrice(shop.books.getValue(B1)))
            eq("rule.fr", shop.floorPrice(shop.books.getValue(B2)).second, "unknown publication date stays capped")
            shop.sell(listOf(CartLine(LineKind.BOOK, B1, 1, 500)), "cash")
            shop.receive(B1, 1, 1000, "P")
            eq("rule.fr", shop.floorPrice(shop.books.getValue(B1)).second, "a new delivery restarts the 6 months")
        }
    }
    test("Japan: new books at the publisher's price, second-hand books free") {
        val (shop, _) = newShop(Market.JP)
        shop.use {
            shop.saveBook(book(B1, 1980))
            shop.saveBook(book(B2, 800, condition = Condition.USED))
            shop.receive(B1, 2, 1200, "P"); shop.receive(B2, 2, 200, "P")
            eq("error.discount", fails<ShopError> { shop.sell(listOf(CartLine(LineKind.BOOK, B1, 1, 1970)), "cash") }.key)
            shop.sell(listOf(CartLine(LineKind.BOOK, B2, 1, 300)), "cash")
        }
    }
    test("Taiwan: no cap; Korea: at most 10 % off") {
        newShop(Market.TW).first.use { s ->
            s.saveBook(book(B1, 500)); s.receive(B1, 1, 300, "P")
            s.sell(listOf(CartLine(LineKind.BOOK, B1, 1, 350)), "cash")
        }
        newShop(Market.KR).first.use { s ->
            s.saveBook(book(B1, 15000)); s.receive(B1, 2, 9000, "P")
            s.sell(listOf(CartLine(LineKind.BOOK, B1, 1, 13500)), "cash")
            eq("error.discount", fails<ShopError> { s.sell(listOf(CartLine(LineKind.BOOK, B1, 1, 13400)), "cash") }.key)
        }
    }
    test("Japan receipts: tax rounded once per rate (8 % take-away, 10 % eat-in)") {
        val (shop, _) = newShop(Market.JP)
        shop.use {
            shop.saveProduct(Product("cookie", mapOf("ja" to "クッキー"), 101))
            val sale = shop.sell(listOf(CartLine(LineKind.CAFE, "cookie", 3, null, takeaway = true), CartLine(LineKind.CAFE, "cookie", 1)), "cash")
            // Line by line, 3 × round(101 × 8/108) would give 21; once for the rate, 303 × 8/108 gives 22.
            eq(listOf(Triple(1000, 101L, 9L), Triple(800, 303L, 22L)), sale.byRate())
        }
    }
    test("stock never goes below zero; a refund puts the books back, once") {
        val (shop, _) = newShop(Market.TW)
        shop.use {
            shop.saveBook(book(B1, 500)); shop.receive(B1, 1, 300, "P")
            eq("error.stock", fails<ShopError> { shop.sell(listOf(CartLine(LineKind.BOOK, B1, 2)), "cash") }.key)
            val s = shop.sell(listOf(CartLine(LineKind.BOOK, B1, 1)), "cash")
            eq(0L, shop.stock.getValue(B1).qty)
            eq("error.stock", fails<ShopError> { shop.adjust(B1, -1, "lost") }.key)
            shop.refund(s.id, "changed mind")
            eq(1L, shop.stock.getValue(B1).qty)
            eq("error.refunded", fails<ShopError> { shop.refund(s.id, "again") }.key)
        }
    }
    test("bad input is refused before it reaches the journal") {
        val (shop, _) = newShop(Market.FR)
        shop.use {
            val before = shop.journal.entries.size
            eq("error.isbn", fails<ShopError> { shop.saveBook(book("9780306406158", 1000)) }.key)
            eq("error.price", fails<ShopError> { shop.saveBook(book(B1, 0)) }.key)
            eq("error.title", fails<ShopError> { shop.saveBook(book(B1, 1000).copy(titles = mapOf("xx" to "nope"))) }.key)
            eq("error.registration", fails<ShopError> { shop.configure(Settings("A", Market.JP, "123")) }.key)
            eq("error.empty", fails<ShopError> { shop.sell(emptyList(), "cash") }.key)
            eq(before, shop.journal.entries.size)
        }
    }
    test("reports: takings, bestsellers, books asleep, stock value") {
        val clock = HandClock(T0)
        val (shop, _) = newShop(Market.TW, clock)
        shop.use {
            shop.saveBook(book(B1, 500)); shop.saveBook(book(B2, 300))
            shop.receive(B1, 5, 300, "P"); shop.receive(B2, 2, 150, "P")
            shop.sell(listOf(CartLine(LineKind.BOOK, B1, 2)), "cash")
            val r = Reports(shop)
            val day = LocalDate.ofInstant(T0, ZoneOffset.UTC)
            eq(1000L, r.days(day, day).single().revenue)
            eq(B1, r.bestsellers(day, day).first().isbn)
            eq(3 * 300L + 2 * 150L, r.stockValue())
            clock.now = T0.plusSeconds(100L * 86400)
            eq(setOf(B1, B2), r.sleeping(90).map { it.isbn }.toSet())
        }
    }
    test("CSV import saves good rows and names the bad ones by line") {
        val (shop, _) = newShop(Market.FR)
        shop.use {
            val csv = "isbn,title,authors,price,qty,cost\n$B1,Paper Gardens,Claire Dumont,\"35,00\",3,\"20,00\"\n9780306406158,Bad,X,10,1,5\n"
            val rep = Csv.import(shop, csv, "Distributor")
            eq(1, rep.added); eq(3L, rep.received)
            ok(rep.errors.single().startsWith("line 3:"), rep.errors.toString())
            ok(Csv.export(shop, "en").contains("Paper Gardens"), "export")
        }
    }
    test("the demo shops build and keep every rule, in all four markets") {
        for (m in Market.entries) {
            val dir = Check.tempDir()
            Demo.create(dir, m, LocalDate.of(2026, 11, 16))
            eq(emptyList<String>(), Journal.verify(File(dir, "journal.jsonl")), "${m.name} journal")
            Shop.open(dir).use { s ->
                ok(s.sales.size > 100, "${m.name} has sales")
                ok(s.stock.values.all { it.qty >= 0 }, "${m.name} stock never negative")
                if (m == Market.JP) ok(s.sales.flatMap { it.lines }.filter { it.kind == LineKind.BOOK }.all { it.price == it.unit || s.books.getValue(it.ref).condition == Condition.USED }, "JP fixed prices")
            }
        }
    }
}

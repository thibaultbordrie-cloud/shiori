package shiori

import shiori.Check.eq
import shiori.Check.fails
import shiori.Check.ok
import shiori.Check.test
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

private val T0: Instant = Instant.parse("2026-06-01T10:00:00Z")
private const val A = "9780306406157"
private val B = "97800" + "2222222" + Isbn.check13("978002222222")

private class Fixture(market: Market = Market.FR) {
    val clock = HandClock(T0)
    val dir: File = Check.tempDir()
    val shop = Shop.open(dir, clock)
    val customers = Customers(File(dir, "customers.json"), clock)

    init {
        shop.configure(Settings("Test shop", market, if (market == Market.JP) "T1234567890123" else "", "", "UTC"))
        shop.saveBook(Book(A, mapOf("en" to "Paper Gardens"), listOf("C. Dumont"), "Atelier", "en", 2000, Condition.NEW, null, "Art"))
        shop.saveBook(Book(B, mapOf("en" to "Tide Café"), listOf("M. Lefèvre"), "Phare", "en", 1500, Condition.NEW, null, "Literature"))
        shop.saveSupplier(Supplier("atelier", "Atelier Ginkgo", 3500, true, 90, "acct 42"))
        shop.saveSupplier(Supplier("firm", "Firm Sale Ltd", 3000, false, 0, ""))
    }

    fun later(days: Long) { clock.now = clock.now.plus(Duration.ofDays(days)) }

    fun reopen(): Shop { shop.close(); return Shop.open(dir, clock) }
}

fun tradeTests() {
    test("a customer order waits, goes on a purchase order, is set aside on arrival and handed over at the till") {
        val f = Fixture()
        val s = f.shop
        val c = f.customers.add("Aiko Test", "06 00 00 00 00", "")
        val o = s.placeOrder(c.id, A, 1)
        eq(OrderState.WAITING, s.orderState(o))
        val sug = s.suggestions().single()
        eq(listOf(o.id), sug.forOrders)
        val po = s.createPurchaseOrder("atelier", listOf(A to 2), sug.forOrders)
        eq(1300L, po.lines.single().cost, "35 % off 20.00")
        eq(OrderState.ORDERED, s.orderState(o))
        eq(emptyList<Shop.Suggestion>(), s.suggestions(), "nothing more to order")
        s.receivePurchaseOrder(po.id, mapOf(A to 1))
        eq(OrderState.READY, s.orderState(o))
        ok(po.open, "one copy still to come")
        eq(0L, s.available(A), "the only copy is set aside")
        eq("error.stock", fails<ShopError> { s.sell(listOf(CartLine(LineKind.BOOK, A, 1)), "cash") }.key)
        val sale = s.sell(listOf(CartLine(LineKind.BOOK, A, 1, order = o.id)), "card")
        eq(OrderState.COLLECTED, s.orderState(o))
        eq(sale.id, o.sale)
        eq("error.notready", fails<ShopError> { s.sell(listOf(CartLine(LineKind.BOOK, A, 1, order = o.id)), "card") }.key)
        val again = f.reopen()
        eq(OrderState.COLLECTED, again.orderState(again.orders.getValue(o.id)), "rebuilt from the journal")
        eq(1L, again.purchaseOrders.getValue(po.id).lines.single().received)
        again.close()
    }
    test("the oldest customer order is served first, and a cancelled one frees its copy") {
        val f = Fixture()
        val s = f.shop
        s.receive(A, 1, 1300, "atelier")
        val first = s.placeOrder(f.customers.add("First", "", "").id, A, 1)
        val second = s.placeOrder(f.customers.add("Second", "", "").id, A, 1)
        eq(OrderState.READY, s.orderState(first))
        eq(OrderState.WAITING, s.orderState(second))
        s.cancelOrder(first.id)
        eq(OrderState.READY, s.orderState(second))
        eq("error.closed", fails<ShopError> { s.cancelOrder(first.id) }.key)
        s.close()
    }
    test("customers' names never reach the journal, and can be forgotten") {
        val f = Fixture()
        val c = f.customers.add("Mei Lin-Example", "+886 900 000 000", "wants the signed edition")
        f.shop.placeOrder(c.id, A, 1)
        val journal = File(f.dir, "journal.jsonl").readText()
        ok(!journal.contains("Mei Lin") && !journal.contains("886") && journal.contains(c.id), "only the number is in the journal")
        ok(File(f.dir, "customers.json").readText().contains("Mei Lin"), "the name is in the erasable file")
        eq(0, f.customers.forgetAllExcept(setOf(c.id)), "an open order keeps its customer")
        ok(f.customers.forget(c.id), "forgotten")
        eq(null, Customers(File(f.dir, "customers.json")).get(c.id), "and stays forgotten after a restart")
        eq("C00002", Customers(File(f.dir, "customers.json")).add("Next", "", "").id, "numbers are never reused")
        eq("error.customer", fails<ShopError> { f.customers.add(" ", "", "") }.key)
        f.shop.close()
    }
    test("a purchase order: partial delivery, no over-delivery, cancelling the rest") {
        val f = Fixture()
        val s = f.shop
        val po = s.createPurchaseOrder("atelier", listOf(A to 3, B to 2, A to 1))
        eq(4L, po.lines.first { it.isbn == A }.qty, "the same book twice is merged")
        eq("error.qty", fails<ShopError> { s.receivePurchaseOrder(po.id, mapOf(A to 5)) }.key)
        s.receivePurchaseOrder(po.id, mapOf(A to 4))
        eq(4L, s.stock.getValue(A).qty)
        eq("atelier", s.stock.getValue(A).lastSupplier)
        s.cancelPurchaseOrder(po.id)
        ok(!po.open, "closed")
        eq("error.closed", fails<ShopError> { s.receivePurchaseOrder(po.id, mapOf(B to 1)) }.key)
        eq("error.supplier", fails<ShopError> { s.createPurchaseOrder("nobody", listOf(A to 1)) }.key)
        s.close()
    }
    test("returns: only to a supplier who accepts them, within the window, never more than they delivered or a reserved copy") {
        val f = Fixture()
        val s = f.shop
        s.receive(A, 3, 1300, "Atelier Ginkgo") // the name is matched to the supplier
        s.receive(B, 2, 1050, "firm")
        eq(3L, s.returnable(A, "atelier"))
        eq(0L, s.returnable(B, "firm"), "firm sale")
        eq("error.noreturns", fails<ShopError> { s.returnToSupplier("firm", listOf(B to 1), "") }.key)
        val o = s.placeOrder(f.customers.add("Reader", "", "").id, A, 1)
        eq(2L, s.returnable(A, "atelier"), "the copy set aside for a customer stays")
        eq("error.return", fails<ShopError> { s.returnToSupplier("atelier", listOf(A to 3), "") }.key)
        val r = s.returnToSupplier("atelier", listOf(A to 2), "unsold")
        eq(2600L, r.credit)
        eq(1L, s.stock.getValue(A).qty)
        s.cancelOrder(o.id)
        eq(1L, s.returnable(A, "atelier"))
        f.later(91)
        eq(0L, s.returnable(A, "atelier"), "90-day window passed")
        val again = f.reopen()
        eq(2L, again.lots.getValue("$A|atelier").returned)
        eq(1L, again.stock.getValue(A).qty)
        again.close()
    }
    test("a stock count by section: unscanned books count as missing, other sections stay untouched") {
        val f = Fixture()
        val s = f.shop
        s.receive(A, 5, 1300, "atelier")
        s.receive(B, 4, 1000, "firm")
        val draft = CountDraft(File(f.dir, "count.json"), f.clock)
        draft.start("Art")
        repeat(3) { draft.scan(A, s) }
        draft.scan(B, s) // a Literature book left on the Art table
        eq(null, draft.scan("0000000000000", s), "not a book of the catalogue")
        val reloaded = CountDraft(File(f.dir, "count.json"), f.clock)
        eq(3L, reloaded.scans[A], "the count survives a restart")
        eq(listOf("0000000000000"), reloaded.unknown)
        eq("error.counting", fails<ShopError> { reloaded.start(null) }.key)
        eq(mapOf(A to -2L), reloaded.apply(s))
        eq(3L, s.stock.getValue(A).qty)
        eq(4L, s.stock.getValue(B).qty, "Literature not counted, even the copy found on the Art shelves")
        ok(!File(f.dir, "count.json").exists(), "draft removed")
        eq("count", s.journal.entries.last().type)
        s.close()
    }
    test("the sales ledger keeps a closed day closed: a refund is a negative line on its own day") {
        val f = Fixture(Market.JP)
        val s = f.shop
        s.saveProduct(Product("coffee", mapOf("en" to "Coffee"), 500))
        s.receive(A, 2, 1300, "atelier")
        val sale = s.sell(listOf(CartLine(LineKind.BOOK, A, 1), CartLine(LineKind.CAFE, "coffee", 1, takeaway = true)), "cash")
        f.later(1)
        s.refund(sale.id, "wrong book")
        val rep = Reports(s)
        val d0 = LocalDate.of(2026, 6, 1)
        val l = rep.ledger(d0, d0.plusDays(1))
        eq(listOf("sale", "sale", "refund", "refund"), l.map { it.type })
        eq(listOf(1000, 800, 1000, 800), l.map { it.rateBp })
        eq(0L, l.sumOf { it.gross }, "the refund cancels the sale over the two days")
        eq(2500L, rep.closing(d0).total)
        eq(-2500L, rep.closing(d0.plusDays(1)).byMethod["cash"])
        eq(1, rep.closing(d0.plusDays(1)).refunds)
        val csv = Csv.ledger(s, d0, d0.plusDays(1))
        ok(csv.startsWith("﻿date,type,receipt,payment,tax_rate,gross,tax,net,currency\r\n"), "header")
        ok(csv.contains("2026-06-02,refund,S000001,cash,10.00,-2000,-182,-1818,JPY"), "refund line: ${csv.lines()}")
        eq("-12.50", Csv.plain(-1250, Currency.EUR))
        val p = Csv.purchases(s, d0, d0)
        ok(p.contains("delivery") && p.contains("Atelier Ginkgo"), "purchases")
        s.close()
    }
    test("suggestions: a sold-out title that sold recently is proposed once, not while on order") {
        val f = Fixture()
        val s = f.shop
        s.receive(B, 1, 1000, "firm")
        s.sell(listOf(CartLine(LineKind.BOOK, B, 1)), "card")
        val sug = s.suggestions().single()
        ok(sug.soldOut && sug.supplier == "firm", "sold out, from the last supplier")
        s.createPurchaseOrder("firm", listOf(B to 2))
        eq(emptyList<Shop.Suggestion>(), s.suggestions())
        s.close()
    }
}

package shiori

import java.time.LocalDate

/*
 * The trade screens of the web interface: customer orders, suppliers (purchase orders and returns),
 * stock counts, and the accountant's pages. Same rules as Web.kt: forms only, no script, every change by POST.
 */

private fun Web.today(): LocalDate = LocalDate.now(shop.clock.withZone(shop.settings.zoneId))

private fun Web.title(isbn: String, lang: String) = shop.books[isbn]?.title(lang) ?: isbn

private fun Web.supplierName(id: String) = shop.suppliers[id]?.name ?: id.ifEmpty { "—" }

// ---------- customer orders ----------

internal fun Web.orders(r: Web.Req, rest: List<String>): Web.Res {
    val session = r.session!!
    if (rest.isEmpty() && r.method == "POST") {
        val f = r.form
        val isbn = Isbn.normalize(f["isbn"] ?: "")
        if (isbn == null) { session.flash = false to t(r, "error.isbn"); return redirect("/orders") }
        if (isbn !in shop.books) { session.flash = false to t(r, "order.addbook"); return redirect("/books/new?isbn=$isbn") }
        return try {
            val qty = f["qty"]?.trim()?.toLongOrNull() ?: throw ShopError("error.qty")
            if (qty !in 1..100) throw ShopError("error.qty")
            val c = customers.add(f["name"] ?: "", f["contact"] ?: "", (f["note"] ?: "").take(300))
            val o = try { shop.placeOrder(c.id, isbn, qty) } catch (e: ShopError) { customers.forget(c.id); throw e }
            session.flash = true to t(r, "order.saved", o.id)
            redirect("/orders/${o.id}")
        } catch (e: ShopError) {
            session.flash = false to err(r, e)
            redirect("/orders")
        }
    }
    if (rest.isEmpty()) {
        val ready = shop.readyOrders()
        val open = shop.orders.values.filter { !it.closed }.groupBy { shop.orderState(it, ready) }
        return page(r, t(r, "orders.title"), "orders") {
            tag("p", "class" to "help") { +t(r, "orders.intro") }
            for (st in listOf(OrderState.READY, OrderState.ORDERED, OrderState.WAITING)) {
                val list = open[st].orEmpty()
                tag("h2") { +"${t(r, "state.$st")} (${list.size})" }
                if (list.isEmpty()) tag("p", "class" to "help") { +t(r, "orders.none") } else orderTable(this, r, list, st)
            }
            tag("h2") { +t(r, "orders.new") }
            tag("form", "method" to "post", "action" to "/orders", "class" to "stack") {
                csrf(r)
                field("isbn", t(r, "book.isbn"), r.query["isbn"] ?: "", extra = arrayOf("required" to true, "inputmode" to "numeric"))
                field("qty", t(r, "col.qty"), "1", "number", extra = arrayOf("min" to 1, "max" to 100, "required" to true))
                field("name", t(r, "order.name"), extra = arrayOf("required" to true, "maxlength" to 80, "autocomplete" to "off"))
                field("contact", t(r, "order.contact"), help = t(r, "order.contact.help"), extra = arrayOf("maxlength" to 120, "autocomplete" to "off"))
                field("note", t(r, "order.note"), extra = arrayOf("maxlength" to 300))
                tag("p", "class" to "help") { +t(r, "order.privacy") }
                tag("button", "type" to "submit") { +t(r, "orders.add") }
            }
            val closed = shop.orders.values.filter { it.closed }.reversed().take(20)
            if (closed.isNotEmpty()) {
                tag("h2") { +t(r, "orders.closed") }
                orderTable(this, r, closed, null)
            }
        }
    }
    val o = shop.orders[rest[0]] ?: return notFound(r)
    if (r.method == "POST" && rest.size == 2) return when (rest[1]) {
        "notified" -> act(r, "/orders/${o.id}", t(r, "order.notified.ok")) { shop.markNotified(o.id) }
        "cancel" -> act(r, "/orders/${o.id}", t(r, "order.cancelled")) { shop.cancelOrder(o.id) }
        "collect" -> {
            if (shop.orderState(o) != OrderState.READY) { session.flash = false to t(r, "error.notready", o.id); return redirect("/orders/${o.id}") }
            synchronized(session.cart) {
                if (session.cart.none { it.order == o.id }) session.cart.add(CartLine(LineKind.BOOK, o.isbn, o.qty, null, false, o.id))
            }
            redirect("/sell")
        }
        "forget" -> {
            if (shop.orders.values.any { it.customer == o.customer && !it.closed }) { session.flash = false to t(r, "error.stillopen"); return redirect("/orders/${o.id}") }
            customers.forget(o.customer)
            session.flash = true to t(r, "order.forgotten")
            redirect("/orders/${o.id}")
        }
        else -> notFound(r)
    }
    if (rest.size != 1) return notFound(r)
    val st = shop.orderState(o)
    val c = customers.get(o.customer)
    return page(r, t(r, "order.title", o.id), "orders") {
        tag("p") { tag("a", "href" to "/books/${o.isbn}") { +title(o.isbn, r.lang) }; +" × ${o.qty}" }
        tag("div", "class" to "cards") {
            tag("div", "class" to "card") { +t(r, "order.state"); tag("b") { +t(r, "state.$st") } }
            tag("div", "class" to "card") { +t(r, "col.date"); tag("b") { +dateTime(r, o.at) } }
            tag("div", "class" to "card") { +t(r, "col.stock"); tag("b") { +(shop.stock[o.isbn]?.qty ?: 0).toString() } }
        }
        tag("h2") { +t(r, "order.customer") }
        if (c == null) tag("p", "class" to "help") { +t(r, "order.forgotten.info", o.customer) } else {
            tag("p") { +c.name; if (c.contact.isNotBlank()) +" · ${c.contact}" }
            if (c.note.isNotBlank()) tag("p", "class" to "help") { +c.note }
        }
        o.notified?.let { tag("p", "class" to "help") { +t(r, "order.notified.at", dateTime(r, it)) } }
        shop.purchaseOrders.values.filter { o.id in it.orders }.forEach { po -> tag("p", "class" to "help") { +t(r, "order.onpo"); raw(" "); tag("a", "href" to "/buy/po/${po.id}") { +po.id } } }
        o.sale?.let { tag("p") { tag("a", "href" to "/sales/$it") { +t(r, "receipt.no", it) } } }
        if (!o.closed) tag("div", "class" to "row") {
            if (st == OrderState.READY) postButton(r, "/orders/${o.id}/collect", t(r, "order.collect"), primary = true)
            postButton(r, "/orders/${o.id}/notified", t(r, "order.notify"))
            postButton(r, "/orders/${o.id}/cancel", t(r, "order.cancel"))
        }
        if (o.closed && c != null && shop.orders.values.none { it.customer == o.customer && !it.closed })
            tag("div", "class" to "row") { postButton(r, "/orders/${o.id}/forget", t(r, "order.forget")) }
    }
}

private fun Web.orderTable(h: Html, r: Web.Req, list: List<CustomerOrder>, st: OrderState?) = with(h) {
    tag("div", "class" to "tablewrap") { tag("table") {
        tag("thead") { tag("tr") {
            tag("th") { +t(r, "order.no") }; tag("th") { +t(r, "col.title") }; tag("th", "class" to "num") { +t(r, "col.qty") }
            tag("th") { +t(r, "order.customer") }; tag("th") { +t(r, "col.date") }
        } }
        tag("tbody") {
            for (o in list) tag("tr") {
                tag("td") { tag("a", "href" to "/orders/${o.id}") { +o.id } }
                tag("td") { +title(o.isbn, r.lang) }
                tag("td", "class" to "num") { +o.qty.toString() }
                tag("td") {
                    +(customers.get(o.customer)?.name ?: "—")
                    if (st == OrderState.READY && o.notified != null) +" · ${t(r, "order.told")}"
                    if (st == null) +" · ${t(r, "state.${shop.orderState(o)}")}"
                }
                tag("td") { +date(r, o.at.atZone(shop.settings.zoneId).toLocalDate()) }
            }
        }
    } }
}

private fun Html.postButton(r: Web.Req, action: String, label: String, primary: Boolean = false, vararg hidden: Pair<String, String>) {
    tag("form", "method" to "post", "action" to action, "class" to "inline") {
        tag("input", "type" to "hidden", "name" to "csrf", "value" to r.session!!.csrf)
        for ((k, v) in hidden) tag("input", "type" to "hidden", "name" to k, "value" to v)
        tag("button", "type" to "submit", "class" to if (primary) null else "secondary") { +label }
    }
}

// ---------- suppliers, purchase orders, returns ----------

internal fun Web.buy(r: Web.Req, rest: List<String>): Web.Res {
    val session = r.session!!
    val f = r.form
    when {
        r.method == "POST" && rest == listOf("suppliers") -> return act(r, "/buy", t(r, "supplier.saved")) {
            val pct = (f["discount"] ?: "").trim().replace(',', '.').ifEmpty { "0" }.toBigDecimalOrNull() ?: throw ShopError("error.discountbp")
            shop.saveSupplier(Supplier(
                (f["id"] ?: "").trim(), (f["sname"] ?: "").trim(), pct.movePointRight(2).toInt(), f["returnable"] == "1",
                (f["days"] ?: "").trim().ifEmpty { "0" }.toIntOrNull() ?: throw ShopError("error.days"), f["contact"] ?: "",
            ))
        }
        r.method == "POST" && rest == listOf("po") -> {
            // From the suggestions form (q_ISBN, s_ISBN) or from a book page (isbn, qty, supplier).
            val wanted = LinkedHashMap<String, MutableList<Pair<String, Long>>>()
            if (f["isbn"] != null) {
                val q = f["qty"]?.trim()?.toLongOrNull() ?: 0
                wanted.getOrPut(f["supplier"] ?: "") { ArrayList() } += (f["isbn"] ?: "") to q
            } else for ((k, v) in f) if (k.startsWith("q_")) {
                val q = v.trim().toLongOrNull() ?: 0
                if (q > 0) wanted.getOrPut(f["s_" + k.removePrefix("q_")] ?: "") { ArrayList() } += k.removePrefix("q_") to q
            }
            if (wanted.isEmpty()) { session.flash = false to t(r, "error.empty"); return redirect("/buy") }
            val waiting = shop.suggestions().flatMap { s -> s.forOrders }
            val made = ArrayList<String>()
            try {
                for ((sup, lines) in wanted) made += shop.createPurchaseOrder(sup, lines, waiting).id
                session.flash = true to t(r, "po.created", made.joinToString(", "))
            } catch (e: ShopError) {
                session.flash = false to (if (made.isEmpty()) "" else t(r, "po.created", made.joinToString(", ")) + " ") + err(r, e)
            }
            return redirect(if (made.size == 1) "/buy/po/${made[0]}" else "/buy")
        }
        rest.firstOrNull() == "po" && rest.size >= 2 -> return purchaseOrder(r, rest)
        rest.firstOrNull() == "returns" -> return returns(r, rest.drop(1))
        rest.isNotEmpty() -> return notFound(r)
    }
    val sugg = shop.suggestions()
    return page(r, t(r, "buy.title"), "buy") {
        tag("p", "class" to "help") { +t(r, "buy.intro") }
        tag("p") { tag("a", "class" to "button secondary", "href" to "/buy/returns") { +t(r, "returns.title") } }
        tag("h2") { +t(r, "buy.suggest") }
        if (shop.suppliers.isEmpty()) tag("p") { +t(r, "buy.nosupplier") }
        else if (sugg.isEmpty()) tag("p", "class" to "help") { +t(r, "buy.nothing") }
        else tag("form", "method" to "post", "action" to "/buy/po") {
            csrf(r)
            tag("div", "class" to "tablewrap") { tag("table") {
                tag("thead") { tag("tr") {
                    tag("th") { +t(r, "col.title") }; tag("th") { +t(r, "buy.why") }; tag("th") { +t(r, "col.qty") }; tag("th") { +t(r, "buy.supplier") }
                } }
                tag("tbody") {
                    for (s in sugg) tag("tr") {
                        tag("td") { tag("a", "href" to "/books/${s.isbn}") { +title(s.isbn, r.lang) } }
                        tag("td") { +(if (s.forOrders.isNotEmpty()) t(r, "buy.forcustomers", s.forOrders.joinToString(", ")) else t(r, "buy.soldout")) }
                        tag("td") {
                            tag("label", "for" to "q_${s.isbn}", "class" to "help") { +t(r, "col.qty") }
                            tag("input", "id" to "q_${s.isbn}", "name" to "q_${s.isbn}", "type" to "number", "min" to 0, "max" to 10000, "value" to s.qty)
                        }
                        tag("td") {
                            tag("label", "for" to "s_${s.isbn}", "class" to "help") { +t(r, "buy.supplier") }
                            tag("select", "id" to "s_${s.isbn}", "name" to "s_${s.isbn}") {
                                for (sup in shop.suppliers.values) tag("option", "value" to sup.id, "selected" to (sup.id == s.supplier)) { +sup.name }
                            }
                        }
                    }
                }
            } }
            tag("button", "type" to "submit") { +t(r, "buy.create") }
        }
        tag("h2") { +t(r, "buy.open") }
        val open = shop.purchaseOrders.values.filter { it.open }.reversed()
        if (open.isEmpty()) tag("p", "class" to "help") { +t(r, "buy.noopen") } else poTable(this, r, open)
        val done = shop.purchaseOrders.values.filter { !it.open }.reversed().take(20)
        if (done.isNotEmpty()) { tag("h2") { +t(r, "buy.done") }; poTable(this, r, done) }
        tag("h2") { +t(r, "buy.suppliers") }
        if (shop.suppliers.isNotEmpty()) tag("div", "class" to "tablewrap") { tag("table") {
            tag("thead") { tag("tr") {
                tag("th") { +t(r, "supplier.name") }; tag("th", "class" to "num") { +t(r, "supplier.discount") }; tag("th") { +t(r, "supplier.returns") }; tag("th") { +t(r, "supplier.contact") }
            } }
            tag("tbody") {
                for (s in shop.suppliers.values) tag("tr") {
                    tag("td") { +"${s.name} (${s.id})" }
                    tag("td", "class" to "num") { +"${rate(s.discountBp)} %" }
                    tag("td") { +(if (!s.returnable) t(r, "supplier.noreturn") else if (s.returnDays == 0) t(r, "supplier.anytime") else t(r, "supplier.within", s.returnDays)) }
                    tag("td") { +s.contact }
                }
            }
        } }
        tag("h3") { +t(r, "supplier.add") }
        tag("form", "method" to "post", "action" to "/buy/suppliers", "class" to "row") {
            csrf(r)
            field("id", t(r, "settings.product.id"), extra = arrayOf("pattern" to "[a-z0-9-]{1,40}", "required" to true))
            field("sname", t(r, "supplier.name"), extra = arrayOf("required" to true, "maxlength" to 120))
            field("discount", t(r, "supplier.discount"), "", help = "%", extra = arrayOf("inputmode" to "decimal"))
            tag("div") {
                tag("label", "for" to "returnable") { +t(r, "supplier.returnable") }
                tag("select", "id" to "returnable", "name" to "returnable") {
                    tag("option", "value" to "1") { +t(r, "yes") }; tag("option", "value" to "0") { +t(r, "no") }
                }
            }
            field("days", t(r, "supplier.days"), "", "number", help = t(r, "supplier.days.help"), extra = arrayOf("min" to 0, "max" to 3650))
            field("contact", t(r, "supplier.contact"), extra = arrayOf("maxlength" to 200))
            tag("div") { tag("button", "type" to "submit", "class" to "secondary") { +t(r, "supplier.add") } }
        }
        tag("p", "class" to "help") { +t(r, "supplier.help") }
    }
}

private fun Web.poTable(h: Html, r: Web.Req, list: List<PurchaseOrder>) = with(h) {
    tag("div", "class" to "tablewrap") { tag("table") {
        tag("thead") { tag("tr") {
            tag("th") { +t(r, "po.no") }; tag("th") { +t(r, "buy.supplier") }; tag("th") { +t(r, "col.date") }
            tag("th", "class" to "num") { +t(r, "po.copies") }; tag("th", "class" to "num") { +t(r, "po.remaining") }
        } }
        tag("tbody") {
            for (po in list) tag("tr") {
                tag("td") { tag("a", "href" to "/buy/po/${po.id}") { +po.id } }
                tag("td") { +supplierName(po.supplier) }
                tag("td") { +date(r, po.at.atZone(shop.settings.zoneId).toLocalDate()) }
                tag("td", "class" to "num") { +po.lines.sumOf { it.qty }.toString() }
                tag("td", "class" to "num") { +(if (po.cancelled != null) t(r, "po.cancelled") else po.lines.sumOf { it.remaining }.toString()) }
            }
        }
    } }
}

private fun Web.purchaseOrder(r: Web.Req, rest: List<String>): Web.Res {
    val po = shop.purchaseOrders[rest[1]] ?: return notFound(r)
    if (r.method == "POST" && rest.size == 3) return when (rest[2]) {
        "receive" -> act(r, "/buy/po/${po.id}", t(r, "book.received")) {
            shop.receivePurchaseOrder(po.id, po.lines.associate { it.isbn to ((r.form["r_${it.isbn}"] ?: "").trim().ifEmpty { "0" }.toLongOrNull() ?: throw ShopError("error.qty")) })
        }
        "cancel" -> act(r, "/buy/po/${po.id}", t(r, "po.cancelled")) { shop.cancelPurchaseOrder(po.id) }
        else -> notFound(r)
    }
    if (rest.size != 2 || r.method != "GET") return notFound(r)
    val sup = shop.suppliers[po.supplier]
    return page(r, t(r, "po.title", po.id), "buy") {
        tag("div", "class" to "receipt wide") {
            tag("div") { tag("strong") { +shop.settings.name } }
            if (shop.settings.address.isNotBlank()) tag("div") { +shop.settings.address }
            tag("div") { +t(r, "po.to", sup?.name ?: po.supplier); if (sup != null && sup.contact.isNotBlank()) +" · ${sup.contact}" }
            tag("div") { +dateTime(r, po.at) }
            po.cancelled?.let { tag("div") { tag("strong") { +"${t(r, "po.cancelled")} · ${dateTime(r, it)}" } } }
            tag("hr")
            for (l in po.lines) tag("div", "class" to "r") {
                tag("span") { +"${title(l.isbn, r.lang)} · ISBN ${l.isbn}" }
                tag("span") { +"× ${l.qty}" }
            }
        }
        if (po.orders.isNotEmpty()) tag("p", "class" to "help") {
            +t(r, "po.serves"); raw(" ")
            po.orders.forEachIndexed { i, o -> if (i > 0) raw(", "); tag("a", "href" to "/orders/$o") { +o } }
        }
        if (po.open) {
            tag("h2") { +t(r, "po.receive") }
            tag("form", "method" to "post", "action" to "/buy/po/${po.id}/receive") {
                csrf(r)
                tag("div", "class" to "tablewrap") { tag("table") {
                    tag("thead") { tag("tr") {
                        tag("th") { +t(r, "col.title") }; tag("th", "class" to "num") { +t(r, "po.ordered") }; tag("th", "class" to "num") { +t(r, "po.received") }
                        tag("th", "class" to "num") { +t(r, "book.receive.cost") }; tag("th") { +t(r, "po.arrived") }
                    } }
                    tag("tbody") {
                        for (l in po.lines) tag("tr") {
                            tag("td") { +title(l.isbn, r.lang) }
                            tag("td", "class" to "num") { +l.qty.toString() }
                            tag("td", "class" to "num") { +l.received.toString() }
                            tag("td", "class" to "num") { +money(l.cost) }
                            tag("td") {
                                tag("label", "for" to "r_${l.isbn}", "class" to "help") { +t(r, "po.arrived") }
                                tag("input", "id" to "r_${l.isbn}", "name" to "r_${l.isbn}", "type" to "number", "min" to 0, "max" to l.remaining, "value" to l.remaining)
                            }
                        }
                    }
                } }
                tag("button", "type" to "submit") { +t(r, "po.receive.button") }
            }
            tag("div", "class" to "row noprint") { postButton(r, "/buy/po/${po.id}/cancel", t(r, "po.cancel")) }
        }
    }
}

private fun Web.returns(r: Web.Req, rest: List<String>): Web.Res {
    val session = r.session!!
    if (rest.isEmpty() && r.method == "POST") {
        val sup = r.form["supplier"] ?: ""
        val lines = r.form.filterKeys { it.startsWith("q_") }.map { (k, v) -> k.removePrefix("q_") to (v.trim().ifEmpty { "0" }.toLongOrNull() ?: -1L) }.filter { it.second != 0L }
        return try {
            val ret = shop.returnToSupplier(sup, lines, r.form["reason"] ?: "")
            session.flash = true to t(r, "return.saved", ret.id)
            redirect("/buy/returns/${ret.id}")
        } catch (e: ShopError) {
            session.flash = false to err(r, e)
            redirect("/buy/returns")
        }
    }
    if (rest.isEmpty()) {
        val ready = shop.readyOrders()
        val sleeping = Reports(shop).sleeping(90).associate { it.isbn to it.days }
        val all = r.query["all"] == "1"
        return page(r, t(r, "returns.title"), "buy") {
            tag("p", "class" to "help") { +t(r, "returns.intro") }
            tag("p") { tag("a", "href" to if (all) "/buy/returns" else "/buy/returns?all=1") { +t(r, if (all) "returns.asleeponly" else "returns.all") } }
            var any = false
            for (sup in shop.suppliers.values.filter { it.returnable }) {
                val cands = shop.books.keys.filter { all || it in sleeping }.mapNotNull { isbn -> shop.returnable(isbn, sup.id, ready = ready).takeIf { it > 0 }?.let { isbn to it } }
                    .sortedByDescending { sleeping[it.first] ?: -1 }.take(100)
                if (cands.isEmpty()) continue
                any = true
                tag("h2") { +sup.name }
                tag("form", "method" to "post", "action" to "/buy/returns") {
                    csrf(r)
                    tag("input", "type" to "hidden", "name" to "supplier", "value" to sup.id)
                    tag("div", "class" to "tablewrap") { tag("table") {
                        tag("thead") { tag("tr") {
                            tag("th") { +t(r, "col.title") }; tag("th", "class" to "num") { +t(r, "returns.max") }; tag("th") { +t(r, "returns.asleep") }; tag("th") { +t(r, "col.qty") }
                        } }
                        tag("tbody") {
                            for ((isbn, max) in cands) tag("tr") {
                                tag("td") { tag("a", "href" to "/books/$isbn") { +title(isbn, r.lang) } }
                                tag("td", "class" to "num") { +max.toString() }
                                tag("td") { +(sleeping[isbn]?.let { t(r, "returns.days", it) } ?: "") }
                                tag("td") {
                                    tag("label", "for" to "q_${sup.id}_$isbn", "class" to "help") { +t(r, "col.qty") }
                                    tag("input", "id" to "q_${sup.id}_$isbn", "name" to "q_$isbn", "type" to "number", "min" to 0, "max" to max, "value" to 0)
                                }
                            }
                        }
                    } }
                    field("reason", t(r, "returns.reason"), extra = arrayOf("maxlength" to 200))
                    tag("button", "type" to "submit", "class" to "secondary") { +t(r, "returns.button", sup.name) }
                }
            }
            if (!any) tag("p") { +t(r, "returns.none") }
            if (shop.returns.isNotEmpty()) {
                tag("h2") { +t(r, "returns.past") }
                tag("div", "class" to "tablewrap") { tag("table") {
                    tag("thead") { tag("tr") {
                        tag("th") { +t(r, "return.no") }; tag("th") { +t(r, "buy.supplier") }; tag("th") { +t(r, "col.date") }
                        tag("th", "class" to "num") { +t(r, "po.copies") }; tag("th", "class" to "num") { +t(r, "return.credit") }
                    } }
                    tag("tbody") {
                        for (x in shop.returns.values.reversed().take(50)) tag("tr") {
                            tag("td") { tag("a", "href" to "/buy/returns/${x.id}") { +x.id } }
                            tag("td") { +supplierName(x.supplier) }
                            tag("td") { +date(r, x.at.atZone(shop.settings.zoneId).toLocalDate()) }
                            tag("td", "class" to "num") { +x.lines.sumOf { it.qty }.toString() }
                            tag("td", "class" to "num") { +money(x.credit) }
                        }
                    }
                } }
            }
        }
    }
    val x = shop.returns[rest[0]] ?: return notFound(r)
    if (rest.size != 1 || r.method != "GET") return notFound(r)
    return page(r, t(r, "return.title", x.id), "buy") {
        tag("div", "class" to "receipt wide") {
            tag("div") { tag("strong") { +shop.settings.name } }
            if (shop.settings.address.isNotBlank()) tag("div") { +shop.settings.address }
            tag("div") { +t(r, "po.to", supplierName(x.supplier)) }
            tag("div") { +dateTime(r, x.at) }
            if (x.reason.isNotBlank()) tag("div") { +x.reason }
            tag("hr")
            for (l in x.lines) {
                tag("div", "class" to "r") { tag("span") { +"${title(l.isbn, r.lang)} · ISBN ${l.isbn}" }; tag("span") { +"× ${l.qty}" } }
                tag("div", "class" to "help") { +"${l.qty} × ${money(l.cost)}" }
            }
            tag("hr")
            tag("div", "class" to "r") { tag("strong") { +t(r, "return.credit") }; tag("strong") { +money(x.credit) } }
        }
    }
}

// ---------- stock count ----------

internal fun Web.count(r: Web.Req, rest: List<String>): Web.Res {
    val session = r.session!!
    if (r.method == "POST") {
        try {
            when (rest.firstOrNull()) {
                "start" -> count.start((r.form["section"] ?: "").takeIf { it.isNotEmpty() })
                "scan" -> if (count.scan(r.form["code"] ?: "", shop) == null) session.flash = false to t(r, "count.unknown")
                "set" -> {
                    val isbn = r.form["isbn"] ?: ""
                    if (isbn !in shop.books) throw ShopError("error.unknown")
                    count.set(isbn, r.form["qty"]?.trim()?.toLongOrNull() ?: throw ShopError("error.qty"))
                }
                "clear" -> { count.clear(); session.flash = true to t(r, "count.abandoned") }
                "apply" -> {
                    val d = count.apply(shop)
                    session.flash = true to t(r, "count.applied", d.size)
                    return redirect("/books")
                }
                else -> return notFound(r)
            }
        } catch (e: ShopError) {
            session.flash = false to err(r, e)
        }
        return redirect(if (rest.firstOrNull() == "set") "/count/review" else "/count")
    }
    if (rest == listOf("review") && count.active) return countReview(r)
    if (rest.isNotEmpty()) return redirect("/count")
    if (!count.active) return page(r, t(r, "count.title"), "books") {
        tag("p") { +t(r, "count.intro") }
        tag("p", "class" to "help") { +t(r, "count.scanner") }
        tag("form", "method" to "post", "action" to "/count/start", "class" to "row") {
            csrf(r)
            tag("div") {
                tag("label", "for" to "section") { +t(r, "count.scope") }
                tag("select", "id" to "section", "name" to "section") {
                    tag("option", "value" to "") { +t(r, "count.all") }
                    for (s in shop.books.values.map { it.section }.filter { it.isNotBlank() }.distinct().sorted()) tag("option", "value" to s) { +s }
                }
            }
            tag("div") { tag("button", "type" to "submit") { +t(r, "count.start") } }
        }
    }
    val total = count.scans.values.sum()
    return page(r, t(r, "count.title") + " · " + (count.section ?: t(r, "count.all")), "books") {
        tag("form", "method" to "post", "action" to "/count/scan", "class" to "row") {
            csrf(r)
            tag("div") {
                tag("label", "for" to "code") { +t(r, "sell.scan") }
                tag("input", "id" to "code", "name" to "code", "inputmode" to "numeric", "autocomplete" to "off", "autofocus" to true)
            }
            tag("div") { tag("button", "type" to "submit") { +t(r, "count.add") } }
        }
        count.last?.let { last -> tag("p", "class" to "msg ok", "role" to "status") { +t(r, "count.last", title(last, r.lang), count.scans[last] ?: 0) } }
        tag("p") { +t(r, "count.progress", total, count.scans.size) }
        tag("div", "class" to "row") {
            tag("a", "class" to "button", "href" to "/count/review") { +t(r, "count.review") }
            postButton(r, "/count/clear", t(r, "count.abandon"))
        }
        if (count.unknown.isNotEmpty()) {
            tag("h2") { +t(r, "count.unknowns") }
            tag("ul") { for (c in count.unknown.takeLast(20)) tag("li") { tag("a", "href" to "/books/new?isbn=${enc(c)}") { +c } } }
        }
    }
}

private fun Web.countReview(r: Web.Req): Web.Res {
    val section = count.section
    val scope = shop.books.values.filter { section == null || it.section == section }.map { it.isbn }.toSet()
    val elsewhere = count.scans.keys.filter { it !in scope }
    val rows = scope.map { isbn -> Triple(isbn, shop.stock[isbn]?.qty ?: 0, count.scans[isbn] ?: 0) }.filter { it.second != 0L || it.third != 0L }
        .sortedWith(compareByDescending<Triple<String, Long, Long>> { Math.abs(it.third - it.second) }.thenBy { title(it.first, r.lang) })
    return page(r, t(r, "count.review"), "books") {
        tag("p", "class" to "help") { +t(r, "count.review.help") }
        tag("div", "class" to "tablewrap") { tag("table") {
            tag("thead") { tag("tr") {
                tag("th") { +t(r, "col.title") }; tag("th", "class" to "num") { +t(r, "count.expected") }; tag("th", "class" to "num") { +t(r, "count.counted") }
                tag("th", "class" to "num") { +t(r, "count.diff") }; tag("th") { +t(r, "count.fix") }
            } }
            tag("tbody") {
                for ((isbn, exp, got) in rows) tag("tr") {
                    tag("td") { +title(isbn, r.lang) }
                    tag("td", "class" to "num") { +exp.toString() }
                    tag("td", "class" to "num") { +got.toString() }
                    tag("td", "class" to "num") { +((got - exp).let { if (it > 0) "+$it" else it.toString() }) }
                    tag("td") {
                        tag("form", "method" to "post", "action" to "/count/set", "class" to "row") {
                            csrf(r)
                            tag("input", "type" to "hidden", "name" to "isbn", "value" to isbn)
                            tag("div") { tag("label", "for" to "c_$isbn", "class" to "help") { +t(r, "count.counted") }; tag("input", "id" to "c_$isbn", "name" to "qty", "type" to "number", "min" to 0, "value" to got) }
                            tag("div") { tag("button", "type" to "submit", "class" to "secondary") { +t(r, "sell.apply") } }
                        }
                    }
                }
            }
        } }
        if (elsewhere.isNotEmpty()) {
            tag("h2") { +t(r, "count.elsewhere") }
            tag("p", "class" to "help") { +t(r, "count.elsewhere.help") }
            tag("ul") { for (isbn in elsewhere) tag("li") { +"${title(isbn, r.lang)} · ${shop.books[isbn]?.section ?: ""} · ${count.scans[isbn]}" } }
        }
        tag("div", "class" to "row") {
            postButton(r, "/count/apply", t(r, "count.apply"), primary = true)
            tag("a", "class" to "button secondary", "href" to "/count") { +t(r, "count.back") }
        }
    }
}

// ---------- the accountant's pages ----------

private fun Web.period(r: Web.Req): Pair<LocalDate, LocalDate> {
    val to = r.query["to"]?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: today()
    val from = r.query["from"]?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: to.withDayOfMonth(1)
    return if (from.isAfter(to) || from.isBefore(to.minusYears(3))) to.withDayOfMonth(1) to to else from to to
}

internal fun Web.exportLedger(r: Web.Req): Web.Res {
    val (from, to) = period(r)
    return Web.Res(200, Csv.ledger(shop, from, to).toByteArray(), "text/csv; charset=utf-8", mapOf("Content-Disposition" to "attachment; filename=\"shiori-sales-$from-$to.csv\""))
}

internal fun Web.exportPurchases(r: Web.Req): Web.Res {
    val (from, to) = period(r)
    return Web.Res(200, Csv.purchases(shop, from, to).toByteArray(), "text/csv; charset=utf-8", mapOf("Content-Disposition" to "attachment; filename=\"shiori-purchases-$from-$to.csv\""))
}

internal fun Web.closing(r: Web.Req): Web.Res {
    val day = r.query["d"]?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: today()
    val c = Reports(shop).closing(day)
    return page(r, t(r, "closing.title") + " · " + date(r, day), "reports") {
        tag("form", "method" to "get", "action" to "/reports/day", "class" to "row noprint") {
            field("d", t(r, "col.date"), day.toString(), "date")
            tag("div") { tag("button", "type" to "submit", "class" to "secondary") { +"OK" } }
        }
        tag("div", "class" to "receipt wide") {
            tag("div") { tag("strong") { +shop.settings.name } }
            tag("div") { +(t(r, "closing.title") + " · " + date(r, day)) }
            tag("div") { +t(r, "closing.counts", c.sales, c.refunds) }
            tag("hr")
            for (m in listOf("cash", "card", "other")) tag("div", "class" to "r") { tag("span") { +t(r, "method.$m") }; tag("span") { +money(c.byMethod[m] ?: 0) } }
            tag("hr")
            tag("div", "class" to "r") { tag("strong") { +t(r, "receipt.total") }; tag("strong") { +money(c.total) } }
            for ((bp, gt) in c.byRate) tag("div", "class" to "help") {
                +if (bp == 0) t(r, "receipt.exempt", money(gt.first)) else t(r, "receipt.rate", rate(bp), money(gt.first), money(gt.second))
            }
            tag("hr")
            tag("div") { +t(r, "closing.cash", money(c.byMethod["cash"] ?: 0)) }
        }
    }
}

internal fun Web.forgetCustomers(r: Web.Req): Web.Res {
    val keep = shop.orders.values.filter { !it.closed }.map { it.customer }.toSet()
    val n = customers.forgetAllExcept(keep)
    r.session!!.flash = true to t(r, "customers.forgotten", n)
    return redirect("/settings")
}

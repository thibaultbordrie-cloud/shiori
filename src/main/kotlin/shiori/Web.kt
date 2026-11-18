package shiori

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import java.util.concurrent.Executors

/**
 * The shop's web interface, served by the JDK's own HTTP server: no framework, no JavaScript,
 * forms only. Every change goes through a POST with a per-session token; every page sends a
 * strict Content-Security-Policy.
 */
class Web(
    internal val shop: Shop,
    private val auth: Auth,
    private val lookup: Lookup,
    internal val customers: Customers,
    internal val count: CountDraft,
) {
    private lateinit var server: HttpServer
    private val allowedHosts = HashSet<String>()

    val port: Int get() = server.address.port

    fun start(host: String, port: Int): Web {
        server = HttpServer.create(InetSocketAddress(host, port), 64)
        server.createContext("/") { ex -> handle(ex) }
        server.executor = Executors.newFixedThreadPool(8)
        server.start()
        val p = server.address.port
        allowedHosts += listOf("127.0.0.1:$p", "localhost:$p", "[::1]:$p")
        if (host != "127.0.0.1" && host != "localhost") allowedHosts += "$host:$p"
        return this
    }

    /** For a shop that opens Shiori from tablets on its own network (--host 0.0.0.0): the names they will use. */
    fun allowHost(nameAndPort: String) { allowedHosts += nameAndPort.lowercase() }

    fun stop() = server.stop(0)

    // ---------- plumbing ----------

    internal class Req(
        val method: String, val path: String, val query: Map<String, String>, val form: Map<String, String>,
        val cookies: Map<String, String>, var lang: String, val session: Auth.Session?, val sid: String?,
    )

    internal class Res(val status: Int, val body: ByteArray, val type: String = "text/html; charset=utf-8", val headers: Map<String, String> = emptyMap())

    internal class Halt(val res: Res) : RuntimeException()

    private fun handle(ex: HttpExchange) {
        val res = try {
            route(ex)
        } catch (h: Halt) {
            h.res
        } catch (e: Exception) {
            System.err.println("shiori: ${e::class.simpleName}: ${e.message}")
            Res(500, "Internal error".toByteArray(), "text/plain; charset=utf-8")
        }
        val h = ex.responseHeaders
        h["Content-Type"] = listOf(res.type)
        h["Content-Security-Policy"] = listOf("default-src 'none'; style-src 'self'; img-src 'self'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'")
        h["X-Content-Type-Options"] = listOf("nosniff")
        h["Referrer-Policy"] = listOf("same-origin")
        h["Cross-Origin-Opener-Policy"] = listOf("same-origin")
        h["Cache-Control"] = listOf(if (res.type.startsWith("text/css")) "max-age=3600" else "no-store")
        for ((k, v) in res.headers) h.add(k, v)
        val len = if (res.status == 204 || res.status == 304) -1L else res.body.size.toLong()
        ex.sendResponseHeaders(res.status, len)
        if (len > 0) ex.responseBody.use { it.write(res.body) } else ex.responseBody.close()
    }

    private fun parsePairs(s: String?): Map<String, String> {
        if (s.isNullOrEmpty()) return emptyMap()
        val m = LinkedHashMap<String, String>()
        for (part in s.split('&')) {
            if (part.isEmpty()) continue
            val k = URLDecoder.decode(part.substringBefore('='), UTF_8)
            val v = URLDecoder.decode(part.substringAfter('=', ""), UTF_8)
            if (k !in m) m[k] = v
        }
        return m
    }

    private fun route(ex: HttpExchange): Res {
        val host = ex.requestHeaders.getFirst("Host")?.lowercase()
        // Refusing unknown Host names blocks DNS-rebinding pages from reaching a shop running on localhost.
        if (host == null || host !in allowedHosts) return Res(421, "Unknown host".toByteArray(), "text/plain; charset=utf-8")
        val method = ex.requestMethod
        val uri = ex.requestURI
        val path = uri.rawPath
        if (method != "GET" && method != "POST") return Res(405, ByteArray(0), headers = mapOf("Allow" to "GET, POST"))
        val form = if (method == "POST") {
            val ct = ex.requestHeaders.getFirst("Content-Type") ?: ""
            if (!ct.startsWith("application/x-www-form-urlencoded")) return Res(415, ByteArray(0))
            val origin = ex.requestHeaders.getFirst("Origin")
            if (origin != null && origin.removePrefix("http://").removePrefix("https://").lowercase() != host) return Res(403, "Cross-site request refused".toByteArray(), "text/plain; charset=utf-8")
            val bytes = ex.requestBody.readNBytes(1_000_001)
            if (bytes.size > 1_000_000) return Res(413, ByteArray(0))
            parsePairs(String(bytes, UTF_8))
        } else emptyMap()
        val cookies = (ex.requestHeaders["Cookie"] ?: emptyList()).flatMap { it.split(';') }
            .associate { it.substringBefore('=').trim() to it.substringAfter('=', "").trim() }
        val sid = cookies["shiori_session"]
        val session = auth.session(sid)
        val lang = cookies["shiori_lang"]?.takeIf { it in I18n.LOCALES } ?: shop.settings.market.defaultLocale.takeIf { it in I18n.LOCALES } ?: I18n.pick(ex.requestHeaders.getFirst("Accept-Language"))
        val r = Req(method, path, parsePairs(uri.rawQuery), form, cookies, lang, session, sid)

        if (path == "/style.css") return Res(200, CSS.toByteArray(), "text/css; charset=utf-8")
        if (path == "/lang" && method == "GET") return setLang(r)
        if (!auth.isSetUp) return if (path == "/setup") setup(r) else redirect("/setup")
        if (path == "/setup") return redirect("/")
        if (path == "/login") return login(r)
        if (session == null) return redirect("/login")
        if (method == "POST" && r.form["csrf"] != session.csrf) {
            session.flash = false to t(r, "error.csrf")
            return redirect("/")
        }
        val seg = path.trim('/').split('/').filter { it.isNotEmpty() }.map { URLDecoder.decode(it, UTF_8) }
        return when {
            method == "POST" && path == "/logout" -> { auth.logout(sid); redirect("/login", cookie("shiori_session", "", 0)) }
            method == "GET" && seg.isEmpty() -> home(r)
            seg.firstOrNull() == "books" -> books(r, seg.drop(1))
            seg.firstOrNull() == "sell" -> sell(r, seg.drop(1))
            seg.firstOrNull() == "sales" -> sales(r, seg.drop(1))
            seg.firstOrNull() == "orders" -> orders(r, seg.drop(1))
            seg.firstOrNull() == "buy" -> buy(r, seg.drop(1))
            seg.firstOrNull() == "count" -> count(r, seg.drop(1))
            method == "GET" && path == "/reports/day" -> closing(r)
            method == "GET" && path == "/export/ledger.csv" -> exportLedger(r)
            method == "GET" && path == "/export/purchases.csv" -> exportPurchases(r)
            method == "POST" && path == "/settings/forget" -> forgetCustomers(r)
            method == "GET" && path == "/reports" -> reports(r)
            method == "GET" && path == "/export/catalog.csv" ->
                Res(200, Csv.export(shop, r.lang).toByteArray(), "text/csv; charset=utf-8", mapOf("Content-Disposition" to "attachment; filename=\"shiori-catalogue.csv\""))
            seg.firstOrNull() == "settings" -> settings(r, seg.drop(1))
            else -> notFound(r)
        }
    }

    internal fun t(r: Req, key: String, vararg a: Any) = I18n.t(r.lang, key, *a)
    internal fun money(v: Long) = shop.settings.market.currency.format(v)
    internal fun err(r: Req, e: ShopError) = t(r, e.key, *e.args)

    private fun cookie(name: String, value: String, maxAge: Int?) =
        "Set-Cookie" to "$name=$value; Path=/; HttpOnly; SameSite=Strict" + (maxAge?.let { "; Max-Age=$it" } ?: "")

    internal fun redirect(to: String, vararg headers: Pair<String, String>) = Res(303, ByteArray(0), headers = mapOf("Location" to to) + headers)

    internal fun enc(s: String) = URLEncoder.encode(s, UTF_8)

    internal fun dateTime(r: Req, at: java.time.Instant): String =
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(Locale.forLanguageTag(r.lang)).format(at.atZone(shop.settings.zoneId))

    internal fun date(r: Req, d: LocalDate): String = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.forLanguageTag(r.lang)).format(d)

    internal fun page(r: Req, title: String, active: String?, status: Int = 200, body: Html.() -> Unit): Res {
        val h = Html()
        h.raw("<!doctype html>")
        h.tag("html", "lang" to r.lang) {
            tag("head") {
                tag("meta", "charset" to "utf-8")
                tag("meta", "name" to "viewport", "content" to "width=device-width, initial-scale=1")
                tag("title") { +"$title · Shiori" }
                tag("link", "rel" to "stylesheet", "href" to "/style.css")
            }
            tag("body") {
                tag("a", "class" to "skip", "href" to "#main") { +t(r, "skip") }
                tag("header") {
                    tag("a", "class" to "brand", "href" to "/") { +"栞 Shiori · ${shop.settings.name}" }
                    if (r.session != null) {
                        tag("nav", "aria-label" to "Shiori") {
                            for ((key, href) in listOf("home" to "/", "books" to "/books", "sell" to "/sell", "orders" to "/orders", "buy" to "/buy", "sales" to "/sales", "reports" to "/reports", "settings" to "/settings")) {
                                tag("a", "href" to href, "aria-current" to if (active == key) "page" else null) { +t(r, "nav.$key") }
                            }
                        }
                    }
                    tag("form", "method" to "get", "action" to "/lang") {
                        tag("label", "for" to "lang", "class" to "help") { +t(r, "lang.label") }
                        tag("select", "id" to "lang", "name" to "l") {
                            for (l in I18n.LOCALES) tag("option", "value" to l, "selected" to (l == r.lang)) { +I18n.NAMES.getValue(l) }
                        }
                        tag("input", "type" to "hidden", "name" to "back", "value" to r.path)
                        tag("button", "type" to "submit", "class" to "secondary") { +"OK" }
                    }
                    if (r.session != null) {
                        tag("form", "method" to "post", "action" to "/logout") {
                            csrf(r)
                            tag("button", "type" to "submit", "class" to "secondary") { +t(r, "nav.logout") }
                        }
                    }
                }
                tag("main", "id" to "main") {
                    r.session?.flash?.let { (ok, msg) ->
                        tag("p", "class" to if (ok) "msg ok" else "msg error", "role" to if (ok) "status" else "alert") { +msg }
                        r.session.flash = null
                    }
                    tag("h1") { +title }
                    body()
                }
                tag("footer") { +t(r, "footer.local") }
            }
        }
        return Res(status, h.toString().toByteArray())
    }

    internal fun Html.csrf(r: Req) {
        tag("input", "type" to "hidden", "name" to "csrf", "value" to r.session!!.csrf)
    }

    internal fun Html.field(id: String, label: String, value: String = "", type: String = "text", help: String? = null, vararg extra: Pair<String, Any?>) {
        tag("div") {
            tag("label", "for" to id) {
                +label
                if (help != null) { raw(" "); tag("span", "class" to "help") { +help } }
            }
            tag("input", "id" to id, "name" to id, "type" to type, "value" to value, *extra)
        }
    }

    internal fun notFound(r: Req) = page(r, t(r, "error.notfound"), null, 404) {}

    private fun setLang(r: Req): Res {
        val l = r.query["l"]?.takeIf { it in I18n.LOCALES } ?: return redirect("/")
        val back = r.query["back"]?.takeIf { Regex("^/[A-Za-z0-9/._%-]*$").matches(it) && !it.startsWith("//") } ?: "/"
        return redirect(back, cookie("shiori_lang", l, 60 * 60 * 24 * 365))
    }

    // ---------- first run and login ----------

    private fun preToken(r: Req): Pair<String, Pair<String, String>?> {
        val existing = r.cookies["shiori_pre"]?.takeIf { it.length in 20..64 }
        if (existing != null) return existing to null
        val tok = Auth.token(24)
        return tok to cookie("shiori_pre", tok, 3600)
    }

    private fun preOk(r: Req) = r.cookies["shiori_pre"].let { it != null && it.length >= 20 && it == r.form["pre"] }

    private fun setup(r: Req): Res {
        var error: String? = null
        if (r.method == "POST") {
            val p1 = r.form["pass"] ?: ""
            error = when {
                !preOk(r) -> t(r, "error.csrf")
                p1.length < Auth.MIN_LENGTH -> t(r, "setup.short")
                p1 != r.form["pass2"] -> t(r, "setup.mismatch")
                else -> null
            }
            if (error == null) {
                synchronized(auth) { if (!auth.isSetUp) auth.setUp(p1) }
                return redirect("/login")
            }
        }
        val (tok, set) = preToken(r)
        val res = page(r, t(r, "setup.title"), null, if (error != null) 400 else 200) {
            if (error != null) tag("p", "class" to "msg error", "role" to "alert") { +error }
            tag("p") { +t(r, "setup.intro") }
            tag("form", "method" to "post", "action" to "/setup", "class" to "stack") {
                tag("input", "type" to "hidden", "name" to "pre", "value" to tok)
                field("pass", t(r, "login.pass"), type = "password", help = t(r, "setup.short"), extra = arrayOf("autocomplete" to "new-password", "required" to true, "minlength" to Auth.MIN_LENGTH))
                field("pass2", t(r, "setup.pass2"), type = "password", extra = arrayOf("autocomplete" to "new-password", "required" to true))
                tag("button", "type" to "submit") { +t(r, "setup.button") }
            }
        }
        return if (set != null) Res(res.status, res.body, res.type, res.headers + set) else res
    }

    private fun login(r: Req): Res {
        var error: String? = null
        if (r.method == "POST") {
            if (!preOk(r)) error = t(r, "error.csrf")
            else when (val result = auth.login(r.form["pass"] ?: "")) {
                is Auth.Result.Ok -> return redirect("/", cookie("shiori_session", result.sessionId, null))
                Auth.Result.Wrong -> error = t(r, "login.wrong")
                Auth.Result.Wait -> error = t(r, "login.wait")
            }
        }
        if (r.session != null && r.method == "GET") return redirect("/")
        val (tok, set) = preToken(r)
        val res = page(r, t(r, "login.title"), null, if (error != null) 401 else 200) {
            if (error != null) tag("p", "class" to "msg error", "role" to "alert") { +error }
            tag("form", "method" to "post", "action" to "/login", "class" to "stack") {
                tag("input", "type" to "hidden", "name" to "pre", "value" to tok)
                field("pass", t(r, "login.pass"), type = "password", extra = arrayOf("autocomplete" to "current-password", "required" to true, "autofocus" to true))
                tag("button", "type" to "submit") { +t(r, "login.button") }
            }
        }
        return if (set != null) Res(res.status, res.body, res.type, res.headers + set) else res
    }

    // ---------- pages ----------

    private fun home(r: Req): Res {
        val rep = Reports(shop)
        val today = LocalDate.now(shop.clock.withZone(shop.settings.zoneId))
        val d = rep.days(today, today).first()
        return page(r, t(r, "home.title") + " · " + date(r, today), "home") {
            tag("div", "class" to "cards") {
                for ((k, v) in listOf("home.sales" to d.sales.toString(), "home.revenue" to money(d.revenue), "home.copies" to rep.copiesInStock().toString(),
                    "home.value" to money(rep.stockValue()), "home.sleeping" to rep.sleeping().size.toString(),
                    "home.ready" to shop.readyOrders().size.toString(), "home.waiting" to shop.suggestions().size.toString())) {
                    tag("div", "class" to "card") { +t(r, k); tag("b") { +v } }
                }
            }
            tag("div", "class" to "spacer") {}
            searchForm(r, "")
        }
    }

    private fun Html.searchForm(r: Req, q: String) {
        tag("form", "method" to "get", "action" to "/books", "role" to "search", "class" to "row") {
            tag("div") {
                tag("label", "for" to "q") { +t(r, "home.search") }
                tag("input", "id" to "q", "name" to "q", "type" to "search", "value" to q)
            }
            tag("div") { tag("button", "type" to "submit") { +t(r, "home.search") } }
        }
    }

    private fun books(r: Req, rest: List<String>): Res {
        if (rest.isEmpty() && r.method == "GET") {
            val q = r.query["q"]?.take(200) ?: ""
            val list = shop.search(q, r.lang)
            return page(r, t(r, "books.title"), "books") {
                searchForm(r, q)
                tag("p") { tag("a", "class" to "button", "href" to "/books/new") { +t(r, "books.add") }; raw(" "); tag("a", "class" to "button secondary", "href" to "/count") { +t(r, if (count.active) "count.resume" else "count.title") }; raw(" "); tag("span", "class" to "help") { +t(r, "books.count", list.size) } }
                if (list.isEmpty()) tag("p") { +t(r, "books.none") } else tag("div", "class" to "tablewrap") {
                    tag("table") {
                        tag("thead") { tag("tr") {
                            tag("th") { +t(r, "col.title") }; tag("th") { +t(r, "col.author") }; tag("th") { +t(r, "col.section") }
                            tag("th", "class" to "num") { +t(r, "col.price") }; tag("th", "class" to "num") { +t(r, "col.stock") }
                        } }
                        tag("tbody") {
                            for (b in list) tag("tr") {
                                tag("td") { tag("a", "href" to "/books/${b.isbn}") { +b.title(r.lang) } }
                                tag("td") { +b.authors.joinToString(", ") }
                                tag("td") { +b.section }
                                tag("td", "class" to "num") { +money(b.price) }
                                tag("td", "class" to "num") { +(shop.stock[b.isbn]?.qty ?: 0).toString() }
                            }
                        }
                    }
                }
            }
        }
        if (rest == listOf("new") && r.method == "GET") return bookForm(r, null, r.query["isbn"]?.let { Isbn.normalize(it) } ?: "", null)
        if (rest == listOf("lookup") && r.method == "POST") {
            val isbn = Isbn.normalize(r.form["isbn"] ?: "")
            if (isbn == null) {
                r.session!!.flash = false to t(r, "error.isbn")
                return redirect("/books/new")
            }
            if (isbn in shop.books) return redirect("/books/$isbn")
            return when (val res = lookup.find(isbn)) {
                is Lookup.Result.Hit -> bookForm(r, null, isbn, res.found)
                Lookup.Result.Miss -> { r.session!!.flash = false to t(r, "book.lookup.none"); redirect("/books/new?isbn=$isbn") }
                Lookup.Result.Off -> { r.session!!.flash = false to t(r, "book.lookup.off"); redirect("/books/new?isbn=$isbn") }
                Lookup.Result.Failed -> { r.session!!.flash = false to t(r, "book.lookup.fail"); redirect("/books/new?isbn=$isbn") }
            }
        }
        if (rest.isEmpty() && r.method == "POST") return saveBook(r)
        val isbn = rest.firstOrNull() ?: return notFound(r)
        val book = shop.books[isbn] ?: return notFound(r)
        return when {
            rest.size == 1 && r.method == "GET" -> bookPage(r, book)
            rest.size == 2 && rest[1] == "edit" && r.method == "GET" -> bookForm(r, book, book.isbn, null)
            rest.size == 2 && rest[1] == "receive" && r.method == "POST" -> act(r, "/books/$isbn", t(r, "book.received")) {
                val c = shop.settings.market.currency
                shop.receive(isbn, r.form["qty"]?.trim()?.toLongOrNull() ?: throw ShopError("error.qty"), c.parse(r.form["cost"] ?: "") ?: throw ShopError("error.cost"), r.form["supplier"] ?: "")
            }
            rest.size == 2 && rest[1] == "adjust" && r.method == "POST" -> act(r, "/books/$isbn", t(r, "book.received")) {
                shop.adjust(isbn, r.form["qty"]?.trim()?.toLongOrNull() ?: throw ShopError("error.qty"), r.form["reason"] ?: "")
            }
            else -> notFound(r)
        }
    }

    /** Runs a change; on success shows `ok` on the next page, on a refused change shows why. */
    internal fun act(r: Req, to: String, ok: String, block: () -> Unit): Res {
        try {
            block()
            r.session!!.flash = true to ok
        } catch (e: ShopError) {
            r.session!!.flash = false to err(r, e)
        }
        return redirect(to)
    }

    private fun saveBook(r: Req): Res {
        val f = r.form
        val c = shop.settings.market.currency
        val existing = f["original"]?.let { shop.books[it] }
        return try {
            val titles = Shop.TITLE_LANGS.associateWith { (f["title_$it"] ?: "").trim() }.filterValues { it.isNotEmpty() }
            val pub = (f["published"] ?: "").trim().takeIf { it.isNotEmpty() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() ?: throw ShopError("error.price") }
            val book = shop.saveBook(Book(
                existing?.isbn ?: (f["isbn"] ?: ""), titles, (f["authors"] ?: "").lines(), (f["publisher"] ?: "").take(120),
                (f["language"] ?: "").takeIf { it in Shop.TITLE_LANGS } ?: "en", c.parse(f["price"] ?: "") ?: throw ShopError("error.price"),
                if (f["condition"] == "USED") Condition.USED else Condition.NEW, pub, (f["section"] ?: "").take(60),
            ))
            r.session!!.flash = true to t(r, "book.saved")
            redirect("/books/${book.isbn}")
        } catch (e: ShopError) {
            r.session!!.flash = false to err(r, e)
            redirect(if (existing != null) "/books/${existing.isbn}/edit" else "/books/new?isbn=${enc(f["isbn"] ?: "")}")
        }
    }

    private fun bookForm(r: Req, book: Book?, isbn: String, found: Found?): Res {
        val c = shop.settings.market.currency
        val title = if (book == null) t(r, "book.new") else t(r, "book.edit") + " · " + book.title(r.lang)
        return page(r, title, "books") {
            if (book == null) {
                tag("form", "method" to "post", "action" to "/books/lookup", "class" to "row noprint") {
                    csrf(r)
                    tag("div") {
                        tag("label", "for" to "lookup-isbn") { +t(r, "book.isbn") }
                        tag("input", "id" to "lookup-isbn", "name" to "isbn", "value" to isbn, "inputmode" to "numeric", "autofocus" to (isbn.isEmpty()))
                    }
                    tag("div") {
                        tag("button", "type" to "submit", "class" to "secondary") { +t(r, "book.lookup") }
                        tag("p", "class" to "help") { +t(r, "book.lookup.help") }
                    }
                }
            }
            tag("form", "method" to "post", "action" to "/books", "class" to "stack") {
                csrf(r)
                if (book != null) tag("input", "type" to "hidden", "name" to "original", "value" to book.isbn)
                else field("isbn", t(r, "book.isbn"), isbn, extra = arrayOf("required" to true, "inputmode" to "numeric"))
                tag("p", "class" to "help") { +t(r, "book.titles.help") }
                val defaultTitleLang = when (r.lang) { "zh-Hant" -> "zh-Hant"; else -> r.lang }
                for (l in Shop.TITLE_LANGS) {
                    val v = book?.titles?.get(l) ?: if (found != null && l == defaultTitleLang) found.title else ""
                    field("title_$l", t(r, "book.title.$l"), v, extra = arrayOf("lang" to l, "maxlength" to 300))
                }
                tag("div") {
                    tag("label", "for" to "authors") { +t(r, "book.authors") }
                    tag("textarea", "id" to "authors", "name" to "authors", "rows" to 2) { +(book?.authors ?: found?.authors ?: emptyList()).joinToString("\n") }
                }
                field("publisher", t(r, "book.publisher"), book?.publisher ?: found?.publisher ?: "")
                tag("div") {
                    tag("label", "for" to "language") { +t(r, "book.language") }
                    tag("select", "id" to "language", "name" to "language") {
                        for (l in Shop.TITLE_LANGS) tag("option", "value" to l, "selected" to ((book?.language ?: defaultTitleLang) == l)) { +t(r, "book.title.$l").substringAfter("(").substringBefore(")") }
                    }
                }
                field("price", t(r, "book.price"), book?.price?.let { if (c.digits == 0) it.toString() else "%d.%02d".format(it / 100, it % 100) } ?: "", extra = arrayOf("required" to true, "inputmode" to "decimal"))
                tag("div") {
                    tag("label", "for" to "condition") { +t(r, "book.condition") }
                    tag("select", "id" to "condition", "name" to "condition") {
                        tag("option", "value" to "NEW", "selected" to (book?.condition != Condition.USED)) { +t(r, "book.new.opt") }
                        tag("option", "value" to "USED", "selected" to (book?.condition == Condition.USED)) { +t(r, "book.used.opt") }
                    }
                }
                field("published", t(r, "book.published"), (book?.published ?: found?.published)?.toString() ?: "", type = "date")
                field("section", t(r, "book.section"), book?.section ?: "", extra = arrayOf("maxlength" to 60))
                tag("button", "type" to "submit") { +t(r, "book.save") }
            }
        }
    }

    private fun bookPage(r: Req, b: Book): Res {
        val s = shop.stock[b.isbn] ?: Stock()
        val (floor, rule) = shop.floorPrice(b)
        return page(r, b.title(r.lang), "books") {
            tag("p") {
                +listOf(b.authors.joinToString(", "), b.publisher, "ISBN ${b.isbn}").filter { it.isNotBlank() }.joinToString(" · ")
                raw(" ")
                tag("a", "href" to "/books/${b.isbn}/edit") { +t(r, "book.edit") }
            }
            tag("div", "class" to "cards") {
                tag("div", "class" to "card") { +t(r, "col.price"); tag("b") { +money(b.price) } }
                tag("div", "class" to "card") { +t(r, "col.stock"); tag("b") { +s.qty.toString() } }
                tag("div", "class" to "card") { +t(r, "col.sold"); tag("b") { +s.sold.toString() } }
            }
            tag("p") { +t(r, "book.floor", money(floor)); raw(" "); tag("span", "class" to "help") { +t(r, rule) } }
            tag("p", "class" to "help") {
                +t(r, "book.avgcost", money(s.avgCost)); raw(" · ")
                +t(r, "book.lastsale", s.lastSale?.let { dateTime(r, it) } ?: t(r, "book.never"))
            }
            tag("h2") { +t(r, "book.receive") }
            tag("form", "method" to "post", "action" to "/books/${b.isbn}/receive", "class" to "row") {
                csrf(r)
                field("qty", t(r, "book.receive.qty"), "1", "number", extra = arrayOf("min" to 1, "max" to 10000, "required" to true))
                field("cost", t(r, "book.receive.cost"), "", extra = arrayOf("required" to true, "inputmode" to "decimal"))
                field("supplier", t(r, "book.receive.supplier"), "", extra = arrayOf("maxlength" to 120))
                tag("div") { tag("button", "type" to "submit") { +t(r, "book.receive.button") } }
            }
            tag("h2") { +t(r, "book.adjust") }
            tag("form", "method" to "post", "action" to "/books/${b.isbn}/adjust", "class" to "row") {
                csrf(r)
                field("adjust-qty", t(r, "book.adjust.qty"), "", "number", extra = arrayOf("required" to true))
                tag("div") {
                    tag("label", "for" to "reason") { +t(r, "book.adjust.reason") }
                    tag("select", "id" to "reason", "name" to "reason") {
                        for (k in listOf("count", "damaged", "lost", "gift")) tag("option", "value" to k) { +t(r, "reason.$k") }
                    }
                }
                tag("div") { tag("button", "type" to "submit", "class" to "secondary") { +t(r, "book.adjust.button") } }
            }
            val ready = shop.readyOrders()
            val waiting = shop.orders.values.filter { it.isbn == b.isbn && !it.closed }
            tag("h2") { +t(r, "orders.title") }
            if (waiting.isNotEmpty()) tag("p") {
                +t(r, "book.orders", shop.reserved(b.isbn, ready)); raw(" ")
                waiting.forEachIndexed { i, o -> if (i > 0) raw(", "); tag("a", "href" to "/orders/${o.id}") { +o.id } }
            }
            tag("p") { tag("a", "class" to "button secondary", "href" to "/orders?isbn=${b.isbn}") { +t(r, "orders.new") } }
            if (shop.suppliers.isNotEmpty()) {
                tag("h2") { +t(r, "book.order.supplier") }
                tag("form", "method" to "post", "action" to "/buy/po", "class" to "row") {
                    csrf(r)
                    tag("input", "type" to "hidden", "name" to "isbn", "value" to b.isbn)
                    tag("div") {
                        tag("label", "for" to "po-qty") { +t(r, "col.qty") }
                        tag("input", "id" to "po-qty", "name" to "qty", "type" to "number", "value" to 1, "min" to 1, "max" to 10000, "required" to true)
                    }
                    tag("div") {
                        tag("label", "for" to "po-supplier") { +t(r, "buy.supplier") }
                        tag("select", "id" to "po-supplier", "name" to "supplier") {
                            for (sup in shop.suppliers.values) tag("option", "value" to sup.id, "selected" to (sup.id == s.lastSupplier)) { +sup.name }
                        }
                    }
                    tag("div") { tag("button", "type" to "submit", "class" to "secondary") { +t(r, "buy.create") } }
                }
            }
        }
    }

    private fun sell(r: Req, rest: List<String>): Res {
        val session = r.session!!
        val cart = session.cart
        if (r.method == "POST") {
            val f = r.form
            synchronized(cart) {
                when (rest.firstOrNull()) {
                    "add" -> {
                        val product = f["product"]
                        if (product != null) {
                            if (product !in shop.products) session.flash = false to t(r, "error.unknown")
                            else cart.add(CartLine(LineKind.CAFE, product, 1, null, f["takeaway"] == "1"))
                        } else {
                            val isbn = Isbn.normalize(f["isbn"] ?: "")
                            when {
                                isbn == null -> session.flash = false to t(r, "error.isbn")
                                isbn !in shop.books -> return redirect("/books/new?isbn=$isbn")
                                else -> {
                                    val i = cart.indexOfFirst { it.kind == LineKind.BOOK && it.ref == isbn && it.order == null }
                                    if (i >= 0) cart[i] = cart[i].copy(qty = cart[i].qty + 1) else cart.add(CartLine(LineKind.BOOK, isbn, 1))
                                }
                            }
                        }
                    }
                    "update" -> {
                        val i = f["line"]?.toIntOrNull()
                        if (i != null && i in cart.indices) {
                            val qty = f["qty"]?.trim()?.toLongOrNull()
                            val price = f["price"]?.takeIf { it.isNotBlank() }?.let { shop.settings.market.currency.parse(it) }
                            if (qty == null || qty !in 1..1000 || (f["price"]?.isNotBlank() == true && price == null)) session.flash = false to t(r, "error.qty")
                            else cart[i] = cart[i].copy(qty = qty, price = price)
                        }
                    }
                    "remove" -> f["line"]?.toIntOrNull()?.let { if (it in cart.indices) cart.removeAt(it) }
                    "clear" -> cart.clear()
                    "checkout" -> {
                        try {
                            val sale = shop.sell(cart.toList(), f["method"] ?: "")
                            cart.clear()
                            return redirect("/sales/${sale.id}")
                        } catch (e: ShopError) {
                            session.flash = false to err(r, e)
                        }
                    }
                    else -> return notFound(r)
                }
            }
            return redirect("/sell")
        }
        if (rest.isNotEmpty()) return notFound(r)
        val lines = synchronized(cart) { cart.toList() }
        return page(r, t(r, "sell.title"), "sell") {
            tag("form", "method" to "post", "action" to "/sell/add", "class" to "row") {
                csrf(r)
                tag("div") {
                    tag("label", "for" to "scan") { +t(r, "sell.scan") }
                    tag("input", "id" to "scan", "name" to "isbn", "inputmode" to "numeric", "autocomplete" to "off", "autofocus" to true)
                }
                tag("div") { tag("button", "type" to "submit") { +t(r, "sell.add") } }
            }
            if (shop.products.isNotEmpty()) {
                tag("h2") { +t(r, "sell.cafe") }
                tag("div", "class" to "row") {
                    for (p in shop.products.values) for (takeaway in listOf(false, true)) {
                        tag("form", "method" to "post", "action" to "/sell/add", "class" to "inline") {
                            csrf(r)
                            tag("input", "type" to "hidden", "name" to "product", "value" to p.id)
                            if (takeaway) tag("input", "type" to "hidden", "name" to "takeaway", "value" to "1")
                            tag("button", "type" to "submit", "class" to "secondary") {
                                +("${p.name(r.lang)} ${money(p.price)}" + if (takeaway) " · ${t(r, "sell.takeaway")}" else "")
                            }
                        }
                    }
                }
            }
            tag("h2") { +t(r, "nav.sell") }
            if (lines.isEmpty()) tag("p") { +t(r, "sell.empty") } else {
                tag("div", "class" to "tablewrap") { tag("table") {
                    tag("thead") { tag("tr") {
                        tag("th") { +t(r, "col.title") }; tag("th", "class" to "num") { +t(r, "col.price") }
                        tag("th") { +"${t(r, "col.qty")} · ${t(r, "sell.setprice")}" }; tag("th", "class" to "num") { +t(r, "col.total") }; tag("th") {}
                    } }
                    tag("tbody") {
                        lines.forEachIndexed { i, l ->
                            val (name, unit) = when (l.kind) {
                                LineKind.BOOK -> shop.books[l.ref]!!.let { it.title(r.lang) to it.price }
                                LineKind.CAFE -> shop.products[l.ref]!!.let { (it.name(r.lang) + if (l.takeaway) " · ${t(r, "sell.takeaway")}" else "") to it.price }
                            }
                            val price = l.price ?: unit
                            tag("tr") {
                                tag("td") { +name; if (l.order != null) { raw(" "); tag("a", "href" to "/orders/${l.order}", "class" to "help") { +t(r, "order.title", l.order) } } }
                                tag("td", "class" to "num") { +money(unit) }
                                if (l.order != null) tag("td") { +l.qty.toString() } else tag("td") {
                                    tag("form", "method" to "post", "action" to "/sell/update", "class" to "row") {
                                        csrf(r)
                                        tag("input", "type" to "hidden", "name" to "line", "value" to i)
                                        tag("div") { tag("label", "for" to "qty$i", "class" to "help") { +t(r, "col.qty") }; tag("input", "id" to "qty$i", "name" to "qty", "type" to "number", "min" to 1, "max" to 1000, "value" to l.qty) }
                                        tag("div") { tag("label", "for" to "price$i", "class" to "help") { +t(r, "sell.setprice") }; tag("input", "id" to "price$i", "name" to "price", "inputmode" to "decimal", "value" to (l.price?.let { shop.settings.market.currency.format(it) } ?: "")) }
                                        tag("div") { tag("button", "type" to "submit", "class" to "secondary") { +t(r, "sell.apply") } }
                                    }
                                }
                                tag("td", "class" to "num") { +money(price * l.qty) }
                                tag("td") {
                                    tag("form", "method" to "post", "action" to "/sell/remove") {
                                        csrf(r)
                                        tag("input", "type" to "hidden", "name" to "line", "value" to i)
                                        tag("button", "type" to "submit", "class" to "secondary") { +t(r, "sell.remove") }
                                    }
                                }
                            }
                        }
                    }
                } }
                val total = lines.sumOf { l -> (l.price ?: if (l.kind == LineKind.BOOK) shop.books[l.ref]!!.price else shop.products[l.ref]!!.price) * l.qty }
                tag("p") { tag("strong") { +"${t(r, "receipt.total")} ${money(total)}" } }
                tag("form", "method" to "post", "action" to "/sell/checkout", "class" to "row") {
                    csrf(r)
                    tag("div") {
                        tag("label", "for" to "method") { +t(r, "sell.pay") }
                        tag("select", "id" to "method", "name" to "method") { for (m in listOf("cash", "card", "other")) tag("option", "value" to m) { +t(r, "method.$m") } }
                    }
                    tag("div") { tag("button", "type" to "submit") { +t(r, "sell.done") } }
                }
                tag("form", "method" to "post", "action" to "/sell/clear") {
                    csrf(r)
                    tag("button", "type" to "submit", "class" to "secondary") { +t(r, "sell.clear") }
                }
            }
        }
    }

    private fun sales(r: Req, rest: List<String>): Res {
        if (rest.isEmpty()) return page(r, t(r, "sales.title"), "sales") {
            if (shop.sales.isEmpty()) tag("p") { +t(r, "sales.none") } else tag("div", "class" to "tablewrap") { tag("table") {
                tag("thead") { tag("tr") {
                    tag("th") { +t(r, "col.receipt") }; tag("th") { +t(r, "col.date") }; tag("th") { +t(r, "col.method") }; tag("th", "class" to "num") { +t(r, "col.total") }
                } }
                tag("tbody") {
                    for (s in shop.sales.asReversed().take(200)) tag("tr") {
                        tag("td") { tag("a", "href" to "/sales/${s.id}") { +s.id } }
                        tag("td") { +dateTime(r, s.at) }
                        tag("td") { +(t(r, "method.${s.method}") + if (s.refunded) " · ${t(r, "sales.refunded")}" else "") }
                        tag("td", "class" to "num") { +money(s.total) }
                    }
                }
            } }
        }
        val sale = shop.sale(rest[0]) ?: return notFound(r)
        if (rest.size == 2 && rest[1] == "refund" && r.method == "POST") return act(r, "/sales/${sale.id}", t(r, "sales.refunded")) { shop.refund(sale.id, r.form["reason"] ?: "") }
        if (rest.size != 1 || r.method != "GET") return notFound(r)
        return page(r, t(r, "receipt.title") + " " + sale.id, "sales") {
            receipt(r, sale)
            tag("p", "class" to "noprint") { tag("a", "class" to "button", "href" to "/sales/${sale.id}?print=1") { +t(r, "receipt.print") } }
            if (!sale.refunded) tag("form", "method" to "post", "action" to "/sales/${sale.id}/refund", "class" to "row noprint") {
                csrf(r)
                field("reason", t(r, "sales.refund.reason"), "", extra = arrayOf("maxlength" to 200))
                tag("div") { tag("button", "type" to "submit", "class" to "secondary") { +t(r, "sales.refund") } }
            }
        }
    }

    /** The receipt, as the market requires it (Japan: registration number, totals and tax by rate, reduced-rate mark). */
    private fun Html.receipt(r: Req, sale: Sale) {
        val st = shop.settings
        tag("div", "class" to "receipt", "aria-label" to t(r, "receipt.title")) {
            tag("div") { tag("strong") { +st.name } }
            if (st.address.isNotBlank()) tag("div") { +st.address }
            if (st.market.needsRegistrationNumber && st.registration.isNotBlank()) tag("div") { +t(r, "receipt.reg", st.registration) }
            tag("div") { +dateTime(r, sale.at) }
            tag("div") { +t(r, "receipt.no", sale.id) }
            if (sale.refunded) tag("div") { tag("strong") { +t(r, "sales.refunded") } }
            tag("hr")
            val reduced = st.market == Market.JP
            for (l in sale.lines) {
                val name = when (l.kind) {
                    LineKind.BOOK -> shop.books[l.ref]?.title(r.lang) ?: l.ref
                    LineKind.CAFE -> (shop.products[l.ref]?.name(r.lang) ?: l.ref) + if (l.takeaway) " (${t(r, "sell.takeaway")})" else ""
                }
                val mark = if (reduced && l.taxBp == st.market.cafeTakeawayBp && l.kind == LineKind.CAFE && l.takeaway) " ※" else ""
                tag("div", "class" to "r") { tag("span") { +"$name$mark" }; tag("span") { +money(l.total) } }
                tag("div", "class" to "help") {
                    +"${l.qty} × ${money(l.price)}"
                    if (l.price != l.unit) +(" · " + t(r, "receipt.discount", money(l.unit), money((l.unit - l.price) * l.qty)))
                }
            }
            tag("hr")
            tag("div", "class" to "r") { tag("strong") { +t(r, "receipt.total") }; tag("strong") { +money(sale.total) } }
            for ((bp, gross, tax) in sale.byRate()) {
                tag("div", "class" to "help") {
                    +if (bp == 0) t(r, "receipt.exempt", money(gross)) else t(r, "receipt.rate", rate(bp), money(gross), money(tax))
                }
            }
            if (reduced && sale.lines.any { it.kind == LineKind.CAFE && it.takeaway }) tag("div", "class" to "help") { +t(r, "receipt.reduced") }
            tag("div") { +t(r, "method.${sale.method}") }
            tag("hr")
            tag("div") { +t(r, "receipt.thanks") }
        }
    }

    internal fun rate(bp: Int): String = if (bp % 100 == 0) (bp / 100).toString() else "%d,%d".format(bp / 100, (bp % 100) / 10)

    private fun reports(r: Req): Res {
        val rep = Reports(shop)
        val to = LocalDate.now(shop.clock.withZone(shop.settings.zoneId))
        val from = to.minusDays(29)
        val days = rep.days(from, to).filter { it.sales > 0 }
        val (net, cost) = rep.bookMargin(from, to)
        return page(r, t(r, "reports.title"), "reports") {
            tag("p", "class" to "help") { +t(r, "reports.verify", shop.journal.entries.size) }
            tag("p") {
                tag("a", "href" to "/export/catalog.csv") { +t(r, "reports.export") }; raw(" · ")
                tag("a", "href" to "/reports/day") { +t(r, "closing.title") }
            }
            tag("h2") { +t(r, "reports.accountant") }
            tag("form", "method" to "get", "action" to "/export/ledger.csv", "class" to "row") {
                field("from", t(r, "reports.from"), to.withDayOfMonth(1).toString(), "date")
                field("to", t(r, "reports.to"), to.toString(), "date")
                tag("div") { tag("button", "type" to "submit", "class" to "secondary") { +t(r, "reports.ledger") } }
                tag("div") { tag("button", "type" to "submit", "class" to "secondary", "formaction" to "/export/purchases.csv") { +t(r, "reports.purchases") } }
            }
            tag("p", "class" to "help") { +t(r, "reports.accountant.help") }
            tag("p") { +t(r, "reports.margin", money(net), money(cost), money(net - cost)) }
            tag("h2") { +t(r, "reports.days") }
            tag("div", "class" to "tablewrap") { tag("table") {
                tag("thead") { tag("tr") {
                    tag("th") { +t(r, "col.date") }; tag("th", "class" to "num") { +t(r, "nav.sales") }; tag("th", "class" to "num") { +t(r, "col.sold") }
                    tag("th", "class" to "num") { +t(r, "col.total") }; tag("th", "class" to "num") { +t(r, "col.tax") }
                } }
                tag("tbody") {
                    for (d in days) tag("tr") {
                        tag("td") { +date(r, d.date) }; tag("td", "class" to "num") { +d.sales.toString() }; tag("td", "class" to "num") { +d.books.toString() }
                        tag("td", "class" to "num") { +money(d.revenue) }; tag("td", "class" to "num") { +money(d.tax) }
                    }
                }
            } }
            tag("h2") { +t(r, "reports.best") }
            tag("div", "class" to "tablewrap") { tag("table") {
                tag("thead") { tag("tr") { tag("th") { +t(r, "col.title") }; tag("th", "class" to "num") { +t(r, "col.sold") }; tag("th", "class" to "num") { +t(r, "col.total") } } }
                tag("tbody") {
                    for (b in rep.bestsellers(from, to)) tag("tr") {
                        tag("td") { tag("a", "href" to "/books/${b.isbn}") { +(shop.books[b.isbn]?.title(r.lang) ?: b.isbn) } }
                        tag("td", "class" to "num") { +b.qty.toString() }; tag("td", "class" to "num") { +money(b.revenue) }
                    }
                }
            } }
            tag("h2") { +t(r, "reports.through") }
            tag("div", "class" to "tablewrap") { tag("table") {
                tag("tbody") {
                    for ((section, v) in rep.sellThrough(from, to)) tag("tr") {
                        tag("td") { +section.ifEmpty { "—" } }; tag("td", "class" to "num") { +"%.0f %%".format(v * 100) }
                    }
                }
            } }
            tag("h2") { +t(r, "reports.sleeping") }
            tag("div", "class" to "tablewrap") { tag("table") {
                tag("thead") { tag("tr") {
                    tag("th") { +t(r, "col.title") }; tag("th", "class" to "num") { +t(r, "col.stock") }; tag("th", "class" to "num") { +t(r, "col.days") }; tag("th", "class" to "num") { +t(r, "col.value") }
                } }
                tag("tbody") {
                    for (s in rep.sleeping().take(50)) tag("tr") {
                        tag("td") { tag("a", "href" to "/books/${s.isbn}") { +(shop.books[s.isbn]?.title(r.lang) ?: s.isbn) } }
                        tag("td", "class" to "num") { +s.qty.toString() }; tag("td", "class" to "num") { +s.days.toString() }; tag("td", "class" to "num") { +money(s.value) }
                    }
                }
            } }
        }
    }

    private fun settings(r: Req, rest: List<String>): Res {
        if (r.method == "POST" && rest.isEmpty()) return act(r, "/settings", t(r, "settings.saved")) {
            val f = r.form
            shop.configure(Settings(f["name"] ?: "", Market.of(f["market"] ?: "") ?: throw ShopError("error.unknown"), (f["registration"] ?: "").trim().uppercase(), (f["address"] ?: "").take(200), (f["zone"] ?: "").trim()))
        }
        if (r.method == "POST" && rest == listOf("product")) return act(r, "/settings", t(r, "settings.saved")) {
            val f = r.form
            shop.saveProduct(Product((f["id"] ?: "").trim(), mapOf(r.lang to (f["pname"] ?: "").trim().take(80)), shop.settings.market.currency.parse(f["pprice"] ?: "") ?: throw ShopError("error.price")))
        }
        if (rest.isNotEmpty()) return notFound(r)
        val s = shop.settings
        return page(r, t(r, "settings.title"), "settings") {
            tag("h2") { +t(r, "customers.title") }
            tag("p", "class" to "help") { +t(r, "customers.help", customers.all.size) }
            tag("form", "method" to "post", "action" to "/settings/forget") {
                csrf(r)
                tag("button", "type" to "submit", "class" to "secondary") { +t(r, "customers.forget") }
            }
            tag("h2") { +t(r, "settings.shop") }
            tag("form", "method" to "post", "action" to "/settings", "class" to "stack") {
                csrf(r)
                field("name", t(r, "settings.name"), s.name, extra = arrayOf("required" to true, "maxlength" to 120))
                tag("div") {
                    tag("label", "for" to "market") { +t(r, "settings.market") }
                    tag("select", "id" to "market", "name" to "market") { for (m in Market.entries) tag("option", "value" to m.name, "selected" to (m == s.market)) { +t(r, "market.${m.name}") } }
                }
                field("registration", t(r, "settings.registration"), s.registration, extra = arrayOf("pattern" to "T[0-9]{13}"))
                field("address", t(r, "settings.address"), s.address, extra = arrayOf("maxlength" to 200))
                field("zone", t(r, "settings.zone"), s.zone, help = "Europe/Paris, Asia/Tokyo, Asia/Taipei, Asia/Seoul")
                tag("button", "type" to "submit") { +t(r, "settings.save") }
            }
            tag("h2") { +t(r, "settings.products") }
            if (shop.products.isNotEmpty()) tag("ul") { for (p in shop.products.values) tag("li") { +"${p.name(r.lang)} · ${money(p.price)}" } }
            tag("form", "method" to "post", "action" to "/settings/product", "class" to "row") {
                csrf(r)
                field("id", t(r, "settings.product.id"), "", extra = arrayOf("pattern" to "[a-z0-9-]{1,40}", "required" to true))
                field("pname", t(r, "settings.product.name"), "", extra = arrayOf("required" to true))
                field("pprice", t(r, "col.price"), "", extra = arrayOf("required" to true, "inputmode" to "decimal"))
                tag("div") { tag("button", "type" to "submit", "class" to "secondary") { +t(r, "settings.product.add") } }
            }
        }
    }
}

package shiori

import com.sun.net.httpserver.HttpServer
import shiori.Check.eq
import shiori.Check.ok
import shiori.Check.test
import java.io.File
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets.UTF_8

/** A browser stand-in: keeps cookies, sends forms, reads the CSRF token from pages. */
private class Browser(val base: String) {
    val cookies = LinkedHashMap<String, String>()
    private val http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()

    fun get(path: String, headers: Map<String, String> = emptyMap()): HttpResponse<String> = send(HttpRequest.newBuilder(URI.create(base + path)).GET(), headers)

    fun post(path: String, form: Map<String, String>, headers: Map<String, String> = emptyMap(), type: String = "application/x-www-form-urlencoded"): HttpResponse<String> {
        val body = form.entries.joinToString("&") { URLEncoder.encode(it.key, UTF_8) + "=" + URLEncoder.encode(it.value, UTF_8) }
        return send(HttpRequest.newBuilder(URI.create(base + path)).header("Content-Type", type).POST(HttpRequest.BodyPublishers.ofString(body)), headers)
    }

    private fun send(b: HttpRequest.Builder, headers: Map<String, String>): HttpResponse<String> {
        if (cookies.isNotEmpty()) b.header("Cookie", cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
        headers.forEach { (k, v) -> b.header(k, v) }
        val res = http.send(b.build(), HttpResponse.BodyHandlers.ofString())
        for (c in res.headers().allValues("Set-Cookie")) {
            val (k, v) = c.substringBefore(';').split('=', limit = 2)
            if (v.isEmpty()) cookies.remove(k) else cookies[k] = v
        }
        return res
    }

    fun token(page: String, name: String) = Regex("name=\"$name\" value=\"([^\"]+)\"").find(page)?.groupValues?.get(1) ?: error("no $name in page")
}

fun webTests() {
    val dir = Check.tempDir()
    val shop = Shop.open(dir)
    shop.configure(Settings("Test shop", Market.FR, zone = "Europe/Paris"))
    val fake = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/api/books") { ex ->
            val body = if (ex.requestURI.query.contains("9780306406157"))
                """{"ISBN:9780306406157":{"title":"Paper Gardens","authors":[{"name":"Claire Dumont"}],"publishers":[{"name":"Atelier Ginkgo"}],"publish_date":"2020"}}""" else "{}"
            ex.sendResponseHeaders(200, body.toByteArray().size.toLong()); ex.responseBody.use { it.write(body.toByteArray()) }
        }
        start()
    }
    val authClock = HandClock(java.time.Instant.now())
    val web = Web(shop, Auth(File(dir, "auth.json"), authClock, iterations = 1000), Lookup("http://127.0.0.1:${fake.address.port}")).start("127.0.0.1", 0)
    val base = "http://127.0.0.1:${web.port}"
    val pass = "correct horse battery"
    try {
        test("first run asks for a passphrase, and no page opens before") {
            val b = Browser(base)
            eq("/setup", b.get("/books").headers().firstValue("Location").orElse(""))
            val page = b.get("/setup").body()
            eq(400, b.post("/setup", mapOf("pre" to b.token(page, "pre"), "pass" to "short", "pass2" to "short")).statusCode())
            eq(303, b.post("/setup", mapOf("pre" to b.token(page, "pre"), "pass" to pass, "pass2" to pass)).statusCode())
            eq("/login", b.get("/").headers().firstValue("Location").orElse(""))
        }
        test("login: wrong passphrase refused, then a minute's wait after five tries") {
            val b = Browser(base)
            val pre = b.token(b.get("/login").body(), "pre")
            repeat(5) { eq(401, b.post("/login", mapOf("pre" to pre, "pass" to "wrong passphrase")).statusCode()) }
            val locked = b.post("/login", mapOf("pre" to pre, "pass" to pass))
            ok(locked.body().contains(Html.esc(I18n.t("fr", "login.wait"))), "locked out")
        }

        val b = Browser(base)
        fun login() {
            val pre = b.token(b.get("/login").body(), "pre")
            val r = b.post("/login", mapOf("pre" to pre, "pass" to pass))
            check(r.statusCode() == 303) { "login ${r.statusCode()}" }
        }
        test("the session cookie is HttpOnly and SameSite=Strict") {
            authClock.now = authClock.now.plusSeconds(61) // past the lockout from the test above
            val pre = b.token(b.get("/login").body(), "pre")
            val r = b.post("/login", mapOf("pre" to pre, "pass" to pass))
            val c = r.headers().allValues("Set-Cookie").first { it.startsWith("shiori_session=") }
            ok(c.contains("HttpOnly") && c.contains("SameSite=Strict"), c)
        }
        test("every page sends a strict Content-Security-Policy and no caching") {
            val r = b.get("/")
            eq(200, r.statusCode())
            ok(r.headers().firstValue("Content-Security-Policy").get().startsWith("default-src 'none'"), "CSP")
            eq("nosniff", r.headers().firstValue("X-Content-Type-Options").get())
            eq("no-store", r.headers().firstValue("Cache-Control").get())
        }
        test("a POST without the form token changes nothing") {
            val before = shop.journal.entries.size
            b.post("/settings", mapOf("name" to "Hacked", "market" to "FR", "zone" to "UTC"))
            eq(before, shop.journal.entries.size)
            eq("Test shop", shop.settings.name)
        }
        test("a POST from another site is refused, and unknown Host names too") {
            val csrf = b.token(b.get("/settings").body(), "csrf")
            eq(403, b.post("/settings", mapOf("csrf" to csrf, "name" to "Hacked", "market" to "FR", "zone" to "UTC"), mapOf("Origin" to "http://evil.example")).statusCode())
            // A DNS-rebinding page reaches the server under its own name: the Host header gives it away.
            java.net.Socket("127.0.0.1", web.port).use { sock ->
                sock.getOutputStream().write("GET /login HTTP/1.1\r\nHost: evil.example\r\nConnection: close\r\n\r\n".toByteArray())
                ok(sock.getInputStream().bufferedReader().readLine().startsWith("HTTP/1.1 421"), "421 for an unknown Host")
            }
            eq(415, b.post("/settings", mapOf("csrf" to csrf), type = "text/plain").statusCode())
        }
        test("a book is filled from Open Library (only the ISBN is sent), saved, received and sold") {
            val csrf = b.token(b.get("/books/new").body(), "csrf")
            val form = b.post("/books/lookup", mapOf("csrf" to csrf, "isbn" to "0-306-40615-2")).body()
            ok(form.contains("value=\"Paper Gardens\"") && form.contains("Atelier Ginkgo") && form.contains("2020-12-31"), "pre-filled")
            val saved = b.post("/books", mapOf("csrf" to csrf, "isbn" to "9780306406157", "title_fr" to "Les Jardins de papier", "authors" to "Claire Dumont",
                "language" to "fr", "price" to "35,00", "condition" to "NEW", "published" to "2020-12-31", "section" to "Art"))
            eq("/books/9780306406157", saved.headers().firstValue("Location").get())
            b.post("/books/9780306406157/receive", mapOf("csrf" to csrf, "qty" to "3", "cost" to "21,00", "supplier" to "Atelier Ginkgo"))
            eq(3L, shop.stock.getValue("9780306406157").qty)
            b.post("/sell/add", mapOf("csrf" to csrf, "isbn" to "978-0-306-40615-7"))
            b.post("/sell/update", mapOf("csrf" to csrf, "line" to "0", "qty" to "1", "price" to "33,00"))
            b.post("/sell/checkout", mapOf("csrf" to csrf, "method" to "card"))
            ok(b.get("/sell").body().contains("ne peut pas être vendu moins de 33,25"), "Loi Lang refusal shown")
            b.post("/sell/update", mapOf("csrf" to csrf, "line" to "0", "qty" to "1", "price" to "33,25"))
            val done = b.post("/sell/checkout", mapOf("csrf" to csrf, "method" to "card"))
            val receipt = b.get(done.headers().firstValue("Location").get()).body()
            ok(receipt.contains("TVA 5,5 %") && receipt.contains("33,25"), "receipt with French VAT")
        }
        test("book data is escaped, never run as HTML") {
            val csrf = b.token(b.get("/books/new").body(), "csrf")
            b.post("/books", mapOf("csrf" to csrf, "isbn" to "9780000000002", "title_en" to "<script>alert(1)</script>", "language" to "en", "price" to "10", "condition" to "NEW"))
            val list = b.get("/books").body()
            ok(!list.contains("<script>alert(1)</script>") && list.contains("&lt;script&gt;"), "escaped")
        }
        test("the language switch only redirects inside Shiori") {
            eq("/", b.get("/lang?l=ja&back=//evil.example").headers().firstValue("Location").get())
            eq("/books", b.get("/lang?l=ja&back=/books").headers().firstValue("Location").get())
            ok(b.get("/books").body().contains("本を登録する"), "Japanese interface")
        }
        test("logging out ends the session") {
            val csrf = b.token(b.get("/").body(), "csrf")
            b.post("/logout", mapOf("csrf" to csrf))
            eq("/login", b.get("/").headers().firstValue("Location").orElse(""))
        }
    } finally {
        web.stop()
        fake.stop(0)
        shop.close()
    }
}

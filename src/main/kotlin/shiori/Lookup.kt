package shiori

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

/** What a catalogue service knows about an ISBN, to pre-fill the form. The bookseller checks it before saving. */
data class Found(val title: String, val authors: List<String>, val publisher: String, val published: LocalDate?)

/**
 * Asks Open Library (openlibrary.org, Internet Archive) about one ISBN. Only the ISBN leaves
 * the computer; nothing about the shop, its stock or its customers. Turned off with --offline.
 */
class Lookup(private val base: String = "https://openlibrary.org", private val enabled: Boolean = true) {
    sealed interface Result {
        data class Hit(val found: Found) : Result
        data object Miss : Result
        data object Off : Result
        data object Failed : Result
    }

    private val client: HttpClient by lazy {
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NORMAL).build()
    }

    fun find(isbn13: String): Result {
        if (!enabled) return Result.Off
        require(Isbn.isValid13(isbn13))
        return try {
            val req = HttpRequest.newBuilder(URI.create("$base/api/books?bibkeys=ISBN:$isbn13&format=json&jscmd=data"))
                .timeout(Duration.ofSeconds(8)).header("User-Agent", "Shiori (open-source bookshop software)").GET().build()
            val res = client.send(req, HttpResponse.BodyHandlers.ofString())
            if (res.statusCode() != 200) return Result.Failed
            if (res.body().length > 1_000_000) return Result.Failed
            parse(res.body(), isbn13)
        } catch (e: Exception) {
            Result.Failed
        }
    }

    companion object {
        @Suppress("UNCHECKED_CAST")
        fun parse(body: String, isbn13: String): Result {
            val root = Json.parse(body) as? Map<String, Any?> ?: return Result.Failed
            val rec = root["ISBN:$isbn13"] as? Map<String, Any?> ?: return Result.Miss
            val title = listOfNotNull(rec.strOrNull("title"), rec.strOrNull("subtitle")).joinToString(" : ").take(300)
            if (title.isBlank()) return Result.Miss
            val names = { k: String -> rec.list(k).mapNotNull { (it as? Map<String, Any?>)?.strOrNull("name")?.take(120) } }
            return Result.Hit(Found(title, names("authors").take(10), names("publishers").firstOrNull() ?: "", date(rec.strOrNull("publish_date"))))
        }

        /**
         * Open Library dates come as "2005", "March 2005", "Mar 12, 2005" or "2005-03-12".
         * When only the year or month is known, Shiori takes its last day: France's discount
         * rule then never starts earlier than it should.
         */
        fun date(raw: String?): LocalDate? {
            val s = raw?.trim() ?: return null
            runCatching { return LocalDate.parse(s) }
            for (p in listOf("MMMM d, yyyy", "MMM d, yyyy", "d MMMM yyyy")) {
                runCatching { return LocalDate.parse(s, DateTimeFormatter.ofPattern(p, Locale.ENGLISH)) }
            }
            for (p in listOf("MMMM yyyy", "MMM yyyy", "yyyy-MM")) {
                runCatching { return YearMonth.parse(s, DateTimeFormatter.ofPattern(p, Locale.ENGLISH)).atEndOfMonth() }
            }
            Regex("^(\\d{4})$").find(s)?.let { return LocalDate.of(it.groupValues[1].toInt(), 12, 31) }
            return null
        }
    }
}

package shiori

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Clock
import java.time.Instant

/**
 * A stock count in progress. Each scan is saved at once in count.json, so a count of a thousand books
 * survives a closed browser or a restart; nothing reaches the journal until the count is applied.
 *
 * A barcode scanner types the digits and presses Enter, like a keyboard: the scan page needs no driver
 * and no script, only a text field that keeps the focus.
 */
class CountDraft(private val file: File, private val clock: Clock = Clock.systemUTC()) {
    var started: Instant? = null; private set
    /** The section being counted, or null for the whole shop. */
    var section: String? = null; private set
    val scans = LinkedHashMap<String, Long>()
    val unknown = ArrayList<String>()
    var last: String? = null; private set

    init {
        if (file.exists()) {
            @Suppress("UNCHECKED_CAST")
            val m = Json.parse(file.readText()) as Map<String, Any?>
            started = Instant.parse(m.str("started"))
            section = m.strOrNull("section")
            last = m.strOrNull("last")
            for ((k, v) in m.obj("scans")) scans[k] = (v as Number).toLong()
            unknown += m.list("unknown").map { it as String }
        }
    }

    val active: Boolean get() = started != null

    @Synchronized
    fun start(section: String?) {
        if (active) throw ShopError("error.counting")
        started = clock.instant()
        this.section = section?.takeIf { it.isNotBlank() }
        scans.clear(); unknown.clear(); last = null
        save()
    }

    /** One copy scanned. Returns the ISBN, or null if the code is not a book of the catalogue. */
    @Synchronized
    fun scan(code: String, shop: Shop): String? {
        if (!active) throw ShopError("error.nocount")
        val isbn = Isbn.normalize(code)
        if (isbn == null || isbn !in shop.books) {
            val c = code.trim().take(40)
            if (c.isNotEmpty() && unknown.size < 500) unknown += c
            last = null
            save()
            return null
        }
        scans.merge(isbn, 1, Long::plus)
        last = isbn
        save()
        return isbn
    }

    @Synchronized
    fun set(isbn: String, qty: Long) {
        if (!active) throw ShopError("error.nocount")
        if (qty !in 0..100_000) throw ShopError("error.qty")
        scans[isbn] = qty
        save()
    }

    @Synchronized
    fun clear() {
        started = null; section = null; scans.clear(); unknown.clear(); last = null
        file.delete()
    }

    /** Applies the count to the shop and closes it. */
    @Synchronized
    fun apply(shop: Shop): Map<String, Long> {
        if (!active) throw ShopError("error.nocount")
        val deltas = shop.applyCount(section, scans)
        clear()
        return deltas
    }

    private fun save() {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(Json.write(linkedMapOf(
            "started" to started.toString(), "section" to section, "last" to last,
            "scans" to LinkedHashMap<String, Any?>(scans), "unknown" to unknown,
        )) + "\n")
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}

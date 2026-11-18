package shiori

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Clock
import java.time.Instant

/**
 * Names and phone numbers of customers who ordered a book.
 *
 * They are kept out of the journal on purpose: the journal can never change, and a customer may ask
 * the shop to forget them (GDPR in France, APPI in Japan, PDPA in Taiwan, PIPA in Korea). The journal
 * holds only a customer number such as C00012; this file maps the number to the person, and forgetting
 * someone removes their line here while the sales and orders stay intact.
 */
class Customers(private val file: File, private val clock: Clock = Clock.systemUTC()) {
    data class Customer(val id: String, val name: String, val contact: String, val note: String, val created: Instant)

    private val byId = LinkedHashMap<String, Customer>()
    private var next = 1

    init {
        if (file.exists()) {
            @Suppress("UNCHECKED_CAST")
            val m = Json.parse(file.readText()) as Map<String, Any?>
            next = m.int("next")
            for ((id, v) in m.obj("customers")) {
                @Suppress("UNCHECKED_CAST")
                val c = v as Map<String, Any?>
                byId[id] = Customer(id, c.str("name"), c.str("contact"), c.strOrNull("note") ?: "", Instant.parse(c.str("created")))
            }
        }
    }

    val all: Collection<Customer> get() = byId.values

    @Synchronized
    fun add(name: String, contact: String, note: String): Customer {
        val n = name.trim()
        val c = contact.trim()
        if (n.isEmpty() || n.length > 80) throw ShopError("error.customer")
        if (c.length > 120 || note.length > 300) throw ShopError("error.customer")
        val id = "C%05d".format(next++)
        byId[id] = Customer(id, n, c, note.trim(), clock.instant())
        save()
        return byId.getValue(id)
    }

    fun get(id: String): Customer? = byId[id]

    /** Forgets a customer: their name, contact and note are gone; the journal keeps only the number. */
    @Synchronized
    fun forget(id: String): Boolean {
        if (byId.remove(id) == null) return false
        save()
        return true
    }

    /** Forgets everyone who has no open order, as the shop should once a book has been collected or cancelled. */
    @Synchronized
    fun forgetAllExcept(keep: Set<String>): Int {
        val gone = byId.keys.filter { it !in keep }
        gone.forEach { byId.remove(it) }
        if (gone.isNotEmpty()) save()
        return gone.size
    }

    private fun save() {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(Json.write(linkedMapOf(
            "next" to next.toLong(),
            "customers" to byId.mapValues { (_, c) -> linkedMapOf("name" to c.name, "contact" to c.contact, "note" to c.note, "created" to c.created.toString()) },
        )) + "\n")
        tmp.setReadable(false, false); tmp.setReadable(true, true); tmp.setWritable(false, false); tmp.setWritable(true, true)
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}

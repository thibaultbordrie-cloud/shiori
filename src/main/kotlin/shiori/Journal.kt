package shiori

import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant

/** One fact the shop recorded: never edited, never deleted. */
data class Entry(val seq: Long, val at: Instant, val type: String, val data: Map<String, Any?>, val prev: String, val hash: String)

class JournalError(msg: String) : RuntimeException(msg)

/**
 * The shop's journal: one JSON line per fact, appended and flushed to disk before the
 * fact counts. Each line carries the SHA-256 of the previous line, so a change, a
 * deletion or a reordering anywhere breaks the chain and `shiori verify` reports where.
 *
 * Only one Shiori may write a journal at a time (a lock file enforces it).
 */
class Journal private constructor(val file: File, private val clock: Clock, private val lock: FileLock?, private val raf: RandomAccessFile?) : AutoCloseable {
    private val list = ArrayList<Entry>()
    val entries: List<Entry> get() = list

    companion object {
        const val GENESIS = "0000000000000000000000000000000000000000000000000000000000000000"

        fun open(file: File, clock: Clock = Clock.systemUTC()): Journal {
            file.parentFile?.mkdirs()
            val lockRaf = RandomAccessFile(File(file.path + ".lock"), "rw")
            val lock = try {
                lockRaf.channel.tryLock()
            } catch (e: Exception) {
                null
            } ?: run {
                lockRaf.close()
                throw JournalError("another Shiori is already using ${file.path}")
            }
            val j = Journal(file, clock, lock, lockRaf)
            if (file.exists()) {
                val problems = verifyLines(file.readLines(UTF_8)) { j.list.add(it) }
                if (problems.isNotEmpty()) {
                    j.close()
                    throw JournalError("the journal is damaged: ${problems.first()} (run `shiori verify` for the full report)")
                }
            }
            return j
        }

        /** Reads a journal without locking it, for `shiori verify`. Returns the problems found. */
        fun verify(file: File): List<String> = verifyLines(file.readLines(UTF_8)) {}

        fun hashOf(prev: String, seq: Long, at: Instant, type: String, data: Map<String, Any?>): String {
            val body = Json.write(linkedMapOf("seq" to seq, "at" to at.toString(), "type" to type, "data" to data))
            val md = MessageDigest.getInstance("SHA-256")
            md.update(prev.toByteArray(UTF_8))
            md.update('\n'.code.toByte())
            md.update(body.toByteArray(UTF_8))
            return md.digest().joinToString("") { "%02x".format(it) }
        }

        private fun verifyLines(lines: List<String>, onEntry: (Entry) -> Unit): List<String> {
            val problems = ArrayList<String>()
            var prev = GENESIS
            var expected = 1L
            lines.forEachIndexed { idx, line ->
                if (line.isBlank()) {
                    if (idx != lines.lastIndex) problems.add("line ${idx + 1}: empty line")
                    return@forEachIndexed
                }
                val e = try {
                    @Suppress("UNCHECKED_CAST")
                    val m = Json.parse(line) as? Map<String, Any?> ?: throw Json.JsonError("not an object")
                    Entry(m.long("seq"), Instant.parse(m.str("at")), m.str("type"), m.obj("data"), m.str("prev"), m.str("hash"))
                } catch (ex: Exception) {
                    problems.add("line ${idx + 1}: unreadable (${ex.message})")
                    return problems
                }
                if (e.seq != expected) problems.add("line ${idx + 1}: number ${e.seq}, expected $expected")
                if (e.prev != prev) problems.add("line ${idx + 1}: does not follow the line before it")
                if (hashOf(e.prev, e.seq, e.at, e.type, e.data) != e.hash) problems.add("line ${idx + 1}: content changed after it was written")
                if (problems.isNotEmpty()) return problems
                onEntry(e)
                prev = e.hash
                expected++
            }
            return problems
        }
    }

    @Synchronized
    fun append(type: String, data: Map<String, Any?>): Entry {
        val prev = list.lastOrNull()?.hash ?: GENESIS
        val seq = list.size + 1L
        val at = clock.instant()
        val hash = hashOf(prev, seq, at, type, data)
        val e = Entry(seq, at, type, data, prev, hash)
        val line = Json.write(linkedMapOf("seq" to seq, "at" to at.toString(), "type" to type, "data" to data, "prev" to prev, "hash" to hash)) + "\n"
        java.io.FileOutputStream(file, true).use { out ->
            out.write(line.toByteArray(UTF_8))
            out.fd.sync()
        }
        list.add(e)
        return e
    }

    override fun close() {
        try { lock?.release() } catch (_: Exception) {}
        try { raf?.close() } catch (_: Exception) {}
    }
}

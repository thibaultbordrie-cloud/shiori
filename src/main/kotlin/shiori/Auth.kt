package shiori

import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * One passphrase opens the shop. It is stored as a PBKDF2-SHA-256 hash (600,000 iterations,
 * a random salt), never in clear. Sessions live in memory only: restarting Shiori closes them.
 */
class Auth(private val file: File, private val clock: Clock = Clock.systemUTC(), private val iterations: Int = ITERATIONS) {
    companion object {
        const val MIN_LENGTH = 10
        const val ITERATIONS = 600_000
        val IDLE: Duration = Duration.ofHours(12)
        private val rng = SecureRandom()
        fun token(bytes: Int = 32): String {
            val b = ByteArray(bytes)
            rng.nextBytes(b)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(b)
        }

        fun derive(pass: String, salt: ByteArray, iterations: Int): ByteArray =
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(pass.toCharArray(), salt, iterations, 256)).encoded
    }

    class Session(val csrf: String, var seen: Instant, val cart: MutableList<CartLine> = ArrayList()) {
        /** One message shown on the next page (ok or error), then forgotten. */
        @Volatile var flash: Pair<Boolean, String>? = null
    }

    private val sessions = ConcurrentHashMap<String, Session>()
    private var failures = 0
    private var lockedUntil: Instant = Instant.EPOCH

    val isSetUp: Boolean get() = file.exists()

    @Synchronized
    fun setUp(pass: String) {
        check(!isSetUp) { "already set up" }
        require(pass.length >= MIN_LENGTH)
        writeHash(pass)
    }

    /** Replaces the passphrase (from the command line, for someone at the computer) and closes every session. */
    @Synchronized
    fun reset(pass: String) {
        require(pass.length >= MIN_LENGTH)
        writeHash(pass)
        sessions.clear()
    }

    private fun writeHash(pass: String) {
        val salt = ByteArray(16).also { rng.nextBytes(it) }
        val hash = derive(pass, salt, iterations)
        val enc = Base64.getEncoder()
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(Json.write(linkedMapOf("kdf" to "PBKDF2-SHA-256", "iterations" to iterations, "salt" to enc.encodeToString(salt), "hash" to enc.encodeToString(hash))) + "\n")
        tmp.setReadable(false, false); tmp.setReadable(true, true); tmp.setWritable(false, false); tmp.setWritable(true, true)
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    sealed interface Result {
        data class Ok(val sessionId: String) : Result
        data object Wrong : Result
        data object Wait : Result
    }

    @Synchronized
    fun login(pass: String): Result {
        val now = clock.instant()
        if (now.isBefore(lockedUntil)) return Result.Wait
        @Suppress("UNCHECKED_CAST")
        val m = Json.parse(file.readText()) as Map<String, Any?>
        val dec = Base64.getDecoder()
        if (m.int("iterations") !in 1_000..10_000_000) return Result.Wrong
        val ok = MessageDigest.isEqual(derive(pass, dec.decode(m.str("salt")), m.int("iterations")), dec.decode(m.str("hash")))
        if (!ok) {
            failures++
            if (failures >= 5) {
                lockedUntil = now.plusSeconds(60)
                failures = 0
            }
            return Result.Wrong
        }
        failures = 0
        val id = token()
        sessions[id] = Session(token(), now)
        return Result.Ok(id)
    }

    fun session(id: String?): Session? {
        if (id == null) return null
        val s = sessions[id] ?: return null
        val now = clock.instant()
        if (Duration.between(s.seen, now) > IDLE) {
            sessions.remove(id)
            return null
        }
        s.seen = now
        return s
    }

    fun logout(id: String?) {
        if (id != null) sessions.remove(id)
    }
}

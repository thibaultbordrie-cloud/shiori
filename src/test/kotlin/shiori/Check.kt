package shiori

import java.io.File
import java.nio.file.Files
import kotlin.system.exitProcess

/** A small test runner, so the tests need nothing but the JDK and the Kotlin standard library. */
object Check {
    private var passed = 0
    private val failed = ArrayList<String>()
    private var current = ""

    fun test(name: String, body: () -> Unit) {
        current = name
        try {
            body()
            passed++
        } catch (e: Throwable) {
            failed += "$name: ${e::class.simpleName}: ${e.message}"
        }
    }

    fun eq(expected: Any?, actual: Any?, what: String = "") {
        if (expected != actual) throw AssertionError("${if (what.isNotEmpty()) "$what: " else ""}expected <$expected> but was <$actual>")
    }

    fun ok(cond: Boolean, what: String) {
        if (!cond) throw AssertionError(what)
    }

    inline fun <reified T : Throwable> fails(body: () -> Unit): T {
        try {
            body()
        } catch (e: Throwable) {
            if (e is T) return e
            throw AssertionError("expected ${T::class.simpleName} but got ${e::class.simpleName}: ${e.message}")
        }
        throw AssertionError("expected ${T::class.simpleName}, nothing was thrown")
    }

    fun tempDir(): File = Files.createTempDirectory("shiori-test").toFile().also { it.deleteOnExit() }

    fun report(): Int {
        failed.forEach { System.err.println("FAIL $it") }
        println("${passed + failed.size} tests, ${failed.size} failed")
        return if (failed.isEmpty()) 0 else 1
    }
}

fun main() {
    domainTests()
    journalTests()
    shopTests()
    textTests()
    webTests()
    exitProcess(Check.report())
}

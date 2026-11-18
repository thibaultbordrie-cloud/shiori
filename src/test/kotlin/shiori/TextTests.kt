package shiori

import shiori.Check.eq
import shiori.Check.ok
import shiori.Check.test
import java.io.File

fun textTests() {
    test("search folds katakana into hiragana, full-width into half-width, and drops accents") {
        eq(Text.fold("しおり"), Text.fold("シオリ"))
        eq(Text.fold("ＡＢＣ１２３"), Text.fold("abc123"))
        eq(Text.fold("Lefèvre"), Text.fold("lefevre"))
        ok(Text.matches("cafe des marees", "Le Café des marées"), "accents and spaces")
        ok(Text.matches("書架", "書架の剣士 1"), "kanji")
        ok(Text.matches("바다", "바다가 보이는 서점"), "hangul")
    }
    test("voicing marks still count: が is not か") {
        ok(Text.fold("が") != Text.fold("か"), "dakuten kept")
        ok(Text.fold("パン") != Text.fold("ハン"), "handakuten kept")
    }
    test("every interface text exists in all four languages, with the same placeholders") {
        val keys = I18n.TEXT.getValue("en").keys
        for (l in I18n.LOCALES) {
            eq(keys, I18n.TEXT.getValue(l).keys, "keys in $l")
            for (k in keys) {
                val ph = { s: String -> Regex("\\{\\d}").findAll(s).map { it.value }.toSet() }
                eq(ph(I18n.TEXT.getValue("en").getValue(k)), ph(I18n.TEXT.getValue(l).getValue(k)), "placeholders of $k in $l")
            }
        }
    }
    test("every text key the code uses is defined") {
        val src = File("src/main/kotlin/shiori").listFiles()!!.filter { it.extension == "kt" }.joinToString("\n") { it.readText() }
        val used = Regex("\"((?:error|nav|home|books|col|book|rule|sell|sales|receipt|reports|settings|setup|login|reason|method|market|order|orders|buy|supplier|po|returns|return|count|closing|customers|state)\\.[a-zA-Z.-]+)\"").findAll(src).map { it.groupValues[1] }.toSet()
        // Journal entry types and file names share the dotted shape but are not interface texts.
        val notText = setOf("order.notified", "order.cancel", "po.receive", "po.cancel")
        val missing = used.filter { it !in I18n.TEXT.getValue("en") && !it.endsWith(".") && !it.endsWith(".json") && it !in notText }
        eq(emptyList<String>(), missing)
    }
    test("no tracker, CDN or remote script anywhere in the interface") {
        val src = File("src/main/kotlin/shiori").listFiles()!!.filter { it.extension == "kt" }.joinToString("\n") { it.readText() }
        ok(!Regex("<script|https?://(?!openlibrary\\.org)[a-z]+\\.[a-z]").containsMatchIn(src.replace("http://127.0.0.1", "").replace("https://\$", "")), "remote resource found")
    }
    test("no key, token or personal address anywhere in the repository") {
        // Built from pieces so that this file does not match itself.
        val secrets = Regex(listOf("xkey" + "sib-", "sk-" + "ant-", "ghp_[A-Za-z0-9]{20}", "github" + "_pat_", "AKIA[0-9A-Z]{16}", "-----BEGIN [A-Z ]*PRIVATE KEY").joinToString("|"))
        val email = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
        val files = File(".").walkTopDown().onEnter { it.name !in setOf(".git", "build", "shiori-data") }
            .filter { it.isFile && it.length() < 2_000_000 && it.extension in setOf("kt", "md", "yml", "sh", "txt", "csv", "json", "") }.toList()
        ok(files.size > 10, "files scanned")
        for (f in files) {
            val text = f.readText()
            ok(!secrets.containsMatchIn(text), "secret-looking text in ${f.path}")
            val mails = email.findAll(text).map { it.value }.filter { !it.endsWith("@anthropic.com") && !it.endsWith("example.com") && !it.endsWith("users.noreply.github.com") }.toList()
            eq(emptyList<String>(), mails, "addresses in ${f.path}")
        }
    }
}

package shiori

import java.text.Normalizer

/** Search that works the way readers type in four languages. */
object Text {
    /**
     * Full-width and half-width forms become one (NFKC), case and accents are ignored,
     * katakana match hiragana (シオリ = しおり), and spaces, middle dots and punctuation do not count.
     */
    fun fold(s: String): String {
        val n = Normalizer.normalize(s, Normalizer.Form.NFKC).lowercase()
        val noAccents = Normalizer.normalize(n, Normalizer.Form.NFD).replace(Regex("[\\u0300-\\u036f]"), "")
        // Only Latin accents go (é = e); kana voicing marks stay (が is not か). Recompose so Hangul stays whole.
        val composed = Normalizer.normalize(noAccents, Normalizer.Form.NFC)
        val sb = StringBuilder()
        for (c in composed) {
            when {
                c in 'ァ'..'ヶ' -> sb.append(c - 0x60)
                c.isLetterOrDigit() -> sb.append(c)
                else -> {}
            }
        }
        return sb.toString()
    }

    fun matches(query: String, vararg fields: String?): Boolean {
        val q = fold(query)
        if (q.isEmpty()) return true
        return fields.any { it != null && fold(it).contains(q) }
    }
}

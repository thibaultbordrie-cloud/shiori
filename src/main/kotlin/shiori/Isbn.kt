package shiori

/**
 * ISBN-10 and ISBN-13 (ISO 2108). Shiori stores every book under its ISBN-13,
 * without hyphens; it accepts either form, with or without hyphens or spaces,
 * and full-width digits typed on a Japanese or Chinese keyboard.
 */
object Isbn {
    /** Keeps digits (full-width ones become ASCII) and a final X; drops hyphens and spaces. */
    fun clean(raw: String): String {
        val sb = StringBuilder()
        for (c in raw.trim()) {
            when {
                c in '0'..'9' -> sb.append(c)
                c in '０'..'９' -> sb.append('0' + (c - '０'))
                c == 'x' || c == 'X' || c == 'Ｘ' || c == 'ｘ' -> sb.append('X')
                c == '-' || c == ' ' || c == '‐' || c == '－' || c == '　' -> {}
                else -> return ""
            }
        }
        return sb.toString()
    }

    fun check13(first12: String): Char {
        require(first12.length == 12 && first12.all { it.isDigit() })
        val sum = first12.mapIndexed { i, c -> (c - '0') * if (i % 2 == 0) 1 else 3 }.sum()
        return '0' + (10 - sum % 10) % 10
    }

    fun check10(first9: String): Char {
        require(first9.length == 9 && first9.all { it.isDigit() })
        val sum = first9.mapIndexed { i, c -> (c - '0') * (10 - i) }.sum()
        val r = (11 - sum % 11) % 11
        return if (r == 10) 'X' else '0' + r
    }

    fun isValid13(s: String) =
        s.length == 13 && s.all { it.isDigit() } && (s.startsWith("978") || s.startsWith("979")) && check13(s.take(12)) == s[12]

    fun isValid10(s: String) =
        s.length == 10 && s.take(9).all { it.isDigit() } && (s[9].isDigit() || s[9] == 'X') && check10(s.take(9)) == s[9]

    /** The ISBN-13 for any valid input, or null. */
    fun normalize(raw: String): String? {
        val s = clean(raw)
        return when {
            isValid13(s) -> s
            isValid10(s) -> to13(s)
            else -> null
        }
    }

    fun to13(isbn10: String): String {
        val body = "978" + isbn10.take(9)
        return body + check13(body)
    }

    /** ISBN-10 exists only for the 978 prefix. */
    fun to10(isbn13: String): String? {
        if (!isValid13(isbn13) || !isbn13.startsWith("978")) return null
        val body = isbn13.substring(3, 12)
        return body + check10(body)
    }

    /** 978-4…: books registered in Japan; 978-957/986/626: Taiwan; 978-89/979-11: Korea; 978-2/979-10: France. */
    fun registrationArea(isbn13: String): String? = when {
        isbn13.startsWith("9784") -> "JP"
        isbn13.startsWith("978957") || isbn13.startsWith("978986") || isbn13.startsWith("978626") -> "TW"
        isbn13.startsWith("97889") || isbn13.startsWith("97911") -> "KR"
        isbn13.startsWith("9782") || isbn13.startsWith("97910") -> "FR"
        else -> null
    }
}

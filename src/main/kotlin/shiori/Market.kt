package shiori

/**
 * Money is kept as a whole number of the currency's smallest unit used in shops:
 * cents for the euro, and whole yen, dollars and won (coins below 1 NT$ or 1 ¥ are not used).
 */
enum class Currency(val code: String, val digits: Int, val symbol: String, val symbolAfter: Boolean) {
    EUR("EUR", 2, "€", true),
    JPY("JPY", 0, "¥", false),
    TWD("TWD", 0, "NT$", false),
    KRW("KRW", 0, "₩", false);

    /** Reads "12,50", "12.50", "¥2,980", "NT$590" or "2980" into minor units, or null. */
    fun parse(raw: String): Long? {
        var t = raw.trim().replace(symbol, "").replace("￥", "").replace("円", "").replace("元", "").replace("원", "")
            .replace(" ", "").replace(" ", "").replace("　", "")
        if (t.isEmpty()) return null
        if (t.startsWith("-")) return null
        if (digits == 0) {
            t = t.replace(",", "")
            return t.toLongOrNull()?.takeIf { it in 0..1_000_000_000L }
        }
        t = t.replace(',', '.')
        val parts = t.split('.')
        if (parts.size > 2 || parts[0].isEmpty() || !parts[0].all { it.isDigit() }) return null
        val frac = parts.getOrElse(1) { "" }
        if (frac.length > digits || !frac.all { it.isDigit() }) return null
        val whole = parts[0].toLongOrNull() ?: return null
        if (whole > 10_000_000L) return null
        return whole * 100 + frac.padEnd(2, '0').toLong()
    }

    fun format(minor: Long): String {
        val neg = minor < 0
        val a = Math.abs(minor)
        val body = if (digits == 0) group(a.toString(), ',') else {
            val whole = group((a / 100).toString(), if (this == EUR) ' ' else ',')
            "$whole,${"%02d".format(a % 100)}"
        }
        val s = if (symbolAfter) "$body $symbol" else "$symbol$body"
        return if (neg) "-$s" else s
    }

    private fun group(digitsText: String, sep: Char): String {
        val sb = StringBuilder()
        digitsText.forEachIndexed { i, c ->
            if (i > 0 && (digitsText.length - i) % 3 == 0) sb.append(sep)
            sb.append(c)
        }
        return sb.toString()
    }
}

/**
 * The rules a bookshop must follow in each market. Rates are in basis points (550 = 5.5 %).
 * Shelf prices are always tax-inclusive, as customers see them in all four countries.
 *
 * Sources and limits are in docs/RULES.md. These are defaults a shop can check against its
 * own adviser; Shiori is not legal or tax advice.
 */
enum class Market(
    val currency: Currency,
    /** Tax on books, in basis points. Taiwan exempts books (ISBN) from business tax since March 2021; Korea exempts books from VAT. */
    val bookTaxBp: Int,
    /** Largest discount on a new book's printed price, or null when there is no fixed book price. */
    val newBookMaxDiscountBp: Int?,
    /** Largest total benefit (discount plus points), when the law caps it separately. */
    val newBookMaxBenefitBp: Int?,
    /** Café: rate when eaten or drunk in the shop, and when taken away. */
    val cafeEatInBp: Int,
    val cafeTakeawayBp: Int,
    /** Japan's receipts must carry the shop's invoice registration number (T + 13 digits). */
    val needsRegistrationNumber: Boolean,
    val defaultLocale: String,
) {
    /** Loi Lang (1981): at most 5 % off the publisher's price. TVA 5.5 % on books, 10 % for café drinks. */
    FR(Currency.EUR, 550, 500, null, 1000, 1000, false, "fr"),

    /** 再販売価格維持制度: new books sell at the publisher's price; consumption tax 10 %, 8 % for take-away food and drink. */
    JP(Currency.JPY, 1000, 0, null, 1000, 800, true, "ja"),

    /** No fixed book price law in force; books exempt from the 5 % business tax; café 5 %. */
    TW(Currency.TWD, 0, null, null, 500, 500, false, "zh-Hant"),

    /** 도서정가제: at most 10 % off, and 15 % with points; books exempt from VAT; café 10 %. */
    KR(Currency.KRW, 0, 1000, 1500, 1000, 1000, false, "en");

    companion object {
        fun of(code: String): Market? = entries.firstOrNull { it.name.equals(code.trim(), ignoreCase = true) }
    }
}

/** Tax included in a tax-inclusive amount, rounded half up to the smallest unit. */
fun taxIncluded(gross: Long, rateBp: Int): Long {
    if (rateBp == 0) return 0
    // gross * rate / (10000 + rate), rounded half up, in integers.
    val num = gross * rateBp * 2 + (10_000 + rateBp)
    return num / (2L * (10_000 + rateBp))
}

/** Largest discount, in money, that a cap in basis points allows: rounded down, so the cap is never passed. */
fun maxDiscount(price: Long, capBp: Int): Long = price * capBp / 10_000

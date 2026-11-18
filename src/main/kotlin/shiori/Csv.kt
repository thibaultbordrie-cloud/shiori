package shiori

import java.time.LocalDate

/**
 * Catalogue import and export as CSV (RFC 4180, UTF-8), the format distributors and spreadsheets share.
 * Columns: isbn, title, title_lang, authors (separated by ";"), publisher, language, price, condition, published, section, qty, cost.
 */
object Csv {
    val HEADER = listOf("isbn", "title", "title_lang", "authors", "publisher", "language", "price", "condition", "published", "section", "qty", "cost")

    fun parse(text: String): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val cell = StringBuilder()
        var i = 0
        var quoted = false
        val s = text.removePrefix("﻿")
        while (i < s.length) {
            val c = s[i]
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < s.length && s[i + 1] == '"') { cell.append('"'); i++ } else quoted = false
                } else cell.append(c)
            } else when (c) {
                '"' -> quoted = true
                ',' -> { row.add(cell.toString()); cell.clear() }
                '\r' -> {}
                '\n' -> { row.add(cell.toString()); cell.clear(); rows.add(row); row = ArrayList() }
                else -> cell.append(c)
            }
            i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) { row.add(cell.toString()); rows.add(row) }
        return rows.filter { r -> r.any { it.isNotBlank() } }
    }

    /** Spreadsheet programs run cells that start with = + - @; a leading apostrophe keeps them as text. */
    fun cell(v: String): String {
        val safe = if (v.isNotEmpty() && v[0] in "=+-@\t\r") "'$v" else v
        return if (safe.any { it in ",\"\n\r" }) "\"" + safe.replace("\"", "\"\"") + "\"" else safe
    }

    fun export(shop: Shop, lang: String): String {
        val m = shop.settings.market.currency
        val sb = StringBuilder("﻿")
        sb.append(HEADER.joinToString(",")).append("\r\n")
        for (b in shop.books.values) {
            val s = shop.stock[b.isbn]
            val titleLang = if (lang in b.titles) lang else b.titles.keys.first()
            val cells = listOf(
                b.isbn, b.titles.getValue(titleLang), titleLang, b.authors.joinToString("; "), b.publisher, b.language,
                plain(b.price, m), b.condition.name.lowercase(), b.published?.toString() ?: "", b.section,
                (s?.qty ?: 0).toString(), plain(s?.avgCost ?: 0, m),
            )
            sb.append(cells.joinToString(",") { cell(it) }).append("\r\n")
        }
        return sb.toString()
    }

    private fun plain(minor: Long, c: Currency) = if (c.digits == 0) minor.toString() else "%d.%02d".format(minor / 100, minor % 100)

    data class Report(val added: Int, val received: Long, val errors: List<String>)

    /** Imports rows; a bad row is reported with its line number and does not stop the others. */
    fun import(shop: Shop, text: String, supplier: String): Report {
        val rows = parse(text)
        if (rows.isEmpty()) return Report(0, 0, listOf("empty file"))
        val head = rows[0].map { it.trim().lowercase() }
        val idx = HEADER.associateWith { head.indexOf(it) }
        if (idx.getValue("isbn") < 0 || idx.getValue("title") < 0 || idx.getValue("price") < 0) return Report(0, 0, listOf("line 1: needs at least isbn, title and price columns"))
        val cur = shop.settings.market.currency
        var added = 0
        var received = 0L
        val errors = ArrayList<String>()
        rows.drop(1).forEachIndexed { n, r ->
            val line = n + 2
            fun col(k: String) = idx.getValue(k).let { if (it >= 0) r.getOrElse(it) { "" }.trim() else "" }
            try {
                val lang = col("title_lang").ifEmpty { col("language") }.ifEmpty { "en" }
                val book = shop.saveBook(Book(
                    col("isbn"), mapOf((if (lang in Shop.TITLE_LANGS) lang else "en") to col("title")),
                    col("authors").split(';').map { it.trim() }.filter { it.isNotEmpty() }, col("publisher"), col("language").ifEmpty { lang },
                    cur.parse(col("price")) ?: throw ShopError("error.price"),
                    if (col("condition").equals("used", true)) Condition.USED else Condition.NEW,
                    col("published").takeIf { it.isNotEmpty() }?.let { LocalDate.parse(it) }, col("section"),
                ))
                added++
                val qty = col("qty").toLongOrNull() ?: 0
                if (qty > 0) {
                    shop.receive(book.isbn, qty, cur.parse(col("cost")) ?: 0, supplier)
                    received += qty
                }
            } catch (e: ShopError) {
                errors.add("line $line: ${I18n.t("en", e.key, *e.args)}")
            } catch (e: Exception) {
                errors.add("line $line: ${e.message}")
            }
        }
        return Report(added, received, errors)
    }
}

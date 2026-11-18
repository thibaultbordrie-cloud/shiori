package shiori

import shiori.Check.eq
import shiori.Check.ok
import shiori.Check.test

fun domainTests() {
    test("ISBN-13 check digit: the ISO 2108 example 978-0-306-40615-7") {
        eq('7', Isbn.check13("978030640615"))
        ok(Isbn.isValid13("9780306406157"), "valid")
        ok(!Isbn.isValid13("9780306406158"), "wrong check digit")
    }
    test("ISBN-10 with an X check digit, and conversion both ways") {
        ok(Isbn.isValid10("080442957X"), "X is 10")
        eq("9780306406157", Isbn.normalize("0-306-40615-2"))
        eq("0306406152", Isbn.to10("9780306406157"))
        eq(null, Isbn.to10("9791032305690"), "979 books have no ISBN-10")
    }
    test("ISBN typed with hyphens, spaces or full-width digits") {
        eq("9780306406157", Isbn.normalize("978-0-306-40615-7"))
        eq("9780306406157", Isbn.normalize(" ９７８０３０６４０６１５７ "))
        eq(null, Isbn.normalize("978-0-306-40615-8"))
        eq(null, Isbn.normalize("hello"))
        eq(null, Isbn.normalize("1234567890123"), "must start with 978 or 979")
    }
    test("ISBN registration areas of the four markets") {
        eq("JP", Isbn.registrationArea("9784101010014"))
        eq("TW", Isbn.registrationArea("9789571234567"))
        eq("TW", Isbn.registrationArea("9786260000000"))
        eq("KR", Isbn.registrationArea("9791100000000"))
        eq("FR", Isbn.registrationArea("9782070360024"))
    }
    test("money is read as people type it, in each currency") {
        eq(2190L, Currency.EUR.parse("21,90"))
        eq(2190L, Currency.EUR.parse("21.9"))
        eq(2190L, Currency.EUR.parse("21,90 €"))
        eq(null, Currency.EUR.parse("21,905"))
        eq(null, Currency.EUR.parse("-3"))
        eq(2980L, Currency.JPY.parse("¥2,980"))
        eq(2980L, Currency.JPY.parse("2980円"))
        eq(590L, Currency.TWD.parse("NT$590"))
        eq(15000L, Currency.KRW.parse("₩15,000"))
        eq(null, Currency.JPY.parse(""))
    }
    test("money is shown as each country writes it, and reads back") {
        eq("21,90 €", Currency.EUR.format(2190))
        eq("1 234,50 €", Currency.EUR.format(123450))
        eq("¥2,980", Currency.JPY.format(2980))
        eq("NT$590", Currency.TWD.format(590))
        eq("₩15,000", Currency.KRW.format(15000))
        for (c in Currency.entries) for (v in listOf(0L, 7L, 1234L, 99999L)) eq(v, c.parse(c.format(v)), "${c.code} $v")
    }
    test("tax included in a tax-inclusive price") {
        eq(100L, taxIncluded(1100, 1000))
        eq(114L, taxIncluded(2190, 550), "5.5 % of 21,90 € TTC")
        eq(40L, taxIncluded(540, 800))
        eq(0L, taxIncluded(1250, 0))
    }
    test("a discount cap is rounded so it is never passed") {
        eq(99L, maxDiscount(1999, 500), "5 % of 19,99 € is 0,9995 €: at most 0,99 €")
        eq(100L, maxDiscount(1000, 1000))
        eq(0L, maxDiscount(1980, 0))
    }
    test("market rules: France 5 %, Japan none, Taiwan free, Korea 10 % and 15 % with points") {
        eq(500, Market.FR.newBookMaxDiscountBp); eq(550, Market.FR.bookTaxBp)
        eq(0, Market.JP.newBookMaxDiscountBp); eq(800, Market.JP.cafeTakeawayBp); ok(Market.JP.needsRegistrationNumber, "JP registration")
        eq(null, Market.TW.newBookMaxDiscountBp); eq(0, Market.TW.bookTaxBp)
        eq(1000, Market.KR.newBookMaxDiscountBp); eq(1500, Market.KR.newBookMaxBenefitBp); eq(0, Market.KR.bookTaxBp)
    }
    test("JSON writes and reads back text in every script, and refuses bad input") {
        val v = linkedMapOf("a" to "栞 « ok » \"q\" \n ", "n" to 12L, "l" to listOf(true, null, -3L), "m" to linkedMapOf("x" to "한국"))
        eq(v, Json.parse(Json.write(v)))
        Check.fails<Json.JsonError> { Json.parse("{\"a\":1,}") }
        Check.fails<Json.JsonError> { Json.parse("[1] x") }
        Check.fails<Json.JsonError> { Json.parse("[".repeat(100) + "]".repeat(100)) }
    }
    test("CSV: quotes, commas and line breaks; formulas are kept as text") {
        eq(listOf(listOf("a", "b,c", "d\"e"), listOf("f\ng", "")), Csv.parse("a,\"b,c\",\"d\"\"e\"\r\n\"f\ng\",\n"))
        eq("'=SUM(A1)", Csv.cell("=SUM(A1)"))
        eq("\"'+1,2\"", Csv.cell("+1,2"))
        eq("plain", Csv.cell("plain"))
    }
    test("Open Library answers are read, and partial dates take their last day") {
        val body = """{"ISBN:9780306406157":{"title":"Paper Gardens","subtitle":"A Field Guide","authors":[{"name":"Claire Dumont"}],"publishers":[{"name":"Atelier Ginkgo"}],"publish_date":"March 2020"}}"""
        val r = Lookup.parse(body, "9780306406157") as Lookup.Result.Hit
        eq("Paper Gardens : A Field Guide", r.found.title)
        eq(listOf("Claire Dumont"), r.found.authors)
        eq("Atelier Ginkgo", r.found.publisher)
        eq(java.time.LocalDate.of(2020, 3, 31), r.found.published)
        eq(Lookup.Result.Miss, Lookup.parse("{}", "9780306406157"))
        eq(java.time.LocalDate.of(2005, 12, 31), Lookup.date("2005"))
        eq(java.time.LocalDate.of(2005, 3, 12), Lookup.date("Mar 12, 2005"))
        eq(null, Lookup.date("someday"))
    }
}

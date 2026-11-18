package shiori

import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.random.Random

/** A clock the demo moves forward by hand, so that two months of a shop's life can be written in a second. */
class HandClock(var now: Instant, private val zone: ZoneId = ZoneId.of("UTC")) : Clock() {
    override fun getZone(): ZoneId = zone
    override fun withZone(zone: ZoneId): Clock = HandClock(now, zone).also { it.now = now }
    override fun instant(): Instant = now
}

/**
 * A fictional bookshop with two months of history, in any of the four markets.
 * Every book, author and publisher is invented; the ISBNs pass the checksum but name no real book.
 */
object Demo {
    private data class D(val titles: Map<String, String>, val author: String, val publisher: String, val lang: String, val section: String, val eur: Int, val published: String, val used: Boolean = false)

    private val BOOKS = listOf(
        D(mapOf("fr" to "Le Café des marées", "en" to "The Tide Café"), "Margot Lefèvre", "Éditions du Phare", "fr", "Literature", 2190, "2025-09-03"),
        D(mapOf("en" to "Paper Lanterns in the Rain", "ja" to "雨の提灯"), "Hana Whitfield", "Northwind Press", "en", "Literature", 1890, "2024-04-18"),
        D(mapOf("ja" to "夜明けの古書店", "en" to "The Dawn Bookshop"), "椎名 澪", "青灯社", "ja", "Literature", 1650, "2023-11-10"),
        D(mapOf("zh-Hant" to "府城巷弄裡的書", "en" to "Books in the Lanes of Tainan"), "林予安", "南風出版", "zh-Hant", "Literature", 1490, "2024-08-01"),
        D(mapOf("ko" to "바다가 보이는 서점", "en" to "A Bookshop by the Sea"), "한서윤", "물결출판", "ko", "Literature", 1590, "2025-03-21"),
        D(mapOf("fr" to "Petit atlas des îles oubliées", "en" to "A Small Atlas of Forgotten Islands"), "Julien Arnaud", "Éditions du Phare", "fr", "Travel", 2900, "2022-05-12"),
        D(mapOf("en" to "Slow Trains of East Asia", "zh-Hant" to "東亞慢車"), "Owen Clarke", "Northwind Press", "en", "Travel", 2400, "2023-06-30"),
        D(mapOf("ja" to "台南ごはん散歩", "zh-Hant" to "台南吃飯散步", "en" to "Eating Walks in Tainan"), "森 ことり", "旅灯社", "ja", "Travel", 1540, "2025-01-15"),
        D(mapOf("zh-Hant" to "花蓮山海線", "en" to "Hualien: Mountains and Sea"), "陳柏宇", "南風出版", "zh-Hant", "Travel", 1290, "2021-10-05"),
        D(mapOf("fr" to "La Baleine qui lisait", "en" to "The Whale Who Read", "ja" to "本を読むくじら"), "Inès Morel", "Bulles & Co", "fr", "Children", 1350, "2024-10-02"),
        D(mapOf("en" to "Momo and the Paper Moon", "zh-Hant" to "桃桃與紙月亮"), "Lily Tan", "Little Lamp", "en", "Children", 1199, "2025-06-11"),
        D(mapOf("ja" to "ねこの図書係", "en" to "The Cat Librarian"), "やまだ ゆう", "青灯社", "ja", "Children", 990, "2022-02-22"),
        D(mapOf("ko" to "고양이 사서", "en" to "Library Cat"), "이하늘", "물결출판", "ko", "Children", 1100, "2023-09-09"),
        D(mapOf("ja" to "書架の剣士 1", "en" to "Swordsman of the Stacks, vol. 1", "fr" to "Le Bretteur des rayonnages, t. 1"), "藤原 迅", "灯火コミックス", "ja", "Comics", 660, "2024-01-19"),
        D(mapOf("ja" to "書架の剣士 2", "en" to "Swordsman of the Stacks, vol. 2", "fr" to "Le Bretteur des rayonnages, t. 2"), "藤原 迅", "灯火コミックス", "ja", "Comics", 660, "2024-05-17"),
        D(mapOf("zh-Hant" to "珍珠奶茶偵探社", "en" to "The Bubble Tea Detectives"), "黃小魚", "南風漫畫", "zh-Hant", "Comics", 890, "2025-02-14"),
        D(mapOf("fr" to "Les Jardins de papier", "en" to "Paper Gardens"), "Claire Dumont", "Atelier Ginkgo", "fr", "Art", 3500, "2020-11-20"),
        D(mapOf("en" to "Ink and Indigo: Dyeing in Asia", "ja" to "藍と墨"), "Mei Sato-Grant", "Northwind Press", "en", "Art", 3800, "2023-03-03"),
        D(mapOf("ja" to "はじめての書道", "en" to "First Steps in Calligraphy", "zh-Hant" to "書法入門"), "高橋 墨", "青灯社", "ja", "Art", 1980, "2021-07-07"),
        D(mapOf("fr" to "Le japonais en 300 mots", "en" to "Japanese in 300 Words", "ja" to "300語でわかる日本語"), "Akiko Bernard", "Éditions Kotoba", "fr", "Languages", 1690, "2024-09-01"),
        D(mapOf("en" to "Everyday Taiwanese Mandarin", "zh-Hant" to "台灣日常華語"), "Grace Liu", "Little Lamp", "en", "Languages", 2200, "2023-01-12"),
        D(mapOf("ko" to "한국어 첫걸음", "en" to "First Steps in Korean", "fr" to "Premiers pas en coréen"), "박지민", "물결출판", "ko", "Languages", 1800, "2022-08-15"),
        D(mapOf("fr" to "Voyage au bout des pages", "en" to "Journey to the End of the Pages"), "Paul Girard", "Éditions du Phare", "fr", "Second-hand", 1500, "2010-04-01", used = true),
        D(mapOf("ja" to "古い港の物語", "en" to "Tales of an Old Harbour"), "大野 潮", "旅灯社", "ja", "Second-hand", 1200, "2008-06-20", used = true),
        D(mapOf("zh-Hant" to "老街的書", "en" to "Books of the Old Street"), "吳文德", "南風出版", "zh-Hant", "Second-hand", 900, "2012-12-12", used = true),
    )

    private val SECTIONS = mapOf(
        "Literature" to listOf("Littérature", "文芸", "文學"), "Travel" to listOf("Voyages", "旅行", "旅行"),
        "Children" to listOf("Jeunesse", "児童書", "童書"), "Comics" to listOf("BD et mangas", "コミック", "漫畫"),
        "Art" to listOf("Arts", "アート", "藝術"), "Languages" to listOf("Langues", "語学", "語言學習"),
        "Second-hand" to listOf("Occasion", "古本", "二手書"),
    )

    /** Section names in the shop's own language (Korea uses English in this version). */
    private fun section(en: String, m: Market): String = when (m) {
        Market.FR -> SECTIONS.getValue(en)[0]
        Market.JP -> SECTIONS.getValue(en)[1]
        Market.TW -> SECTIONS.getValue(en)[2]
        Market.KR -> en
    }

    /** Prices that look like each country's: ¥1,980, NT$490, ₩15,000, 21,90 €. */
    fun price(eur: Int, m: Market): Long = when (m) {
        Market.FR -> eur.toLong()
        Market.JP -> (eur * 160 / 100 / 100) * 100L - 20
        Market.TW -> (eur * 33 / 100 / 10) * 10L
        Market.KR -> (eur * 1400 / 100 / 1000) * 1000L
    }

    /** Invented ISBNs under 978-0-00, checksum-valid. */
    fun isbn(i: Int): String {
        val body = "97800%07d".format(1234500 + i * 37)
        return body + Isbn.check13(body)
    }

    private val DIRECT = setOf("Atelier Ginkgo", "Éditions Kotoba", "灯火コミックス")

    private val SUPPLIERS = mapOf(
        Market.FR to listOf("Diffusion du Phare", "Atelier Ginkgo", "Rachat d'occasion"),
        Market.JP to listOf("東灯書籍流通", "直取引", "古書買取"),
        Market.TW to listOf("南風圖書經銷", "出版社直營", "二手書收購"),
        Market.KR to listOf("Mulgyeol Distribution", "Direct from publisher", "Used book buy-back"),
    )

    /** Books customers ask for that the shop does not keep: they exist only through special orders. */
    private val SPECIAL = listOf(
        D(mapOf("fr" to "Oiseaux de Formose", "en" to "Birds of Formosa", "zh-Hant" to "福爾摩沙的鳥"), "Lin Wen-hao", "Little Lamp", "en", "Travel", 3200, "2025-04-04"),
        D(mapOf("ja" to "灯台守の日記", "en" to "The Lighthouse Keeper's Diary", "fr" to "Journal d'un gardien de phare"), "海野 灯", "青灯社", "ja", "Literature", 1870, "2025-10-10"),
        D(mapOf("fr" to "Cuisiner le riz", "en" to "Cooking Rice", "ko" to "밥 짓기"), "Sophie Kim", "Éditions du Phare", "fr", "Art", 2500, "2024-03-15"),
    )

    private val CUSTOMERS = mapOf(
        Market.FR to listOf("Camille R.", "Hugo L.", "Inès M.", "Paul D."),
        Market.JP to listOf("さくら S.", "ゆうと T.", "みお K.", "けんじ N."),
        Market.TW to listOf("怡君 L.", "家豪 C.", "雅婷 W.", "冠宇 H."),
        Market.KR to listOf("Seoyeon P.", "Minjun K.", "Jiwoo L.", "Hayun C."),
    )

    /** The last two weeks: special orders through their whole life, and unsold copies sent back. */
    private fun trade(shop: Shop, customers: Customers, clock: HandClock, market: Market, today: LocalDate, zone: ZoneId) {
        fun at(daysAgo: Long, hour: Int) { clock.now = today.minusDays(daysAgo).atTime(hour, 15).atZone(zone).toInstant() }
        val special = SPECIAL.mapIndexed { k, d ->
            val i = BOOKS.size + k
            shop.saveBook(Book(isbn(i), d.titles, listOf(d.author), d.publisher, d.lang, price(d.eur, market), Condition.NEW, LocalDate.parse(d.published), section(d.section, market)))
            isbn(i)
        }
        val names = CUSTOMERS.getValue(market)
        fun customer(n: Int) = customers.add(names[n], "0${n + 1}-0000-0000 (fictional)", "").id
        at(14, 11)
        val o1 = shop.placeOrder(customer(0), special[0], 1)
        val p1 = shop.createPurchaseOrder("dist", listOf(special[0] to 2), listOf(o1.id))
        at(10, 16)
        val o2 = shop.placeOrder(customer(1), special[1], 1)
        val p2 = shop.createPurchaseOrder("dist", listOf(special[1] to 1), listOf(o2.id))
        at(8, 10)
        shop.receivePurchaseOrder(p1.id, mapOf(special[0] to 2))
        at(6, 15)
        shop.sell(listOf(CartLine(LineKind.BOOK, special[0], 1, order = o1.id)), "card")
        at(5, 18)
        // Copies that have not sold for months go back while the distributor still takes them.
        val back = BOOKS.indices.filter { it % 7 == 3 && !BOOKS[it].used }.map { isbn(it) }
            .filter { shop.returnable(it, "dist") > 0 }.map { it to minOf(shop.returnable(it, "dist"), 2L) }
        if (back.isNotEmpty()) shop.returnToSupplier("dist", back, "Unsold")
        at(2, 10)
        shop.receivePurchaseOrder(p2.id, mapOf(special[1] to 1))
        at(1, 11)
        shop.markNotified(o2.id)
        val o3 = shop.placeOrder(customer(2), special[2], 1)
        shop.createPurchaseOrder("direct", listOf(special[2] to 1), listOf(o3.id))
        at(0, 10)
        shop.placeOrder(customer(3), special[0], 2)
    }

    fun create(dir: File, market: Market, today: LocalDate = LocalDate.now()) {
        require(!File(dir, "journal.jsonl").exists()) { "${dir.path} already holds a shop; choose an empty folder" }
        val zone = ZoneId.of(when (market) { Market.FR -> "Europe/Paris"; Market.JP -> "Asia/Tokyo"; Market.TW -> "Asia/Taipei"; Market.KR -> "Asia/Seoul" })
        val start = today.minusDays(240).atTime(10, 0).atZone(zone).toInstant()
        val clock = HandClock(start)
        Shop.open(dir, clock).use { shop ->
            shop.configure(Settings(
                name = when (market) { Market.FR -> "Librairie Shiori"; Market.JP -> "しおり書房"; Market.TW -> "栞書房"; Market.KR -> "시오리 책방" },
                market = market,
                registration = if (market == Market.JP) "T0000000000000" else "",
                address = when (market) { Market.FR -> "3 rue des Lanternes, Bordeaux (fictional)"; Market.JP -> "架空県しおり市 1-2-3"; Market.TW -> "台南市虛構路 12 號"; Market.KR -> "가상시 책길 7" },
                zone = zone.id,
            ))
            val names = mapOf(
                "coffee" to mapOf("fr" to "Café filtre", "en" to "Filter coffee", "ja" to "ドリップコーヒー", "zh-Hant" to "手沖咖啡"),
                "tea" to mapOf("fr" to "Thé oolong", "en" to "Oolong tea", "ja" to "烏龍茶", "zh-Hant" to "烏龍茶"),
                "cake" to mapOf("fr" to "Gâteau du jour", "en" to "Cake of the day", "ja" to "本日のケーキ", "zh-Hant" to "今日蛋糕"),
            )
            val cafeEur = mapOf("coffee" to 300, "tea" to 350, "cake" to 450)
            for ((id, n) in names) shop.saveProduct(Product(id, n, price(cafeEur.getValue(id), market)))

            val sup = SUPPLIERS.getValue(market)
            shop.saveSupplier(Supplier("dist", sup[0], if (market == Market.JP) 2200 else 3500, true, 0, "account 000-0000 (fictional)"))
            shop.saveSupplier(Supplier("direct", sup[1], 3000, true, 180, ""))
            shop.saveSupplier(Supplier("buyback", sup[2], 7500, false, 0, ""))
            fun supplierOf(d: D) = if (d.used) "buyback" else if (d.publisher in DIRECT) "direct" else "dist"

            val rnd = Random(2027)
            BOOKS.forEachIndexed { i, d ->
                val p = price(d.eur, market)
                shop.saveBook(Book(isbn(i), d.titles, listOf(d.author), d.publisher, d.lang, p,
                    if (d.used) Condition.USED else Condition.NEW, LocalDate.parse(d.published), section(d.section, market)))
                // Publishers deliver at about 60 % of the price; second-hand books are bought for about 25 %.
                shop.receive(isbn(i), (2 + rnd.nextInt(5)).toLong(), p * (if (d.used) 25 else 60) / 100, supplierOf(d))
            }
            // Eight months of trade, quieter at first; a restock when a title runs low.
            for (day in 0 until 240) {
                val date = today.minusDays(240L - day)
                val visits = if (day < 180) rnd.nextInt(2) else 2 + rnd.nextInt(6)
                for (v in 0 until visits) {
                    clock.now = date.atTime(11 + v % 8, rnd.nextInt(60)).atZone(zone).toInstant()
                    val cart = ArrayList<CartLine>()
                    val pick = BOOKS.indices.filter { (shop.stock[isbn(it)]?.qty ?: 0) > 0 && it % 7 != 3 }.shuffled(rnd).take(1 + rnd.nextInt(2))
                    for (b in pick) cart.add(CartLine(LineKind.BOOK, isbn(b), 1))
                    if (rnd.nextInt(3) == 0) cart.add(CartLine(LineKind.CAFE, listOf("coffee", "tea", "cake")[rnd.nextInt(3)], 1, null, rnd.nextBoolean()))
                    if (cart.isEmpty()) continue
                    runCatching { shop.sell(cart, listOf("cash", "card", "card")[rnd.nextInt(3)]) }
                }
                clock.now = date.atTime(19, 30).atZone(zone).toInstant()
                for (i in BOOKS.indices) {
                    val s = shop.stock[isbn(i)] ?: continue
                    if (s.qty <= 0 && i % 5 != 4) shop.receive(isbn(i), 3, price(BOOKS[i].eur, market) * (if (BOOKS[i].used) 25 else 60) / 100, supplierOf(BOOKS[i]))
                }
            }
            trade(shop, Customers(File(dir, "customers.json"), clock), clock, market, today, zone)
        }
    }
}

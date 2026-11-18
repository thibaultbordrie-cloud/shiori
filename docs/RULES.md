# The rules Shiori applies

Shiori ships with the rules below for the four markets. They are defaults for a small shop selling printed books and café drinks. Laws change and every shop has its own case: check them with your accountant or adviser. **This is not legal or tax advice.**

All shelf prices are tax-inclusive, as customers see them in all four countries. Tax is computed backwards from the price: `tax = price × rate / (1 + rate)`, rounded half up to the smallest unit, **once per rate for the whole receipt**.

| | France | Japan | Taiwan | South Korea |
|---|---|---|---|---|
| Currency, smallest unit used | EUR, 0.01 | JPY, 1 | TWD, 1 | KRW, 1 |
| Fixed price for new books | Yes: at most **5 %** off the publisher's price | Yes: **no discount** on new books | **No** fixed-price law in force | Yes: at most **10 %** off (15 % with points) |
| Second-hand books | Free price | Free price | Free price | Free price |
| Tax on books | VAT **5.5 %** | Consumption tax **10 %** | **Exempt** from business tax | **Exempt** from VAT |
| Café, eaten in / taken away | 10 % / 10 % | 10 % / **8 %** (reduced rate) | 5 % / 5 % | 10 % / 10 % |
| Receipt | Shop, date, lines, VAT by rate | Registration number (T + 13 digits), date, lines, totals and tax **by rate**, reduced-rate items marked ※ | Lines and total | Lines and total |

## France

- **Loi Lang** (loi n° 81-766 du 10 août 1981 relative au prix du livre). Article 1: the publisher sets the price; shops may sell at 95 % to 100 % of it. Article 5: shops may discount freely the books *published or imported more than two years ago and whose last delivery was more than six months ago*.
  Shiori applies article 5 only when it knows the publication date and the last delivery. A delivery of the same title restarts the six months. When a catalogue gives only a year or a month, Shiori takes the last day of it, so the exception never starts early.
- **VAT on books: 5.5 %** (Code général des impôts, article 278-0 bis).
- **Café**: 10 % for food and non-alcoholic drinks consumed on the spot or right away. Alcohol (20 %) and packaged food (5.5 %) are not handled in this version.
- **Cash register software**: since 2018, software used to record sales to private customers must be certified for inalterability, security, storage and archiving (CGI, article 286, I-3° bis). Shiori's journal is built in that spirit (append-only, hash-chained, verifiable), but **Shiori is not certified**. In France, use it alongside a certified till, or for second-hand and stock management only.

## Japan

- **再販売価格維持制度** (resale price maintenance): publishers may require shops to sell new books at the publisher's price. The Antimonopoly Act exempts copyrighted works from the ban on resale price maintenance (独占禁止法 第23条). The obligation comes from contracts, so Shiori applies the strictest default: no discount on new books.
- **Consumption tax**: 10 %; **8 %** for food and non-alcoholic drinks sold to take away (軽減税率). Eating in the café is 10 %.
- **Simplified qualified invoice** (適格簡易請求書), required since October 2023 for a shop receipt to support the customer's input-tax credit (国税庁 Q&A 問58): the issuer's name and **registration number**, the date, what was sold (reduced-rate items marked), the totals by rate, and the tax by rate or the rate itself. Shiori prints all of them and rounds the tax once per rate, as the rules require. The shop must have its own registration; the number in the demo is a placeholder.

## Taiwan

- **No fixed book price** is in force. A draft (new books at no less than 95 % of the list price during their first year) was discussed from 2015 but not adopted.
- **Books are exempt from business tax** (營業稅) since March 2021, through an amendment of 文化藝術事業減免營業稅及娛樂稅辦法. Books with an ISBN qualify; the exemption is reviewed every five years.
- **Café**: business tax 5 %, included in the price.
- **Uniform invoices** (統一發票, e-invoices) are **not issued by Shiori**. A Taiwanese shop needs its invoice system next to it.

## South Korea

- **도서정가제** (출판문화산업진흥법 제22조): the price on the book is binding; at most 10 % off, and at most 15 % of the price in total benefits (discount plus points). Shiori has no points yet, so it applies the 10 % cap. Second-hand books sold to readers are outside the rule.
- **Books are exempt from VAT** (부가가치세법 제26조).
- **Café**: VAT 10 %.
- The interface has no Korean translation yet; Korean shops use it in English. Book titles in Korean are supported and searchable.

## Sources

- France: [Loi n° 81-766 (Légifrance)](https://www.legifrance.gouv.fr/loda/id/JORFTEXT000000517179); [Prix du livre, marges et TVA à 5,5 % en 2026](https://hayot-expertise.fr/blog/librairie-prix-unique-livre-marges-tva); [Question écrite n° 17396, Assemblée nationale](https://questions.assemblee-nationale.fr/q17/17-17396QE.htm).
- Japan: [国税庁 消費税軽減税率制度 Q&A 問58](https://www.nta.go.jp/taxes/shiraberu/zeimokubetsu/shohi/keigenzeiritsu/pdf/qa/58.pdf).
- Taiwan: [圖書銷售免徵營業稅 3月正式上路 (公視新聞網)](https://news.pts.org.tw/article/511167/); [圖書定價制在台灣：箭在弦上 福禍難料 (中央社)](https://www.cna.com.tw/culture/article/20180128w001).
- South Korea: [도서정가제를 향한 두 시선 (전북일보)](https://www.jjan.kr/articleAmp/20200910716363).

Corrections from booksellers and accountants in these countries are very welcome in the issues.

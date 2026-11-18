package shiori

/** A tiny HTML builder: every text and attribute goes through escaping, so data never becomes markup. */
class Html {
    private val sb = StringBuilder()

    companion object {
        fun esc(s: String): String {
            val out = StringBuilder(s.length + 8)
            for (c in s) when (c) {
                '&' -> out.append("&amp;")
                '<' -> out.append("&lt;")
                '>' -> out.append("&gt;")
                '"' -> out.append("&quot;")
                '\'' -> out.append("&#39;")
                else -> out.append(c)
            }
            return out.toString()
        }

        private val NAME = Regex("[a-z][a-z0-9-]*")
        private val VOID = setOf("input", "br", "meta", "link", "hr")
    }

    fun tag(name: String, vararg attrs: Pair<String, Any?>, body: (Html.() -> Unit)? = null): Html {
        require(NAME.matches(name))
        sb.append('<').append(name)
        for ((k, v) in attrs) {
            require(NAME.matches(k) && !k.startsWith("on")) { "attribute $k" }
            when (v) {
                null, false -> {}
                true -> sb.append(' ').append(k)
                else -> sb.append(' ').append(k).append("=\"").append(esc(v.toString())).append('"')
            }
        }
        sb.append('>')
        if (name in VOID) return this
        body?.invoke(this)
        sb.append("</").append(name).append('>')
        return this
    }

    fun text(s: String): Html { sb.append(esc(s)); return this }

    operator fun String.unaryPlus() { text(this) }

    /** Trusted markup written in this source file only (never data). */
    fun raw(s: String): Html { sb.append(s); return this }

    override fun toString() = sb.toString()
}

const val CSS = """
:root{--bg:#fbf8f2;--fg:#2b2622;--muted:#6b625a;--line:#e3dccf;--accent:#7a3b2e;--accent-fg:#fff;--card:#fff;--ok:#2f6b3a;--warn:#8a3a14;
font-family:system-ui,-apple-system,"Segoe UI","Hiragino Sans","Noto Sans CJK JP","Noto Sans TC","Microsoft JhengHei",sans-serif;line-height:1.55}
@media (prefers-color-scheme:dark){:root{--bg:#1d1a17;--fg:#ece5da;--muted:#b3a99c;--line:#3a342e;--accent:#e39b7e;--accent-fg:#1d1a17;--card:#26221e;--ok:#8fd19e;--warn:#f0a07a}}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--fg)}
a{color:var(--accent)}a:focus,button:focus,input:focus,select:focus,textarea:focus{outline:3px solid var(--accent);outline-offset:2px}
.skip{position:absolute;left:-999px}.skip:focus{left:8px;top:8px;background:var(--card);padding:8px;z-index:2}
header{display:flex;flex-wrap:wrap;gap:4px 16px;align-items:center;padding:10px 16px;border-bottom:1px solid var(--line);background:var(--card)}
header .brand{font-weight:700;text-decoration:none;color:var(--fg);margin-right:8px}header nav{display:flex;flex-wrap:wrap;gap:4px 14px}
header nav a{text-decoration:none;padding:4px 2px}header nav a[aria-current=page]{border-bottom:2px solid var(--accent);font-weight:600}
header form{margin-left:auto;display:flex;gap:8px;align-items:center}header label{white-space:nowrap}header select{width:auto}
main{max-width:1040px;margin:0 auto;padding:16px}h1{font-size:1.6rem;margin:.4em 0 .6em}h2{font-size:1.2rem;margin:1.4em 0 .5em}
.cards{display:grid;grid-template-columns:repeat(auto-fit,minmax(170px,1fr));gap:12px}
.card{background:var(--card);border:1px solid var(--line);border-radius:8px;padding:12px 14px}.card b{display:block;font-size:1.4rem}
table{width:100%;border-collapse:collapse;background:var(--card);border:1px solid var(--line)}th,td{text-align:left;padding:7px 9px;border-bottom:1px solid var(--line);vertical-align:top}
td.num,th.num{text-align:right;font-variant-numeric:tabular-nums;white-space:nowrap}
.tablewrap{overflow-x:auto}
form.stack{display:grid;gap:10px;max-width:560px}label{display:block;font-weight:600;font-size:.95rem}
input,select,textarea{font:inherit;padding:7px 9px;border:1px solid var(--muted);border-radius:6px;background:var(--bg);color:var(--fg);width:100%}
input[type=checkbox]{width:auto}
button,.button{font:inherit;padding:8px 14px;border-radius:6px;border:1px solid var(--accent);background:var(--accent);color:var(--accent-fg);cursor:pointer;text-decoration:none;display:inline-block}
button.secondary,.button.secondary{background:transparent;color:var(--accent)}.row{display:flex;flex-wrap:wrap;gap:8px;align-items:end}.row>*{flex:1 1 140px}
.inline{display:inline}.spacer{height:20px}.help{color:var(--muted);font-size:.9rem;font-weight:400}
.msg{padding:10px 12px;border-radius:6px;border:1px solid var(--line);background:var(--card)}.msg.error{border-color:var(--warn);color:var(--warn)}.msg.ok{border-color:var(--ok);color:var(--ok)}
.receipt{background:#fff;color:#111;max-width:340px;padding:16px;border:1px dashed #999;font-family:ui-monospace,"Cascadia Mono","Noto Sans Mono CJK JP",monospace;font-size:.9rem}
.receipt.wide{max-width:640px}.receipt .r{display:flex;justify-content:space-between;gap:8px}.receipt hr{border:0;border-top:1px dashed #999}
footer{color:var(--muted);font-size:.85rem;text-align:center;padding:24px 16px}
@media print{header,footer,.noprint{display:none}main{padding:0}.receipt{border:0}}
"""

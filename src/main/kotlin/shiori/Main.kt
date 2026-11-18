package shiori

import java.io.File
import kotlin.system.exitProcess

const val VERSION = "0.1.0"

private const val USAGE = """Shiori $VERSION, software for independent bookshops.

  shiori serve    [--data DIR] [--port 8080] [--host 127.0.0.1] [--offline] [--allow-host NAME:PORT]
  shiori demo     --market fr|jp|tw|kr [--data DIR]    create a fictional shop with 8 months of history
  shiori verify   [--data DIR]                          check that the journal was not altered
  shiori import   FILE.csv [--supplier NAME] [--data DIR]
  shiori passphrase [--data DIR]                        choose a new passphrase (at the computer only)
  shiori version

DIR defaults to ./shiori-data. The shop is served on 127.0.0.1 only, unless --host says otherwise."""

fun main(args: Array<String>) {
    exitProcess(run(args.toList()))
}

fun run(args: List<String>): Int {
    val cmd = args.firstOrNull() ?: return usage()
    val opts = HashMap<String, String>()
    val pos = ArrayList<String>()
    var i = 1
    while (i < args.size) {
        val a = args[i]
        if (a.startsWith("--")) {
            val k = a.removePrefix("--")
            if (k == "offline") opts[k] = "1" else {
                if (i + 1 >= args.size) return fail("--$k needs a value")
                opts[k] = args[++i]
            }
        } else pos += a
        i++
    }
    val dir = File(opts["data"] ?: "shiori-data")
    return try {
        when (cmd) {
            "serve" -> serve(dir, opts)
            "demo" -> {
                val m = Market.of(opts["market"] ?: "") ?: return fail("--market must be fr, jp, tw or kr")
                Demo.create(dir, m)
                println("A fictional ${m.name} shop is ready in ${dir.path}. Start it with: shiori serve --data ${dir.path}")
                0
            }
            "verify" -> {
                val f = File(dir, "journal.jsonl")
                if (!f.exists()) return fail("no journal in ${dir.path}")
                val problems = Journal.verify(f)
                if (problems.isEmpty()) { println("journal intact: ${f.readLines().count { it.isNotBlank() }} entries"); 0 }
                else { problems.forEach { System.err.println(it) }; 2 }
            }
            "import" -> {
                val file = File(pos.firstOrNull() ?: return fail("which CSV file?"))
                Shop.open(dir).use { shop ->
                    val rep = Csv.import(shop, file.readText(), opts["supplier"] ?: file.nameWithoutExtension)
                    println("${rep.added} books saved, ${rep.received} copies received")
                    rep.errors.forEach { System.err.println(it) }
                    if (rep.errors.isEmpty()) 0 else 1
                }
            }
            "passphrase" -> {
                val console = System.console() ?: return fail("run this command in a terminal")
                val p1 = String(console.readPassword("New passphrase (at least ${Auth.MIN_LENGTH} characters): ") ?: return 1)
                val p2 = String(console.readPassword("Again: ") ?: return 1)
                if (p1 != p2 || p1.length < Auth.MIN_LENGTH) return fail("the passphrases differ or are too short")
                Auth(File(dir, "auth.json")).reset(p1)
                println("Passphrase changed. Restart Shiori to close open sessions.")
                0
            }
            "version", "--version" -> { println("shiori $VERSION"); 0 }
            else -> usage()
        }
    } catch (e: JournalError) {
        fail(e.message ?: "journal error")
    } catch (e: IllegalArgumentException) {
        fail(e.message ?: "bad input")
    }
}

private fun serve(dir: File, opts: Map<String, String>): Int {
    val host = opts["host"] ?: "127.0.0.1"
    val port = opts["port"]?.toIntOrNull() ?: 8080
    val shop = Shop.open(dir)
    val web = Web(shop, Auth(File(dir, "auth.json")), Lookup(enabled = "offline" !in opts)).start(host, port)
    opts["allow-host"]?.split(',')?.forEach { web.allowHost(it.trim()) }
    Runtime.getRuntime().addShutdownHook(Thread { web.stop(); shop.close() })
    println("Shiori is open: http://127.0.0.1:${web.port}/  (data: ${dir.absolutePath})")
    if (host != "127.0.0.1" && host != "localhost") println("Warning: listening on $host. Anyone on this network can reach the login page; use a strong passphrase.")
    Thread.currentThread().join()
    return 0
}

private fun usage(): Int { println(USAGE); return 64 }

private fun fail(msg: String): Int { System.err.println("shiori: $msg"); return 1 }

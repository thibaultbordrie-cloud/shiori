# Security

Shiori holds a shop's sales and stock. Please report a vulnerability privately through **GitHub's private vulnerability reporting** (Security tab → Report a vulnerability), not in a public issue.

## What Shiori protects, and how

- **The journal.** Append-only, one JSON line per fact, flushed to disk before it counts, each line chained to the previous one by SHA-256. `shiori verify` reports any change, removal or reordering; Shiori refuses to start on a damaged journal. A lock file stops two processes from writing at once. The chain detects changes; it does not stop someone with access to the files from rewriting the whole journal. Keep backups of the data folder somewhere else.
- **Access.** One passphrase per shop (at least 10 characters), stored as PBKDF2-SHA-256 with 600,000 iterations and a random salt, in `auth.json`. After five wrong tries, logins wait one minute. Sessions are random 256-bit tokens kept in memory, in an HttpOnly, SameSite=Strict cookie, closed after 12 hours without use.
- **The network.** Shiori listens on 127.0.0.1 unless told otherwise. Requests whose `Host` is not an expected name get 421 (this stops DNS-rebinding attacks from a web page). If you open it to the shop's network with `--host`, use a strong passphrase. The server speaks plain HTTP, so put it behind a TLS proxy before going any further than the shop's own network.
- **The pages.** Every change is a POST carrying a per-session token; cross-site POSTs are refused by their Origin. Pages send a strict Content-Security-Policy (`default-src 'none'`), contain no JavaScript, and escape every value. CSV exports keep cells that start with `=`, `+`, `-` or `@` as text.
- **Outgoing traffic.** Only the Open Library lookup, when the bookseller clicks it, and it sends only the ISBN. `--offline` turns it off.

## Out of scope

- Someone with administrator access to the shop's computer.
- Certification as cash register software (France, CGI article 286): Shiori does not have it.

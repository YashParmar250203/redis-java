# redis-java — Phase 1: Core Key-Value Engine

A Redis-inspired in-memory database built from scratch in Java + Spring Boot.
This README covers **Phase 1 only**, per the project's phase-by-phase build rule.

## What was implemented

- Thread-safe in-memory key-value store (`InMemoryStore`, backed by `ConcurrentHashMap`)
- `SET key value`, `GET key`, `DEL key [key ...]`, `EXISTS key [key ...]`
- A transport-agnostic `Command` abstraction + `CommandExecutor` dispatcher
- A REST endpoint (`POST /api/command`) that accepts raw Redis-style command
  text and returns a JSON envelope
- Centralized, Redis-style error handling (`ERR ...` messages)
- Unit tests for storage, dispatch, and the REST layer (incl. a concurrency test)

## Architecture

```
controller/   → CommandController (HTTP -> CommandExecutor)
command/      → Command interface, CommandExecutor (parsing + dispatch)
command/impl/ → SetCommand, GetCommand, DelCommand, ExistsCommand
storage/      → Store interface, InMemoryStore (ConcurrentHashMap-backed)
model/        → CommandResponse (JSON envelope: success/result/error)
exception/    → RedisException hierarchy (Redis-style "ERR ..." messages)
config/       → GlobalExceptionHandler (RedisException -> HTTP 400 JSON)
```

Data flow:

```
HTTP POST /api/command  ("SET name Yash")
        |
        v
CommandController -----> CommandExecutor.execute(rawLine)
                                |
                        tokenize + look up Command by name
                                |
                                v
                       SetCommand.execute(args) ---> Store.set(key, value)
                                |
                                v
                        CommandResponse (JSON)
```

## Why this design

**`ConcurrentHashMap` over `synchronized Map`.** ConcurrentHashMap uses
bucket-level locking (and CAS operations for the common path) instead of one
lock guarding the entire map. Reads are effectively lock-free; writes only
contend when two threads hit the same bucket. This matters once concurrent
clients are added in Phase 6 — a single global lock would serialize every
operation regardless of which keys are touched.

**`Command` abstraction now, even though Phase 1 only needs 4 REST calls.**
Phase 4 replaces the transport with a raw TCP server parsing RESP, and both
transports need to reach the exact same dispatch/validation logic. By
routing REST through `CommandExecutor` today, adding TCP later means writing
a socket loop that calls `CommandExecutor.execute(line)` — zero changes to
command logic, tests, or validation.

**Errors mirror Redis's own reply text** (`ERR wrong number of arguments for
'set' command`) rather than generic Java exception messages. This means the
error *content* is already correct for Phase 4, when these get wrapped as
RESP error replies (`-ERR ...\r\n`) instead of HTTP 400 JSON bodies.

## Time/space complexity

| Command | Time complexity | Notes |
|---|---|---|
| `SET` | O(1) average | Single `ConcurrentHashMap.put` |
| `GET` | O(1) average | Single `ConcurrentHashMap.get` |
| `DEL` | O(n) in keys given | O(1) per key removed |
| `EXISTS` | O(n) in keys given | O(1) per key checked |

Space: O(k) where k = number of distinct keys currently stored.

## Known limitations (by design, deferred to later phases)

- Only string values — no lists/sets/hashes/sorted sets yet (Phase 3).
- No TTL/expiration yet (Phase 2).
- Tokenization is naive whitespace-splitting — a value containing a space
  (e.g. `SET name "Yash Sharma"`) is **not** supported yet. Real argument
  boundary handling arrives with RESP parsing in Phase 4.
- Read-modify-write sequences (like a future `INCR`) are not atomic just
  because the store is thread-safe — `store.get()` then `store.set()` is two
  separate operations. This will matter in Phase 6/7 and needs
  `ConcurrentHashMap.compute()`-style atomic updates.
- No persistence — restarting the app clears all data (Phase 5).

## How to run

```bash
mvn spring-boot:run
```

Test with curl:

```bash
curl -X POST http://localhost:8080/api/command -H "Content-Type: text/plain" -d "SET name Yash"
curl -X POST http://localhost:8080/api/command -H "Content-Type: text/plain" -d "GET name"
curl -X POST http://localhost:8080/api/command -H "Content-Type: text/plain" -d "EXISTS name missing"
curl -X POST http://localhost:8080/api/command -H "Content-Type: text/plain" -d "DEL name"
```

Run tests:

```bash
mvn test
```

## What I learned (fill this in as you build)

- Why `ConcurrentHashMap`'s locking strategy beats a single global lock
  under concurrent access, and how to explain that in an interview.
- Why decoupling command dispatch from transport (REST now, TCP later)
  avoids rewriting business logic when the protocol changes.
- The difference between a command "succeeding with a null result" (GET
  miss) and a command "failing" (unknown command) — and why conflating
  them in an API is a design smell.

## Phase 2 — TTL and Expiration

**What was implemented:** `SET key value EX seconds`, `EXPIRE key seconds`, `TTL key`. Hybrid lazy + active expiration.

**Architecture changes:** `StoredValue` wraps every value with an optional `expireAtMillis`. A `keysWithExpiry` index (separate from the main map) lets active expiration sample only keys that actually have a TTL. A new `ttl` package holds `ExpirationScheduler`, a `@Scheduled(fixedDelay = 100)` job that mimics Redis's real active-expire-cycle: sample up to 20 keys with a TTL, reap expired ones, and repeat immediately if more than 25% of the sample was expired.

**Design decisions:** `EXPIRE` uses `ConcurrentHashMap.computeIfPresent` for atomic read-modify-write, closing a race window a naive get-then-put would have. Errors mirror real Redis text (`ERR invalid expire time in 'expire' command`, etc).

**Complexity:** `EXPIRE`/`TTL` are O(1) average. Active expiration is O(sample size) per cycle, not O(all keys).

**Tests:** lazy expiration on read, TTL updates/clears, `DEL`/`EXPIRE` on already-expired keys, and a deterministic active-expiration-cycle test.

**What I learned:** why lazy-only expiration leaks memory, why active-only wastes CPU scanning everything, and why the combination (plus sampling instead of full scans) is the standard answer.

**What to improve before Phase 3:** none carried over — the TTL mechanism turned out to compose cleanly with typed values in Phase 3 below.

---

## Phase 3 — Redis Data Structures

**What was implemented:** Lists (`LPUSH`, `RPUSH`, `LPOP`, `RPOP`, `LRANGE`), Sets (`SADD`, `SREM`, `SISMEMBER`, `SMEMBERS`), Hashes (`HSET`, `HGET`, `HDEL`, `HGETALL`), Sorted Sets (`ZADD`, `ZRANGE`, `ZREM`).

**Architecture changes:** `StoredValue` now carries a `RedisType` tag (`STRING`/`LIST`/`SET`/`HASH`/`ZSET`); mismatched-type commands throw `WrongTypeException`. Storage operations are split into `ListOperations`, `SetOperations`, `HashOperations`, `SortedSetOperations` interfaces (mirroring Spring Data Redis's own split), all implemented by `InMemoryStore`, so each `Command` only depends on the interface it actually uses.

**Design decisions:**
- Lists use `ConcurrentLinkedDeque` (lock-free, O(1) push/pop from either end) instead of hand-rolled locking.
- Sets and hash field-maps reuse `ConcurrentHashMap`-backed structures.
- Sorted sets use a dedicated `SortedSetValue`: a `ConcurrentHashMap<String,Double>` for O(1) score lookup plus a `ConcurrentSkipListSet<ScoredMember>` ordered by (score, member) for range queries — the same dual-structure design real Redis uses internally. Writes to a single ZSET's two structures are synchronized on that ZSET's own instance (lock scoped to one key, not the whole store).
- Popping/removing the last element of a list/set/hash/zset deletes the key entirely, matching real Redis.
- `getOrCreate` uses `ConcurrentHashMap.compute` so "check type, create if absent" is one atomic step.

**Complexity per operation:**

| Command | Complexity |
|---|---|
| `LPUSH`/`RPUSH`/`LPOP`/`RPOP` | O(1) |
| `LRANGE` | O(n) snapshot + O(k) slice |
| `SADD`/`SREM`/`SISMEMBER` | O(1) average |
| `SMEMBERS` | O(n) |
| `HSET`/`HGET`/`HDEL` | O(1) average |
| `HGETALL` | O(n) in field count |
| `ZADD`/`ZREM` | O(log n) |
| `ZRANGE` | O(n) — see limitation below |

**Known limitation (intentional, documented rather than hidden):** `ZRANGE` walks the ordered structure from the front, so it's O(n) to reach an arbitrary start index instead of O(log n). Real Redis's skip list carries "span" counters per level specifically to support O(log n) rank access. Building that augmented skip list is a natural, strong interview-story extension — deferred rather than over-engineered into this phase.

**Tests:** per-type happy path, empty-key behavior (reads return empty, not errors), empty-container key deletion, and `WrongTypeException` cross-type checks; a `ZADD` score-update-reorders-rank test for the sorted set.

**What I learned:** how to pick a concurrent structure per access pattern instead of reaching for "one lock over everything"; why Redis's own ZSET needs two coordinated structures and how to reason about the consistency window that coordination trades away; interface segregation applied to a real storage layer.

**What should be improved before Phase 4:** the RESP tokenizer will need real argument-boundary parsing (values containing spaces aren't supported by today's whitespace-split parser) — this becomes unavoidable once binary-safe values are on the table.

---

## Phase 4 — Custom TCP Server and RESP Protocol

**What was implemented:** A single-threaded, NIO Selector-based TCP server on port 6380 (configurable via `redis.tcp.port`) speaking real RESP (multibulk arrays) and Redis's inline-command shorthand, both handled by the same incremental parser. `redis-cli -p 6380` works against it directly.

**Architecture changes:** New `server` package (`TcpServer`, `ClientConnection`) and `protocol` package (`RequestParser`, `RespReplyEncoder`). `CommandExecutor` gained `execute(String[] tokens)` — the REST path's `execute(String)` now just tokenizes and delegates to it. No `Command` implementation changed at all; the abstraction built in Phase 1 specifically for this moment did its job.

```
Client (redis-cli or telnet)
        |  TCP
        v
TcpServer (NIO Selector event loop)
        |
ClientConnection (buffers bytes, calls RequestParser)
        |
RequestParser (RESP array or inline line -> String[] tokens)
        |
CommandExecutor.execute(String[])   <-- same dispatcher REST uses
        |
Command.execute(args) -> Store
        |
RespReplyEncoder (result -> RESP wire reply)
        |
        v
back to client
```

**Design decisions:**
- **Single-threaded event loop, deliberately** — mirrors real Redis's own concurrency model. No locking needed around command execution on this path since only one command runs at a time across all clients; the honest cost is that one slow command blocks everyone else, same trade-off Redis itself makes.
- **Dual protocol support is one implementation, not two** — real Redis servers accept both RESP arrays and plain inline lines; detecting by first byte (`*` vs anything else) is exactly what real Redis does, not a shortcut invented here.
- **Never consume buffered bytes until a complete command is confirmed present.** This is the core correctness property for any incremental network parser — a command can arrive split across multiple reads, and partially consuming the buffer would corrupt framing for everything after it.
- **`SimpleStringReply` marker type** was introduced so `SET`'s `"OK"` reply is distinguishable from a `GET` result that happens to literally be the string `"OK"` — only the former should be RESP-encoded as a status line (`+OK\r\n`) rather than a bulk string.
- **Pipelining works without extra code** — parsing loops while the buffer still has a complete command, so multiple commands sent in one packet all get executed and replied to.

**Known, stated simplifications (not oversights):**
- Connection data is treated as UTF-8 text, not raw binary — real Redis bulk strings are fully binary-safe (arbitrary bytes, including nulls); ours assumes single-byte-per-character content.
- Writes use a simple blocking-retry loop rather than registering for `OP_WRITE` and resuming on write-readiness. Fine for this project's small, single-reply payloads; a server handling large replies or slow clients would need real write backpressure handling to avoid busy-spinning.

**Tests:** `RequestParserTest` covers RESP parsing across multiple simulated TCP chunks (the actual hard part of this phase), pipelined inline commands, and malformed-input protocol errors — all without touching a real socket. `RespReplyEncoderTest` covers every reply type including the `"OK"`-as-bulk-string-vs-status-line distinction. `TcpServerIntegrationTest` opens genuine sockets against the real running server (bound to an OS-assigned ephemeral port via `redis.tcp.port=0`) and verifies inline commands, RESP round-trips, pipelining, error replies, and RESP values containing spaces.

**What I learned:** why an incremental parser must never partially consume its buffer; how to reason about partial TCP reads and reassembly; why Redis's single-threaded design is a legitimate engineering trade-off rather than a limitation to work around; how to keep a dispatch layer transport-agnostic in practice, not just in theory.

**What should be improved before Phase 5:** persistence (AOF) will need to hook into the same `CommandExecutor.execute` path to log every mutating command — worth checking whether that's best done as a decorator around `CommandExecutor` or inside each `Command`, before writing any persistence code.

---

## Phase 5 — Persistence and Crash Recovery

**What was implemented:** AOF (append-only file) persistence with configurable fsync policy, plus an alternative RDB-style periodic snapshot strategy — switchable via `redis.persistence.mode=aof|snapshot|none` (default `aof`).

**Architecture changes:**
- Extracted a `CommandDispatcher` interface; `CommandExecutor` is now the "raw" implementation. REST (`CommandController`) and TCP (`TcpServer`/`ClientConnection`) depend on the interface, not the concrete class.
- New `PersistingCommandDispatcher` (in a new `persistence` package) decorates `CommandExecutor`: runs the real command first, and only if it succeeds *and* the resolved `Command.isWrite()` is true, appends it to the AOF. `@Primary` + `@ConditionalOnProperty` mean it only exists (and only gets auto-wired in place of the raw executor) when `redis.persistence.mode=aof`.
- Every `Command` implementation now declares `isWrite()` explicitly — no default — so a new command author must consciously decide, rather than silently inheriting a guess that could cause silent data loss (a missed write) or wasted logging (a logged read) on crash recovery.
- AOF entries are RESP-encoded (`protocol.RespCommandEncoder`), the same format real Redis's own AOF has used since v7. Replay (`persistence.AofReader`) reuses `RequestParser` from Phase 4 directly.
- `storage.Snapshottable` interface + `InMemoryStore` implementation for RDB-style dumps (Java serialization of a plain-`HashMap` copy of the keyspace); `persistence.SnapshotWriterScheduler` writes it to a temp file and atomically renames it into place on a fixed interval.
- `persistence.PersistenceRecoveryRunner` (`@Order(1)`) restores state at startup according to the configured mode, always through the raw `CommandExecutor` (never the AOF-logging decorator, or replay would re-log everything forever). `TcpServer` is `@Order(2)` so no client can connect before recovery finishes.

**Design decisions:**
- **AOF logging lives in a decorator, not inside any `Command`.** This was flagged as an open question at the end of Phase 4 and resolved here: commands stay completely unaware persistence exists.
- **AOF and snapshot are mutually exclusive strategies, not combined.** Real Redis's combined mode (RDB baseline + AOF rewrite/compaction layered on top) requires coordinating AOF-rewrite timing with snapshot timing — genuinely hard, and deliberately out of scope. Implementing both correctly in isolation was judged more valuable than one half-integrated hybrid.
- **Reusing the Phase 4 parser for AOF replay wasn't just convenient — it solved a real problem for free.** The parser's rule of never consuming a record until it's confirmed complete (built for TCP packets arriving in pieces) means a truncated AOF tail from a mid-write crash is handled correctly with zero new code.
- **Fsync policy is configurable and precisely documented** (`ALWAYS`/`EVERYSEC`/`NO`), matching real Redis's own three options and their exact durability trade-offs (see `AofFsyncPolicy` javadoc).
- **Snapshots are written atomically** (temp file + atomic rename), matching real Redis's RDB save — a reader can never observe a half-written file.

**Known, honestly-stated limitations:**
- **TTL correctness bug on AOF replay:** `SET key value EX 60` is logged verbatim; replaying it recomputes the expiry as *replay-time + 60s*, silently resetting the TTL window instead of preserving the original expiry moment. Real Redis avoids this by rewriting relative expiries to absolute timestamps (`PEXPIREAT`) before logging. Fixing this properly would mean giving the persistence layer command-specific rewriting knowledge, which conflicts with keeping commands persistence-unaware — flagged rather than silently worked around.
- Snapshotting copies the live map directly rather than using a true copy-on-write fork() (which real Redis's RDB save uses) — a fast, "mostly consistent" snapshot, not a strictly atomic one.
- Java serialization is used for the snapshot format: simple and correct for this project, but not cross-language portable and fragile across incompatible code changes, unlike Redis's own compact, versioned binary RDB format.
- A brief startup race exists between Spring's embedded Tomcat (which starts during context refresh, before `ApplicationRunner`s run) and `PersistenceRecoveryRunner` completing — a REST request could theoretically arrive before recovery finishes. Left as a Phase 10 hardening concern since REST is only used for ad hoc testing here.

**Tests:** `AofWriterReaderTest` covers write→replay round trips (including values with spaces), a missing file, a genuinely truncated trailing record, and a genuinely malformed record — the last two specifically verifying the "stop, don't guess" corruption behavior. `InMemoryStoreSnapshotTest` covers a full round trip across every data type plus TTL, complete state replacement on restore, and an already-expired-but-unreaped key correctly staying expired after restore. `CommandExecutorTest` gained `isWriteCommand` coverage.

**What I learned:** why persistence belongs in a decorator rather than in command logic; how storing AOF entries in the same wire format the parser already understands turns "handle corruption gracefully" from new work into free reuse; the precise (not hand-wavy) difference between what each fsync policy actually protects against; why AOF+RDB coordination is a genuinely hard problem rather than a missing afternoon of work.

**What should be improved before Phase 6:** concurrency work is next - benchmarking the TCP server's single-threaded model against the REST path's multi-threaded one, and revisiting whether `AofWriter`'s `synchronized` methods become a bottleneck under concurrent write load.




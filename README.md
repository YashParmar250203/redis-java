# redis-java — A Redis Clone Built From Scratch (Java + Spring Boot)

A from-scratch reimplementation of core Redis internals: a typed in-memory
data engine, TTL expiration, a hand-rolled TCP server speaking real RESP,
AOF/snapshot persistence, and sampling-based LRU/LFU eviction — no Redis
dependency anywhere, no third-party command-engine library. Spring Boot is
used for wiring, configuration, and the REST test surface; every piece of
actual database logic is original.

**This README is written as interview prep, not just documentation.** It
covers what was built, *why* each design decision was made over the
alternatives, what's deliberately simplified (and why that's honest
engineering, not a gap), and a dedicated Q&A section mapping common
interview questions directly to parts of this codebase.

---

## Table of Contents

1. [What This Demonstrates](#what-this-demonstrates)
2. [Quick Start](#quick-start)
3. [Architecture Overview](#architecture-overview)
4. [Supported Commands](#supported-commands)
5. [Deep Dive: Data Structures](#deep-dive-data-structures)
6. [Deep Dive: TTL & Expiration](#deep-dive-ttl--expiration)
7. [Deep Dive: Networking — TCP Server & RESP Protocol](#deep-dive-networking--tcp-server--resp-protocol)
8. [Deep Dive: Persistence — AOF & Snapshots](#deep-dive-persistence--aof--snapshots)
9. [Deep Dive: Eviction & Memory Management](#deep-dive-eviction--memory-management)
10. [Deep Dive: Concurrency Design](#deep-dive-concurrency-design)
11. [Key Design Decisions, Consolidated](#key-design-decisions-consolidated)
12. [Known Limitations (Stated Honestly)](#known-limitations-stated-honestly)
13. [Interview Q&A — Mapped to This Codebase](#interview-qa--mapped-to-this-codebase)
14. [Testing Strategy](#testing-strategy)
15. [Project Structure](#project-structure)
16. [Configuration Reference](#configuration-reference)
17. [Roadmap (Not Yet Built)](#roadmap-not-yet-built)

---

## What This Demonstrates

- **Data structures under real constraints**: choosing a concurrent structure per access pattern (deque, skip list, hash-backed set) instead of "one map, one lock."
- **Network protocol implementation**: a hand-rolled RESP parser handling partial TCP reads correctly — the actual hard part of building any wire protocol.
- **Systems trade-offs stated precisely**: fsync durability levels, AOF vs snapshot, exact vs approximate LRU — each with the *real* cost/benefit, not a hand-wave.
- **Concurrency reasoning**: where locks exist, why they're scoped as narrowly as possible, and an honest admission of the one place they aren't (AOF writes).
- **Decorator-based extensibility**: persistence and (in later phases) transactions bolt onto a dispatch layer that never had to change.

---

## Quick Start

```bash
mvn spring-boot:run
```

This starts two servers:
- REST on `:8080` — `POST /api/command` with a raw command line as the body (e.g. `SET name Yash`). Meant for quick testing, not a production API.
- Raw TCP on `:6380` (configurable via `redis.tcp.port`) — speaks real RESP. `redis-cli -p 6380` works against it directly.

```bash
redis-cli -p 6380
127.0.0.1:6380> SET name Yash
OK
127.0.0.1:6380> LPUSH mylist a b c
(integer) 3
127.0.0.1:6380> LRANGE mylist 0 -1
1) "c"
2) "b"
3) "a"
```

No `redis-cli` on Windows? See `test-tcp.ps1` — a zero-dependency PowerShell client using raw `TcpClient` sockets.

Run tests: `mvn test`

---

## Architecture Overview

```
com.example.redis/
├── controller/    REST entry point (CommandController)
├── server/        TCP server: NIO event loop + per-connection parsing (TcpServer, ClientConnection)
├── protocol/      Wire format: RESP parsing + encoding (RequestParser, RespReplyEncoder, RespCommandEncoder)
├── command/       Dispatch layer: Command interface, CommandExecutor, CommandDispatcher
├── command/impl/  One class per command (22 commands)
├── command/util/  Shared argument-parsing helpers
├── storage/       The actual database engine (InMemoryStore + typed operation interfaces)
├── persistence/   AOF + snapshot persistence, as a decorator around dispatch
├── ttl/           Background active-expiration scheduler
├── model/         Cross-layer reply types (SimpleStringReply, CommandResponse, etc.)
├── exception/     RedisException hierarchy — every message matches real Redis's own text
└── config/        Spring wiring (GlobalExceptionHandler)
```

### Request flow (both transports funnel through the same dispatcher)

```
REST: HTTP POST /api/command ("SET name Yash")
TCP:  raw bytes over a socket, RESP or inline
        │                              │
        ▼                              ▼
CommandController          ClientConnection (buffers bytes,
        │                   calls RequestParser incrementally)
        │                              │
        └──────────┬───────────────────┘
                    ▼
         CommandDispatcher.execute(String[] tokens)
                    │
         (AOF-logging decorator, if enabled, wraps this)
                    │
                    ▼
         CommandExecutor: look up Command by name, dispatch
                    │
                    ▼
         Command.execute(args) → Store / ListOperations / etc.
                    │
                    ▼
         Result: Java object (String, Long, List, Map, SimpleStringReply...)
                    │
         ┌──────────┴──────────┐
         ▼                     ▼
 CommandResponse (JSON)  RespReplyEncoder (RESP wire bytes)
```

**The single most important design decision in this project**: `CommandExecutor` (built in Phase 1, for 4 commands, over REST only) never had its dispatch logic changed to support a completely different transport (raw TCP/RESP, Phase 4) or a completely new cross-cutting concern (AOF persistence, Phase 5). Both were added by wrapping the *interface* it implements, `CommandDispatcher`, not by touching the dispatcher or any of the 22 command classes. That's the payoff of investing in a transport-agnostic abstraction one phase before it was strictly necessary.

---

## Supported Commands

| Type | Commands |
|---|---|
| String + TTL | `SET` (+ `EX`), `GET`, `DEL`, `EXISTS`, `EXPIRE`, `TTL` |
| List | `LPUSH`, `RPUSH`, `LPOP`, `RPOP`, `LRANGE` |
| Set | `SADD`, `SREM`, `SISMEMBER`, `SMEMBERS` |
| Hash | `HSET`, `HGET`, `HDEL`, `HGETALL` |
| Sorted Set | `ZADD`, `ZRANGE`, `ZREM` |

22 commands total, each its own class implementing `Command`, each explicitly declaring whether it's a write (`isWrite()`) for AOF-logging purposes.

---

## Deep Dive: Data Structures

Every Redis type maps to a specific concurrent Java structure, chosen for how it's actually accessed — not "whichever collection is closest to the textbook description."

### Strings — `ConcurrentHashMap<String, StoredValue>`

The core keyspace. `StoredValue` is an immutable record: `(Object value, Long expireAtMillis, RedisType type)`. Why `ConcurrentHashMap` over `synchronized Map`: it uses bucket-level locking / CAS internally, so reads are effectively lock-free and writes only contend when two threads hit the *same bucket* — not a single lock guarding the whole map. `SET`/`GET` are O(1) average.

### Lists — `ConcurrentLinkedDeque<String>`

A genuinely lock-free deque. `LPUSH`/`RPUSH`/`LPOP`/`RPOP` are O(1) with **zero explicit locking** — the structure's own CAS-based implementation handles concurrent pushes/pops from either end safely. `LRANGE` is O(n) to snapshot the deque (no random access) plus O(k) to slice — real Redis's own list range is comparably O(S+N).

### Sets — `ConcurrentHashMap.newKeySet()`

A thread-safe `Set<String>` for free, same underlying guarantees as the main keyspace map. O(1) average for `SADD`/`SREM`/`SISMEMBER`.

### Hashes — `ConcurrentHashMap<String, String>`

Same reasoning as the top-level store, one level down (field → value instead of key → value).

### Sorted Sets — the interesting one

Needs **two things at once**: O(1)-ish score lookup by member, and ordered iteration by score for range queries. Real Redis solves this internally with a hash table (member→score) *plus* a skip list (ordered by score) — and this project mirrors that exactly:

```java
class SortedSetValue {
    private final Map<String, Double> scoresByMember = new ConcurrentHashMap<>();
    private final ConcurrentSkipListSet<ScoredMember> orderedByScore = new ConcurrentSkipListSet<>();
    // ScoredMember orders by (score, then member) — same tie-break real Redis uses
}
```

Because these two structures must move together on every update, writes to *one ZSET's own instance* are `synchronized` — but that lock is scoped to that single sorted set, never global. `ZADD`/`ZREM` are O(log n).

**Stated limitation, not hidden**: `ZRANGE` walks the skip list from the front to reach an arbitrary start index — **O(n)**, not O(log n). Real Redis's skip list carries "span" counters per level specifically to support O(log n) rank access (jump straight to the k-th element). Building that augmented skip list would be the natural next step — a strong follow-up to bring up unprompted in an interview rather than something to be caught out on.

### Type safety across the whole keyspace

Every `StoredValue` carries a `RedisType` tag. Calling a list command against a key holding a string throws `WrongTypeException` — matching real Redis's `WRONGTYPE` error exactly. Storage operations are split into four interfaces (`ListOperations`, `SetOperations`, `HashOperations`, `SortedSetOperations`) — the same split Spring Data Redis itself uses — so each `Command` class only depends on the interface it actually needs.

**Empty containers delete their key.** Popping the last element of a list (or removing the last member of a set/hash/zset) removes the key entirely, matching real Redis — an empty list is not the same thing as no list.

---

## Deep Dive: TTL & Expiration

Two independent mechanisms, combined — the standard, correct answer to "how would you expire cache entries efficiently":

**Lazy expiration**: every read path (`get`, `exists`, `ttl`, `delete`, and all typed reads) checks `expireAtMillis <= now` and reaps the key on the way out if stale. Guarantees a client is never handed stale data — but alone, it **leaks memory** for keys that expire and are never read again.

**Active expiration**: a background job (`ttl.ExpirationScheduler`, `@Scheduled(fixedDelay = 100)` — matching real Redis's actual 10×/sec default) reclaims those orphaned keys. It does **not** scan the whole keyspace. It samples up to 20 keys *only from a side-index of keys that currently have a TTL* (`keysWithExpiry`), reaps expired ones, and — matching real Redis's own algorithm precisely — **repeats immediately if more than 25% of the sample was expired** (up to 5 rounds), on the theory that expired keys cluster in time.

```java
for (round in 0..5) {
    sample = random 20 keys from keysWithExpiry
    expiredCount = reap expired ones in sample
    if (expiredCount < sample.size() * 0.25) break
}
```

`EXPIRE` uses `ConcurrentHashMap.computeIfPresent` for atomic read-modify-write — closing the race window a naive `get()`-then-`put()` would leave open, and correctly returning "no-op" if the key was concurrently found to be already expired.

---

## Deep Dive: Networking — TCP Server & RESP Protocol

### Single-threaded NIO event loop, deliberately

`TcpServer` uses a `Selector`-based non-blocking event loop: **one thread** handles accept/read/parse/execute/write for every connected client. This mirrors real Redis's own concurrency model on purpose: no locking is needed around command execution on this path, because a command runs to completion before the loop looks at the next ready channel — two clients' commands can *never* interleave mid-execution. The honest cost (also true of real Redis): one slow command blocks every other client until it finishes.

### Dual protocol support — one implementation, not two

Real Redis servers accept both a RESP multibulk array *and* a plain newline-terminated "inline command" (a real, documented feature — not a simplification invented here). Detection is by first byte: `*` means RESP, anything else means inline. Both paths funnel into the exact same tokens.

### The actual hard part: partial reads

TCP doesn't preserve message boundaries. A command can arrive split across multiple `read()` calls, or several commands can arrive in one `read()`. The **one correctness rule** the whole parser exists to enforce:

> Never consume bytes from the buffer unless a complete command is confirmed present.

```java
// RequestParser.parseResp — simplified
if (not enough bytes for the full multibulk array yet) {
    return Optional.empty();   // buffer is untouched — safe to retry after more bytes arrive
}
buffer.delete(0, consumedLength);   // only now, once we're certain, do we advance
return Optional.of(tokens);
```

Get this wrong and a partial read corrupts framing for every command after it. A nice side effect of doing this correctly: because the connection handler keeps calling the parser while the buffer still holds a complete command, **pipelining works for free** — multiple commands sent in one packet all get executed and replied to, with zero extra code.

### Reply encoding — RESP is not "just add quotes"

`RespReplyEncoder` distinguishes reply *types* precisely: `+OK\r\n` (simple status) vs `$2\r\nOK\r\n` (bulk string) look almost identical but mean different things on the wire — a `GET` whose value happens to literally be `"OK"` must never be encoded as a status line. This is why `SetCommand` returns a dedicated `SimpleStringReply("OK")` marker instead of a raw string — the encoder branches on type, not on value.

### Stated simplifications

- Connection data is treated as UTF-8 text, not raw binary. Real Redis bulk strings are fully binary-safe (arbitrary bytes, including nulls); this implementation assumes single-byte-per-character content.
- Writes use a simple blocking-retry loop instead of registering for `OP_WRITE` and resuming on write-readiness. Fine for small, single-reply payloads; a server handling large replies or slow clients would need real backpressure handling to avoid busy-spinning.

---

## Deep Dive: Persistence — AOF & Snapshots

Two independent, switchable strategies (`redis.persistence.mode = aof | snapshot | none`), implemented correctly in isolation rather than one half-integrated hybrid.

### AOF (append-only file)

Every successful **write** command is appended to a log, RESP-encoded — the same wire format real Redis's own AOF has used since v7. This isn't incidental: it means replay reuses the *exact same* `RequestParser` from the TCP server. That parser's core correctness rule (never consume an incomplete record) was built to survive a command split across TCP packets — and it turns out to solve **"gracefully handle a truncated AOF from a mid-write crash" for free**, with zero new code. A genuinely malformed record mid-file (not just a truncated tail) stops replay entirely, matching real Redis's own default behavior of refusing to guess past corruption.

**Where AOF logging lives**: not inside any `Command` — that would violate separation of concerns. Instead, `PersistingCommandDispatcher` *decorates* `CommandExecutor` behind the shared `CommandDispatcher` interface: run the real command first, and only if it **succeeds** *and* `Command.isWrite()` is true, append it. `Command` implementations have zero awareness this exists.

**Fsync policy** (`redis.aof.fsync = always | everysec | no`), matching real Redis's own three options with the *exact* trade-off each represents:

| Policy | Guarantee | Cost |
|---|---|---|
| `ALWAYS` | Survives a full power loss — bytes are physically on disk before the write returns | A disk sync on every single write |
| `EVERYSEC` (default) | Bounds data loss to ~1 second, regardless of write volume | Negligible — one background flush/sec |
| `NO` | Survives *our process* crashing (bytes are already handed to the kernel) | Does **not** survive an OS crash or power loss before the kernel flushes dirty pages |

That `NO` distinction — survives process crash, not OS crash — is the kind of precise detail that separates a real answer from a hand-wavy one.

### Snapshots (RDB-style)

A periodic full dump of the keyspace, written to a **temp file then atomically renamed** into place — the same trick real Redis's RDB save uses, so a crash mid-write can never corrupt the previous good snapshot; a reader only ever sees the old complete file or the new complete file.

**Stated trade-off vs AOF**: a snapshot's data-loss window is "however long since the last snapshot" (minutes) vs AOF's "at most the fsync interval" (~1 second). In exchange, snapshotting has effectively zero per-command overhead.

**Why AOF and snapshot aren't combined**: real Redis's combined mode (RDB baseline + AOF rewrite/compaction layered on top) requires coordinating AOF-rewrite timing with snapshot timing — genuinely hard. Treating them as mutually exclusive, switchable strategies was judged more valuable than a half-correct integration.

### A bug worth naming, not hiding

`SET key value EX 60` is logged **verbatim**. Replaying it at restart recomputes the expiry as *replay-time + 60s* — silently **resetting the TTL window** instead of preserving the original expiry moment. Real Redis avoids this by rewriting relative expiries to absolute timestamps (`PEXPIREAT`) before logging. Fixing it here would mean giving the persistence decorator command-specific rewriting knowledge, conflicting with keeping commands persistence-unaware — so it's documented as a known limitation rather than silently patched around. **This is a genuinely good thing to bring up unprompted in an interview** — it shows you found a real correctness issue by reasoning about replay semantics, not just by testing the happy path.

---

## Deep Dive: Eviction & Memory Management

### No global lock for LRU/LFU — on purpose

The textbook LRU answer is a doubly-linked list + hashmap under one shared lock — and every read *and* write would need to mutate that shared structure. That directly contradicts every other design decision in this project (every structure so far was chosen specifically to avoid a shared-lock bottleneck).

Instead: recency (`AtomicLong` per key) and frequency (`LongAdder` per key) are tracked as **independent per-key atomics** — touching one key's counter never contends with any other key's. Eviction samples a handful of random keys (`EVICTION_SAMPLE_SIZE = 5`, matching real Redis's actual `maxmemory-samples` default) and evicts whichever scores worst in the sample.

**This is not a shortcut relative to real Redis — it's what real Redis actually does.** Redis doesn't maintain true LRU order either, for the identical reason: exact ordering under concurrent access is expensive, and random-sample approximation is nearly free and good enough in practice.

```java
// simplified
sample = 5 random keys from the whole keyspace
victim = the one with the lowest recorded access time (LRU) or count (LFU)
evict(victim)
```

A key that's never been touched scores as "oldest possible" (`Long.MIN_VALUE`) — a sensible default, and the one code path that's genuinely hard to exercise through normal commands (since `SET` itself records an access — writing is a form of using a key). It's actually reachable through **snapshot restore**, which rebuilds the keyspace directly without touching the access trackers — a freshly-restored key legitimately has zero LRU history.

### `noeviction` is the default — matching real Redis

A lot of people assume Redis evicts by default. It doesn't. When the limit is hit under `noeviction`, a write that would add a new key fails with the exact real Redis error text: `OOM command not allowed when used memory > 'maxmemory'`.

### Stated limitation: key-count, not real memory

`redis.max-keys` is a **proxy** for actual memory usage — real Redis enforces true byte-level accounting via its allocator. The JVM doesn't expose anything that cheap or precise, so key-count stands in, explicitly labeled as such rather than disguised as real memory tracking.

---

## Deep Dive: Concurrency Design

(Full detail in `docs/concurrency.md`.)

| Structure | Used for | Why not a lock |
|---|---|---|
| `ConcurrentHashMap` | keyspace, hash fields, sets, TTL/access indexes | Bucket-level locking/CAS — writes only contend on the same bucket |
| `ConcurrentLinkedDeque` | Lists | Genuinely lock-free CAS-based linked list |
| `ConcurrentSkipListSet` | Sorted set ordering | Lock-free skip list, safe concurrent iteration |
| `AtomicLong` / `LongAdder` | LRU timestamps, LFU counts, eviction count | Per-key atomics — no cross-key contention |

**Where locks genuinely exist, and why they're narrow:**
- `SortedSetValue` (`synchronized`): its hash-table and ordered structure must move together — the lock is scoped to *that one ZSET's own instance*, never global.
- `AofWriter` (`synchronized`): file-channel operations aren't documented as safe for unsynchronized concurrent callers.

**The one real, named contention point**: every other structure in this project is scoped per-key. `AofWriter` is the exception — when AOF is enabled, *every* write command, regardless of which key it touched, serializes through one shared file-append call. This is a genuine bottleneck under high concurrent write load, and it's not a design flaw to apologize for — real Redis's own AOF has the identical property, for the identical reason (one shared log file). Naming your own system's real bottleneck, unprompted, is a stronger interview moment than waiting to be asked "where's the contention?"

**One deliberately-accepted race**: eviction's capacity check (`enforceCapacityBeforeInsertingNewKey`) is check-then-act, *not* atomic with the insert that follows it — done to avoid nesting an eviction's `data.remove()` inside another key's `data.compute()` callback, which `ConcurrentHashMap`'s own documentation warns against. Under heavy concurrent inserts, key count could transiently drift slightly past `maxKeys`. Consistent with the approximate philosophy used throughout, and documented rather than hidden.

---

## Key Design Decisions, Consolidated

| Decision | What was chosen | Why over the obvious alternative |
|---|---|---|
| Storage locking | `ConcurrentHashMap` + per-type concurrent structures | A single global lock would serialize every operation regardless of which key was touched |
| Command dispatch | Transport-agnostic `Command`/`CommandExecutor`, built before it was "needed" | Let TCP (Phase 4) and AOF (Phase 5) bolt on without touching 22 command classes |
| Expiration | Lazy + active (sampled), not one or the other | Lazy alone leaks memory; active-only (full scan) wastes CPU |
| ZSET internals | Dual hash-table + skip list, per-key lock | Mirrors real Redis; avoids a global ZSET lock |
| TCP concurrency | Single-threaded event loop | Matches real Redis's own model — no locking needed on the hot path |
| AOF format | RESP, not custom text | Free reuse of the exact same parser for replay + corruption handling |
| Persistence hook point | Decorator around dispatch, not inside commands | Keeps `Command` classes fully unaware persistence exists |
| Eviction | Random sampling + independent atomics, not a shared LRU list | Avoids a global lock; matches real Redis's actual approach, not a compromise |
| AOF vs snapshot | Mutually exclusive strategies | Combining them correctly (AOF rewrite) is genuinely hard; two correct isolated implementations beat one half-integrated hybrid |

---

## Known Limitations (Stated Honestly)

Being able to list these unprompted, with the *correct* fix named, is worth more in an interview than pretending they don't exist:

1. **`ZRANGE` is O(n)**, not O(log n) — needs a rank-augmented ("span-counter") skip list to fix.
2. **AOF replay resets relative TTLs** — needs absolute-timestamp rewriting (`PEXPIREAT`-equivalent) before logging.
3. **Not binary-safe** — connection data is UTF-8 text; real Redis bulk strings can hold arbitrary bytes.
4. **`MSET`-equivalent multi-key writes aren't atomic across keys** (n/a until Phase 7, but the reasoning already applies to any future multi-key command) — true atomicity would need a lock broader than anything else in the codebase.
5. **Snapshot isn't a true point-in-time view** — it copies the live map directly rather than using copy-on-write `fork()` like real Redis's RDB save.
6. **Java serialization for snapshots** — simple and correct here, but not cross-language portable or resilient to code changes, unlike Redis's own versioned binary RDB format.
7. **`max-keys` is a key-count proxy**, not real memory accounting.
8. **A brief startup race** exists between Spring's embedded Tomcat (starts during context refresh) and persistence recovery completing — a REST request could theoretically arrive before recovery finishes.
9. **AOF writes serialize globally** through one file lock — the one real contention point in an otherwise per-key-scoped system.

---

## Interview Q&A — Mapped to This Codebase

**"How would you design a cache with expiration?"**
→ Lazy expiration (check on read) alone leaks memory for untouched expired keys. Active-only (background full scan) wastes CPU. I implemented both: lazy for correctness, plus a sampled background sweep (20 random keys from an index of *only* keys with a TTL, repeating if >25% were expired) — see `InMemoryStore.runActiveExpirationCycle`.

**"Design an LRU cache."**
→ The textbook answer is a doubly-linked list + hashmap under one lock. I deliberately didn't build that, because it reintroduces the exact shared-lock bottleneck every other structure in this project avoids. Instead: independent per-key atomic timestamps, and eviction samples a handful of random keys and picks the worst — the same technique real Redis's `maxmemory-samples` uses. Trade exact ordering for near-zero contention.

**"How do you handle a TCP message that arrives split across multiple reads?"**
→ Never consume bytes from the buffer until a complete message is confirmed present. See `RequestParser` — this single rule is what makes RESP parsing (and, for free, AOF corruption recovery) correct.

**"What's the difference between AOF and RDB persistence, and their trade-offs?"**
→ AOF logs every write; bounded data loss (~1s on `everysec`), higher steady-state overhead. Snapshot/RDB dumps the whole keyspace periodically; near-zero overhead between snapshots, but a bigger data-loss window. Combining them well (AOF rewrite on top of an RDB baseline) is genuinely hard — I implemented both correctly as switchable strategies rather than one half-integrated hybrid.

**"How would you implement a sorted set / leaderboard?"**
→ You need both O(1)-ish score lookup by member *and* ordered range queries. One structure can't efficiently give you both — real Redis (and this project) uses two coordinated structures: a hash table for lookup, a skip list for order.

**"Where are the locks in your system, and why there?"**
→ Two places, both narrow: a ZSET's own dual-structure update (locked per-ZSET-instance, not globally), and AOF file appends (globally serialized, because there's one shared log file — the one real bottleneck in the system, and I can name exactly why).

**"How do you avoid a race condition in a check-then-act operation, like 'set a TTL only if the key exists'?"**
→ `ConcurrentHashMap.computeIfPresent` — the check and the mutation happen as one atomic step per key, rather than a `get()` then a separate `put()` with a window in between.

**"What would you benchmark first in this system?"**
→ Not an abstract "TCP vs REST" number — specifically, AOF-enabled vs AOF-disabled write throughput under concurrent load, since that's the one place a shared lock is known to exist (see `docs/concurrency.md`).

---

## Testing Strategy

- **Storage layer**: exhaustive per-type unit tests (happy path, empty-key behavior, `WRONGTYPE` cross-type checks, empty-container deletion), plus dedicated concurrency tests (concurrent writes to distinct keys, concurrent writes to the same key).
- **Protocol layer**: `RequestParserTest` simulates a request arriving across multiple chunks, pipelined commands in one buffer, and malformed input — all without a real socket. `RespReplyEncoderTest` covers every reply type, including the "OK"-as-bulk-string-vs-status-line distinction.
- **Networking**: `TcpServerIntegrationTest` opens genuine sockets against the actual running server (bound to an OS-assigned ephemeral port), verifying inline commands, RESP round-trips, pipelining, and error replies end-to-end.
- **Persistence**: write→replay round trips including values with spaces, a missing file, a genuinely truncated record, and a genuinely malformed record — verifying "stop, don't guess" behavior precisely.
- **Eviction**: tests deliberately keep `maxKeys` ≤ the sample size (5), which makes sampling examine *every* key deterministically — turning what could have been a flaky, randomness-dependent suite into a reliable one, without changing any production code to achieve it.

---

## Project Structure

```
redis-java/
├── src/main/java/com/example/redis/
│   ├── RedisApplication.java
│   ├── controller/CommandController.java
│   ├── server/            TcpServer, ClientConnection
│   ├── protocol/          RequestParser, RespReplyEncoder, RespCommandEncoder
│   ├── command/           Command, CommandDispatcher, CommandExecutor
│   ├── command/impl/      22 command classes
│   ├── command/util/      Arguments (shared parsing helpers)
│   ├── storage/           InMemoryStore + typed operation interfaces + Snapshottable
│   ├── persistence/       AofWriter, AofReader, PersistingCommandDispatcher, SnapshotWriterScheduler, PersistenceRecoveryRunner
│   ├── ttl/                ExpirationScheduler
│   ├── model/              SimpleStringReply, CommandResponse
│   ├── exception/           RedisException hierarchy
│   └── config/              GlobalExceptionHandler
├── src/test/java/...        mirrors main, ~106+ tests
├── docs/concurrency.md       full concurrency design writeup
└── test-tcp.ps1               zero-dependency Windows PowerShell TCP test client
```

---

## Configuration Reference

```properties
# Networking
redis.tcp.port=6380

# Persistence: aof | snapshot | none
redis.persistence.mode=aof
redis.aof.path=data/appendonly.aof
redis.aof.fsync=everysec            # always | everysec | no
redis.snapshot.path=data/dump.snapshot
redis.snapshot.interval-ms=300000

# Eviction
redis.maxmemory-policy=noeviction   # noeviction | allkeys-lru | allkeys-lfu
redis.max-keys=0                    # 0 = unlimited
```

---

## Roadmap (Not Yet Built)

Phases 7–10 per the original project plan: `MULTI`/`EXEC`/`DISCARD`/`WATCH` transactions and `INCR`/`DECR`/`MSET`/`MGET` (Phase 7), performance benchmarking with real numbers (Phase 8), distributed sharding/replication (Phase 9), and production polish — metrics, structured logging, Docker (Phase 10). `evictedKeyCount()` and `isWrite()` were already added ahead of time specifically so Phase 10's metrics work and Phase 7's transaction/AOF interaction have less to retrofit.

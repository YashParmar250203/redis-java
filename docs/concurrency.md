# Concurrency Design

This document is the running answer to "walk me through the thread-safety of this system" — every concurrent structure used, why it was chosen over a lock, where locks genuinely do exist and why they're scoped narrowly, and where real contention lives.

## Who calls into the store concurrently

Two independent concurrency models feed the same `InMemoryStore`:

- **REST** runs on Spring's embedded Tomcat thread pool — genuinely multi-threaded, many requests can call into the store at the same instant.
- **TCP** (Phase 4) is a single-threaded NIO event loop — only one command executes at a time *within that path*, by design (see `server.TcpServer` javadoc).

Both paths share one `InMemoryStore` instance, so **the store itself must be safe for real concurrent multi-threaded access** even though the TCP path alone wouldn't require it. Every design choice below is justified for the harder (REST) case.

## Concurrent structures used, and why

| Structure | Used for | Why not a lock |
|---|---|---|
| `ConcurrentHashMap` | main keyspace, hash fields, sets, TTL/access-tracking side-indexes | Bucket-level locking / CAS internally — reads are effectively lock-free, writes only contend on the same bucket, not globally |
| `ConcurrentLinkedDeque` | Lists | Genuinely lock-free (CAS-based linked list) — `LPUSH`/`RPUSH`/`LPOP`/`RPOP` never block |
| `ConcurrentSkipListSet` | Sorted set ordering | Lock-free skip list; safe concurrent iteration for `ZRANGE` |
| `AtomicLong` / `LongAdder` | LRU timestamps, LFU counters, eviction count | Per-key/per-counter atomics — one key's access never contends with another's |

The unifying theme: **pick a structure whose own concurrency guarantees match the access pattern**, rather than wrapping a plain collection in one shared lock. This is true from Phase 1's `ConcurrentHashMap` choice through Phase 6's eviction tracking.

## Where locks genuinely exist, and why they're narrow

Two places in this codebase use `synchronized`, and both are deliberately scoped to the smallest thing that needs it:

- **`SortedSetValue`** (Phase 3): a ZSET's hash-table (member→score) and ordered structure (score→member) must move together, or a reader could observe them disagreeing mid-update. The lock is on *that one sorted set's own instance* — writes to `leaderboard` never block writes to `other-leaderboard`. Reads aren't synchronized at all.
- **`AofWriter`** (Phase 5): file-channel operations aren't documented as safe for unsynchronized concurrent callers, so appends are serialized. This one *is* global in effect — see "the one real contention point" below.

Everywhere else, "locking strategy" is really "structure selection" — the absence of an explicit lock is the design decision, not an oversight.

## Atomic operations and the TOCTOU problem

Several operations are check-then-act by nature (does this key exist? is it the right type? does it need creating?) and use `ConcurrentHashMap.compute`/`computeIfPresent` specifically to make that atomic per key:

- `getOrCreate` (Phase 3): "does this key exist, is it the right type, create if absent" — one atomic step, not a `containsKey` + `put` race.
- `expire` (Phase 2): "does this key exist and is it live, if so update its TTL" — same pattern.

**One explicitly accepted exception:** eviction's capacity check (`enforceCapacityBeforeInsertingNewKey`, Phase 6) is check-then-act *without* full atomicity — it's called before, not inside, the map operation that follows, specifically to avoid nesting a `data.remove()` (eviction) inside another key's `data.compute()` callback, which `ConcurrentHashMap`'s own documentation warns against. Under heavy concurrent inserts this could transiently let key count drift slightly past `maxKeys`, or trigger an eviction that (in hindsight) wasn't quite necessary. This is consistent with the approximate philosophy used everywhere expiration/eviction sampling appears in this project, not a bug to be embarrassed about.

## The one real contention point: AOF writes

Every other structure in this project is scoped per-key — a write to `foo` never blocks a write to `bar`. **`AofWriter` is the exception.** When AOF persistence is enabled, every write command, regardless of which key it touched, serializes through the same `synchronized` file-append call, because there's one shared log file. This is a genuine bottleneck under high concurrent write load — and it's worth naming explicitly rather than letting the per-key theme elsewhere imply everything is contention-free.

This isn't unique to this implementation: real Redis's own AOF has the identical property for the identical reason (one file). It's an inherent cost of "durable, ordered, single log," not a design flaw to fix by adding more locks — the actual fix (if this became a real bottleneck) is architectural: batching multiple commands into fewer file writes, or moving to a segmented/sharded log, both of which are non-trivial enough to be their own project phase rather than a quick patch here.

## Read/write contention summary

| Path | Blocks other reads? | Blocks other writes (same key)? | Blocks writes (different key)? |
|---|---|---|---|
| String/Hash/Set GET-style reads | No | No | No |
| List/ZSET reads | No | No (deque/skip-list are lock-free) | No |
| ZSET writes (`ZADD`/`ZREM`) | No | Yes (same ZSET's own lock) | No |
| AOF append (any write command) | N/A | Yes, globally | **Yes, globally** |
| Eviction sampling | No (reads `data.keySet()` snapshot) | No | Momentarily, only for the removed victim key |

## What a "Phase 8 benchmark" would actually measure here

Given the structure above, the highest-value benchmark isn't "TCP vs REST throughput" in the abstract — it's **AOF-enabled vs AOF-disabled write throughput under concurrent load**, since that's the one place a shared lock is known to exist. A secondary useful comparison: single-threaded TCP command execution latency vs REST's multi-threaded latency under the same workload, to make concrete the trade-off described in `server.TcpServer`'s javadoc rather than leaving it as a claim.

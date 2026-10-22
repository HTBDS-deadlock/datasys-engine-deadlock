# Transactions Design

Design decisions for `DELETE` and multi-`COPY` support (Exercises 7-9). These answer the questions
from "1. Design before code" before any of it is implemented. See `docs/storage-design.md` for the
Part 1 rationale this builds on.

## 1. Versioning

Each table's `TableSchema` carries its own `version` field (an int), incremented by one on every
committed `COPY` or `DELETE` against that table. The counter lives entirely **in memory** for the
duration of the session — no `catalog.json` write happens on each commit.

Concretely: `StorageEngine` keeps its existing `Map<String, TableSchema> tables` as the
"current version" pointer (unchanged for existing callers), plus a new
`Map<String, NavigableMap<Integer, TableSchema>> versionHistory` that every commit also writes into,
keyed by table name and then version number.

This is deliberately the cheapest, riskiest of the three options the exercise lists ("metadata held
in memory only, flushed when the engine exits"). We accept that tradeoff because `./engine` is not a
long-running daemon: every invocation (`-c` or `-f`) is its own JVM process that starts, does its
work, and exits — so "flush on close" already happens at the end of every single invocation, not
only once at the end of some long session. The real risk window is narrower than it first sounds:
it is confined to a single `-f script.sql` call that contains multiple statements. If the JVM dies
between statement 3 and statement 10 of one script, the in-memory-only effects of statements 1-9 are
lost even though each individually succeeded. We accept this as a scope limitation of this exercise.

`createTable` and `copyFile` keep all of their existing logic unchanged (building the in-memory
`TableSchema`, writing partition bytes to the data file) — only the `saveCatalog()` call currently
made *inside* each of them is removed. In its place, `StorageEngine` gets one new public `close()`
method (the class implements `AutoCloseable`) that performs the single `catalog.json` write for the
session. `Engine.main` calls it once, after the last statement has run, in the same place its
`finally` block already sits.

## 2. Commit

**In-JVM atomicity**: committing a version is a single reference swap — the table's entry in `tables`
is reassigned to the newly built `TableSchema` in one map `put`. Under our no-concurrency assumption,
no reader can observe a half-updated table.

**On-disk atomicity**: deferred to engine close (see §1). At that point, every table's current
(highest-version) `TableSchema` is serialized and written to a new temporary file, which is then
atomically renamed over `catalog.json` (`Files.move(..., StandardCopyOption.ATOMIC_MOVE)`), relying on
POSIX's guarantee that `rename()` is atomic. This still protects the final save itself against a torn
write, even though it is the only disk write of the session.

## 3. Snapshot

A statement pins the table's last committed version **at statement start** — inside
`Executor.execute(statement)`, before `binder.bind(statement)` is even called — not when its `Plan` is
built. `Executor` captures a reference to the table's current `TableSchema` at that point and threads
it through `Binder`/`Planner`, rather than `Planner` re-querying the live `StorageEngine` map multiple
times during planning as it does today. Under our current no-concurrency model this has no observable
effect yet, but it establishes the correct pin point once concurrent statements exist.

## 4. Retention

Every version created during a session is kept in memory for the session's whole lifetime, in
`versionHistory` (§1). Nothing is evicted mid-session; only the single newest version per table is
ever persisted to disk, at close (§1, §2). We accept the resulting memory growth as a scope
limitation of this exercise rather than designing reference counting or a running-statement list — a
more correct but more involved solution we are deliberately deferring.

If something requests a version number that was never created, the engine throws an error rather than
silently returning the nearest available version.

## 5. The rewrite set

`DELETE`'s rewrite set reuses the same pruning helper `Planner` already uses for `SELECT`:
`StorageEngine.canPrune(comparison, constant, min, max)`. For a `DELETE ... WHERE predicate`, the
rewrite set is exactly the partitions `canPrune` cannot rule out for that predicate — the same
"surviving partitions" computation `Planner.plan` already performs for a `SELECT` with that predicate.
No separate prune logic is written for `DELETE`; both call the one method already living on
`StorageEngine`.

Pruning is conservative: a partition in the rewrite set *might* contain a match, but reading it may
reveal that none of its rows actually do (a false positive at the partition level). For a rewrite-set
partition where every row survives the predicate (no real match), we carry the original
`PartitionInfo` over into the new version unchanged — no physical copy is written. Only a partition
with at least one row that must actually be removed is physically rewritten: read it, keep the rows
that do not match the `DELETE` predicate, and write those into a new partition.

## 6. New version

A `DELETE` creates a new version only if at least one partition in the rewrite set is physically
rewritten (i.e., had a real match). If every rewrite-set partition turns out to have zero matches,
every partition is carried over unchanged and the table's version does not advance.

## 7. Partition placement

New partitions — from a `DELETE`'s rewrite, or from a second `COPY` into an existing table — go at the
end of the table's single existing data file, reusing `writePartition`'s append pattern. This requires
`copyFile` to stop unconditionally writing the magic-bytes/format-version header and truncating the
file (`raf.setLength(0)`) on every call: the header is written once, when the file is first created; a
later `COPY` against a table that already has a data file instead opens it, seeks to the end, and
appends new partitions after the existing ones.

# Transactions Design

Design decisions for `DELETE` and multi-`COPY` support (Exercises 7-9). These answer the questions
from "1. Design before code" before any of it is implemented. See `docs/storage-design.md` for the
Part 1 rationale this builds on.

## 1. Versioning

Each table's `TableSchema` carries its own `version` field (an int), incremented by one on every
committed `COPY` or `DELETE` against that table. The current version is persisted in `catalog.json`
at engine close, so a later `./engine` invocation continues the table's version number; merely
starting a new process does not increment it. During one invocation, intermediate versions live
entirely **in memory** — no `catalog.json` write happens on each commit.

Concretely: `StorageEngine` keeps a concurrent `Map<String, TableSchema> tables` as the current
version pointer, plus a concurrent `Map<String, ConcurrentNavigableMap<Integer, TableSchema>>
versionHistory`. On startup, the persisted current version is loaded into both maps. Each commit
adds its immutable schema snapshot to the history and then publishes it as the current entry in
`tables`. A snapshot is a reference to one `TableSchema`, whose partition metadata is not mutated
after publication.

This is deliberately the cheapest, riskiest of the three options the exercise lists ("metadata held
in memory only, flushed when the engine exits"). Each `./engine -c ...` or `./engine -f ...`
invocation is one process/run; its intermediate version history is discarded at exit, while the
latest version remains persisted for the next run. If the process crashes before close, catalog
changes from that invocation are lost, although any already-appended partition bytes are harmless
orphans. This is an accepted scope limitation of this exercise.

The concurrent maps make current-version publication and version lookup safe for concurrent readers.
Exercise 8's first-committer-wins validation serializes DML commits and rejects a writer whose pinned
base version is no longer current.

`createTable`, `copyFile`, and `delete` build and publish in-memory `TableSchema` versions without
writing `catalog.json` per statement. `StorageEngine.close()` performs the single catalog write for
the invocation. `Engine.run` calls it after the last statement through try-with-resources, including
when a statement fails.

## 2. Commit

**In-JVM atomicity**: a writer records the version it pinned as its base version. At commit,
`StorageEngine` synchronizes validation and publication: if the current version differs from the base,
the writer logs `conflict table=... base=... current=...` and throws `ConflictException`. Otherwise it
records the completed schema in version history and publishes it as current. This is first-committer-
wins; the losing statement cannot publish its schema, and any partition bytes it already wrote are
orphaned.

Partition appends to the shared table data file are separately serialized by a storage write lock so
concurrent writers cannot interleave their bytes. The append lock is released before commit
validation, allowing writers based on the same snapshot to reach commit and detect the conflict.
`close()` synchronizes on the commit monitor while saving the final catalog, so catalog serialization
does not overlap a metadata commit.

For deterministic concurrency tests, the package-private `beforeCommit` hook runs immediately before
commit validation. It defaults to a no-op; tests can park one writer there while another commits, with
latches that have bounded timeouts. The hook runs outside the commit monitor so the competing writer
can finish its commit.

**On-disk atomicity**: deferred to engine close (see §1). At that point, every table's current
(highest-version) `TableSchema` is serialized and written to a new temporary file, which is then
atomically renamed over `catalog.json` (`Files.move(..., StandardCopyOption.ATOMIC_MOVE)`), relying on
POSIX's guarantee that `rename()` is atomic. This still protects the final save itself against a torn
write, even though it is the only disk write of the session.

This exercise serializes commits within one `StorageEngine` instance. It does not provide conflict
detection between separate JVM invocations writing the same data directory.

## 3. Snapshot

A statement that targets an existing table pins its last committed `TableSchema` **at statement
start** — inside `Executor.execute(statement)`, before `binder.bind(statement)` is called. The
snapshot reference is passed through binding and execution. For `SELECT`, `Planner` uses only this
snapshot for schema, statistics, and partition selection, and each scan reads partitions from the
same snapshot rather than looking the table up again in the live map. `COPY` and `DELETE` also build
from the pinned schema. `CREATE TABLE` has no prior table version to pin.

## 4. Retention

Every version created during one engine invocation is kept in memory for that invocation's whole
lifetime, in `versionHistory` (§1). Nothing is evicted mid-run; only the single newest version per
table is persisted to disk at close (§1, §2). Old snapshots from earlier invocations are not
available after restart; startup seeds history with the latest persisted version. Retaining every
version in memory through process exit avoids reference counting or tracking active statements,
because no statement from that process can outlive it.

If something requests a version number that is not retained in the current invocation, the engine
throws an error rather than silently returning the nearest available version.

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

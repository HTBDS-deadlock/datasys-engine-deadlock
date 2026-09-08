# Storage Design
Design decisions for the Exercise 2 storage core (`StorageEngine`, catalog, and binary
data format). These decisions apply to `createTable`, `copyFile`, and `select` as
specified in the exercise.

## 1. Catalog storage
One catalog file, `catalog.json`, stored at the root of the data directory (the `Path`
passed into the `StorageEngine` constructor). We chose a single file over one-per-table
because our workload is not designed for concurrent multi-table writers or massive
parallelism, so there's no benefit from splitting it. JSON was chosen over Java
properties or a custom binary catalog format because it's human-readable, easy to debug
by hand during development, and the exercise explicitly allows a JSON library for the
catalog. The catalog is small enough (schema + partition metadata, not row data) that
loading it entirely into memory on startup is inexpensive.

## 2. Catalog contents
Per table, the catalog stores:

- The table name.
- The schema: an ordered list of `(column name, column type)` pairs, matching
  `ColumnSpec` order.
- The list of data belonging to the table, and for each file, its partitions.
- Per partition: the byte offset of its data within the file, and per-column
  min/max statistics.

## 3. Where the min/max summaries live
In the catalog only. Since the catalog is already fully loaded into memory at startup.
keeping min/max stats there means partition pruning during `select` is a
pure in-memory lookup — no data-file I/O is needed to decide whether a partition can be
skipped, matching how Snowflake and Iceberg prune. The tradeoff is that a data file is
no longer self-describing which is acceptable here because `StorageEngine` is the only 
reader and writer of these files; no other tool needs to interpret them independently, 
and `StorageEngine` always reads the catalog before reaching any data file.

## 4. Restart
A fresh `StorageEngine` constructed on an existing data directory reads `catalog.json`
in full and reconstructs in memory: the set of known tables, each table's schema, its
list of data files and partitions, and each partition's min/max statistics. No data
files are read at construction time — only the catalog. Data files are opened lazily,
only for partitions that survive pruning during a `select`.

## 5. Layout inside a partition
We use a PAX (Partition Attributes Across) layout: rows within a partition are
grouped into mini row-groups, and within each mini row-group, values are stored
column-by-column (DMS Style - contiguous per-column runs), rather than a single 
pure row-wise partition or a single pure columnar partition.

This is a deliberate and we justify it as follows: PAX is the layout DuckDB itself uses
internally (its "storage philosophy"), and since this engine is explicitly modeled on
DuckDB's semantics we chose to mirror that storage layout too rather than pick a pure form that
we'd likely have to move away from later. Given that min/max pruning happens 
entirely in the catalog the cost is only actually reading a partition once it's selected — 
and PAX is at least as good as pure columnar for that, at the cost of slightly more 
implementation complexity than either pure option.

## 6. Partition size
Default: `maxRowsPerPartition = 65536` (64k rows), configurable per `StorageEngine`
instance. This system isn't designed for massively
parallel scanning or billion-row datasets — our expected workloads are moderate in
size and don't need a high degree of intra-scan parallelism. Smaller partitions improve
pruning granularity and parallelism opportunities, but increase catalog metadata volume
and the number of small I/O operations. Given our workload assumptions, a larger
default partition size reduces metadata volume and favors sequential read throughput;
the parameter stays configurable so this can be revisited if workloads change.

## 7. Value encodings and framing
- `LONG`: 8-byte two's-complement integer.
- `DOUBLE`: 8-byte IEEE 754 floating point.
- `STRING`: length-prefixed UTF-8 byte sequence — a 4-byte length prefix followed by
  that many UTF-8-encoded bytes. A length prefix is used instead of a delimiter so
  string values can contain arbitrary bytes without needing escaping.
- Fixed-width encoding for `LONG`/`DOUBLE` means a column value's offset within a
  partition's column chunk can be computed directly as `index * rowWidth`, without
  needing a separate per-value offset table.
- Every data file begins with magic bytes and a format version number, so a reader can
  fail fast on a corrupted or foreign file before trusting anything else in it.
- Partition and column-chunk locations are **not** stored as an in-file table of
  contents; they're resolved entirely through the catalog (§3), which already acts as
  the index into the data files. This keeps the writer single-pass: partitions are
  flushed as they fill, and their file offsets are recorded in the catalog rather than
  requiring the writer to seek back and patch a header or footer.

## 8. Byte order
Big-endian. This matches `ByteBuffer`'s default in Java, which simplifies encoding and
decoding code (no explicit `order(...)` calls needed), and follows common convention in
portable binary formats — even though the machines this runs on are little-endian.

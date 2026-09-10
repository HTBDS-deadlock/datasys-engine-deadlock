# Storage Design
Design decisions for the Exercise 2 storage core (StorageEngine, catalog, and binary
data format). These decisions apply to createTable, copyFile, and select as
specified in the exercise.

## 1. Catalog storage
One catalog file, catalog.json, stored at the root of the data directory (the Path
passed into the StorageEngine constructor). We chose a single file over one-per-table
because our workload is not designed for concurrent multi-table writers or massive
parallelism, so there's no benefit from splitting it. JSON was chosen over Java
properties or a custom binary catalog format because it's human-readable, easy to debug
by hand during development, and the exercise explicitly allows a JSON library for the
catalog. The catalog is small enough (schema + partition metadata, not row data) that
loading it entirely into memory on startup is inexpensive.

## 2. Catalog contents
Per table, the catalog stores:

- The table name.
- The schema: an ordered list of (column name, column type) pairs, matching
  ColumnSpec order.
- The table's data file and its partitions.
- Per partition: the byte offset of its data within the file, and per-column
  min/max statistics.

## 3. Where the min/max summaries live
In the catalog only. Since the catalog is already fully loaded into memory at startup.
keeping min/max stats there means partition pruning during select is a
pure in-memory lookup where no data-file I/O is needed to decide whether a partition can be
skipped, matching how Snowflake and Iceberg prune. The tradeoff is that a data file is
no longer self-describing which is acceptable here because StorageEngine is the only
reader and writer of these files; no other tool needs to interpret them independently,
and StorageEngine always reads the catalog before reaching any data file.

## 4. Restart
A fresh StorageEngine constructed on an existing data directory reads catalog.json in full and reconstructs in memory: the set of known tables, each table's schema, its data file, its partitions, and each partition's min/max statistics.

## 5. Layout inside a partition
We chose PAX because it is the layout used internally by DuckDB, which this project is inspired by, so it seemed like the most natural choice. Rows are stored in small groups, but values within each group are written column-by-column. This gives some of the benefits of a column-oriented layout while still keeping related rows together. In our implementation each partition is divided into mini-groups of 8 rows. The choice of 8 is mainly an implementation decision rather than a performance-tuned parameter. A small fixed group size keeps the code simple and the memory footprint low while still preserving the key idea behind PAX.

## 6. Partition size
The default partition size is 65,536 rows (64k), but it can be configured when constructing a StorageEngine. Larger partitions reduce the amount of metadata stored in the catalog and avoid creating many small reads. Smaller partitions would give more precise pruning, but would increase catalog size and management overhead. Since we expect datasets used in this project to be relatively small, we chose a larger default size.

## 7. Value encodings and framing
- `LONG`: 8-byte two's-complement integer.
- `DOUBLE`: 8-byte IEEE 754 floating point.
- `STRING`: length-prefixed UTF-8 byte sequence — a 4-byte length prefix followed by
  that many UTF-8-encoded bytes. A length prefix is used instead of a delimiter so
  string values can contain arbitrary bytes without needing escaping.
- LONG and DOUBLE use fixed-width encodings, which keeps serialization and deserializatio
  straightforward
- Every data file begins with magic bytes and a format version number, so a reader can
  fail fast on a corrupted or foreign file before trusting anything else in it.
- Partition and column-chunk locations are not stored as an in-file table of
  contents; they're resolved entirely through the catalog (§3), which already acts as
  the index into the data files. This keeps the writer single-pass: partitions are
  flushed as they fill, and their file offsets are recorded in the catalog rather than
  requiring the writer to seek back and patch a header or footer.

## 8. Byte order
We use big-endian byte order because it is the default used by ByteBuffer in Java. This avoids having to explicitly specify a byte order when encoding and decoding values.

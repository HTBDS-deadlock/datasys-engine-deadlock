# Storage Design

## 1. Catalog storage
One catalog file, `catalog.json`, at the root of the data directory (the `Path`
passed to the `StorageEngine` constructor). A single file is enough because the
engine has no concurrent writers. JSON is human-readable, easy to debug by hand,
and the exercise allows a JSON library for the catalog. The catalog holds only
metadata, not rows, so it is loaded fully into memory at startup.

## 2. Catalog contents
Per table, keyed by table name:

- The columns: an ordered list of (name, type), in `ColumnSpec` order.
- The data file name (`<table>.bin`), or `null` before `COPY`.
- The partitions, each with its byte offset in the data file, its row count,
  and the min/max of every column.

Min/max values are stored as strings in the JSON and converted to the column's
type (`toSchema`) once when the catalog is loaded, so queries compare typed values.

## 3. Where the min/max summaries live
In the catalog only. Since the catalog is already in memory, the Planner can
decide which partitions to skip without opening the data file, similar to how
Snowflake and Iceberg prune. The tradeoff is that a data file is not
self-describing, which is acceptable because `StorageEngine` is the only reader
and writer of these files and always reads the catalog first.

## 4. Restart
A new `StorageEngine` on an existing data directory reads `catalog.json` and
rebuilds in memory every table's columns, data file, partitions and min/max
statistics. The catalog is saved after every `CREATE TABLE` and `COPY`.

## 5. Layout inside a partition
PAX: each partition is split into groups of 1,024 rows, and within a group the
values are written column by column. This is a PAX-style layout similar to the
row groups DuckDB uses. A column's values lie together, which makes scanning one
column cheap, while all values of a row stay in the same group, which makes
rebuilding a row cheap. Because columns are stored as separate blocks, a scan
skips the columns a query does not use instead of decoding them. The group size
is an implementation choice, not a tuned parameter.

## 6. Partition size
The default is 10,240 rows (10 groups of 1,024), configurable through the
`StorageEngine` constructor. `COPY` writes a partition each time that many rows
have been read, and the remaining rows form a smaller last partition. Smaller
partitions give more precise pruning but more catalog entries; larger
partitions read more unneeded rows at the edge of a range. The original default
was 65,536; our experiment on 2 million rows sorted by distance found 10,000 and
12,500 fastest, and 10,240 was chosen as the nearest multiple of the group size.

## 7. Value encodings and framing
- `LONG`: 8-byte two's-complement integer.
- `DOUBLE`: 8-byte IEEE 754 floating point.
- `STRING`: a 4-byte length followed by that many UTF-8 bytes, so strings can
  contain any character without escaping.
- Each data file starts with an 8-byte header: the magic bytes `PAX1` and a
  4-byte format version (1). The header is checked on every read, so a corrupt
  or foreign file fails fast. The first partition therefore starts at byte 8.
- Each group starts with a 4-byte row count, followed by one block per column.
- There is no table of contents in the file; partition offsets are stored in
  the catalog (§3). This keeps `COPY` single-pass: partitions are written as
  they fill, without seeking back to patch a header or footer.

## 8. Byte order
Big-endian, the default for both `ByteBuffer` and `RandomAccessFile` in Java,
so no byte order has to be set explicitly when encoding and decoding.

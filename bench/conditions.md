# Benchmark conditions

| | |
|---|---|
| Machine | Intel Core i7-12700H (14 cores / 20 threads), 15.7 GB RAM |
| OS | Windows 11 Home, run from Git Bash |
| JVM | OpenJDK 25.0.2+10-69, 64-Bit Server VM, G1 GC |
| Heap | JVM default: initial 252 MB, max 4.0 GB (no `-Xmx`, `ENGINE_JAVA_OPTS` unset) |
| Commit | 74bacb5 (engine code; the benchmark commit on top adds only `bench/`) |
| Data | `bench/generate_joinbench.py`, seed 42: 1,000 customers, 100 cities, 100,000 orders, 100,000 shipments |
| Partitions | `DEFAULT_MAX_ROWS_PER_PARTITION` = 10,240 (21 partitions in total) |
| Runs | 5 per configuration; every run is a new `./engine` process (cold JVM, warm OS file cache) |
| Output | stdout redirected to `/dev/null`; `durationMs` from the `statement=SELECT` log line |

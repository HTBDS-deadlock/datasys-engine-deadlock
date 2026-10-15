# Deadlock

A small SQL database engine written in Java by Team Deadlock (ITU, Data Systems).
It parses SQL with ANTLR, validates and plans each statement, and executes it with
iterator-style (Volcano) operators over its own binary storage format: a JSON
catalog plus one PAX-laid-out data file per table, split into partitions with
per-column min/max statistics used for partition pruning.

Design rationale: [docs/storage-design.md](docs/storage-design.md)

## Build and run

Requires JDK 25 and Maven. Run from the project root (in Git Bash on Windows).

```bash
mvn package -DskipTests

./engine -c "CREATE TABLE trips (city STRING, distance LONG, price DOUBLE);
COPY trips FROM 'src/test/resources/trips.csv';"

./engine -c "SELECT city, price FROM trips WHERE distance < 100"
./engine -f q.sql             # run a script file
```

`SELECT` rows are printed to stdout as CSV; logs go to stderr and `logs/engine.log`.
Tables are stored in `./data` and persist between runs; delete it to start over.
Building without `-DskipTests` runs the tests, which delete `./data`.

## Tests

```bash
mvn test      # unit tests
mvn verify    # unit + integration tests (*IT), as run by CI
```

## Conventions

- Only `Engine` writes to stdout; everything else logs through SLF4J as `key=value`.
- Only `StorageEngine` reads and writes the catalog and data files.
- Unit tests end in `Test`, integration tests in `IT`; use `@TempDir` for files.
- Pull requests follow the template and must pass CI (`mvn -B verify`).

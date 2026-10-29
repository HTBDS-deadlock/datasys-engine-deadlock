package dk.itu.datasys;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Exercises 7-9's required test suite: see the exercise's "Required tests"
 * section. Parser, printer, and binder coverage for DELETE live alongside
 * the existing SELECT tests in SqlParserSmokeTest, SqlPrinterTest, and
 * BinderTest.
 */
class TransactionsTests {

    private static final List<ColumnSpec> TRIPS_COLUMNS = List.of(
            new ColumnSpec("city", ColumnType.STRING),
            new ColumnSpec("distance", ColumnType.LONG),
            new ColumnSpec("price", ColumnType.DOUBLE));

    private static final String GOLDEN_CSV = """
            Copenhagen,12,23.5
            Aarhus,187,301.0
            Odense,95,120.75
            Copenhagen,140,210.0
            Aalborg,210,340.5
            Roskilde,31,45.0
            Copenhagen,88,99.99
            Esbjerg,299,450.25
            """;

    private static Path writeGoldenCsv(Path dir) throws IOException {
        Path csv = dir.resolve("trips.csv");
        Files.writeString(csv, GOLDEN_CSV);
        return csv;
    }

    // ------------------------------------------------------------------
    // 3. Versions: CREATE, COPY, COPY, DELETE produce versions 0 through 3; a
    // SELECT after each commit sees that commit's rows.
    // ------------------------------------------------------------------

    @Test
    void createCopyCopyDeleteProduceVersionsZeroThroughThree(@TempDir Path dir) throws IOException {
        Path csv = writeGoldenCsv(dir);
        StorageEngine engine = new StorageEngine(dir);

        engine.createTable("trips", TRIPS_COLUMNS);
        assertEquals(0, engine.currentVersion("trips"));
        assertEquals(0, engine.select("trips", "distance", Comparison.GREATER_THAN, -1L).size());

        CopyResult firstCopy = engine.copyFile("trips", csv.toString());
        assertEquals(1, firstCopy.version());
        assertEquals(1, engine.currentVersion("trips"));
        assertEquals(8, engine.select("trips", "distance", Comparison.GREATER_THAN, -1L).size());

        CopyResult secondCopy = engine.copyFile("trips", csv.toString());
        assertEquals(2, secondCopy.version());
        assertEquals(2, engine.currentVersion("trips"));
        assertEquals(16, engine.select("trips", "distance", Comparison.GREATER_THAN, -1L).size());

        DeleteResult delete = engine.delete("trips",
                Optional.of(new Predicate("distance", Comparison.GREATER_THAN, 200L)));
        assertEquals(3, delete.version());
        assertEquals(3, engine.currentVersion("trips"));
        // each of the two copies had one row with distance > 200 (210) and one with
        // distance > 200 (299) -- 4 rows removed in total, 12 remain
        assertEquals(12, engine.select("trips", "distance", Comparison.GREATER_THAN, -1L).size());
    }

    // ------------------------------------------------------------------
    // 4. Rewrite set: sorted golden data with maxRowsPerPartition = 2; DELETE
    // with a selective predicate rewrites exactly the partitions that can
    // match, and the carried partitions keep their offsets in the catalog.
    // ------------------------------------------------------------------

    @Test
    void deleteRewritesExactlyThePartitionsThatCanMatch(@TempDir Path dir) throws IOException {
        // 4 partitions of 2 rows each, sorted by distance:
        // P0 [12,31] P1 [88,95] P2 [140,187] P3 [210,299]
        String sortedByDistance = """
                Copenhagen,12,23.5
                Roskilde,31,45.0
                Copenhagen,88,99.99
                Odense,95,120.75
                Copenhagen,140,210.0
                Aarhus,187,301.0
                Aalborg,210,340.5
                Esbjerg,299,450.25
                """;
        Path csv = dir.resolve("trips_sorted.csv");
        Files.writeString(csv, sortedByDistance);

        // distance > 180 can only match P2 (max=187) and P3 (max=299); P0 and P1
        // are pruned outright and must be carried over unchanged.
        long p0OffsetBefore;
        long p1OffsetBefore;
        try (StorageEngine engine = new StorageEngine(dir, 2)) {
            engine.createTable("trips", TRIPS_COLUMNS);
            engine.copyFile("trips", csv.toString());
        }
        JsonNode before = readPartitions(dir, "trips");
        p0OffsetBefore = before.get(0).get("byteOffset").asLong();
        p1OffsetBefore = before.get(1).get("byteOffset").asLong();

        DeleteResult result;
        try (StorageEngine engine = new StorageEngine(dir, 2)) {
            result = engine.delete("trips", Optional.of(new Predicate("distance", Comparison.GREATER_THAN, 180L)));
        }

        // P2 loses its one matching row (187) and keeps 140; P3 loses both its rows
        // (210, 299) and is dropped entirely -- both are rewritten.
        assertEquals(3, result.rowsDeleted());
        assertEquals(2, result.partitionsRewritten());
        assertEquals(2, result.partitionsCarried());

        JsonNode after = readPartitions(dir, "trips");
        assertEquals(3, after.size()); // P0, P1, and the rewritten P2 -- P3 had nothing left to carry
        assertEquals(p0OffsetBefore, after.get(0).get("byteOffset").asLong());
        assertEquals(p1OffsetBefore, after.get(1).get("byteOffset").asLong());
    }

    private static JsonNode readPartitions(Path dir, String tableName) throws IOException {
        JsonNode root = new ObjectMapper().readTree(dir.resolve("catalog.json").toFile());
        return root.path("tables").path(tableName).path("partitions");
    }

    // ------------------------------------------------------------------
    // 5. Restart: a fresh engine on the same data directory sees the
    // post-DELETE version.
    // ------------------------------------------------------------------

    @Test
    void freshEngineOnRestartSeesThePostDeleteVersion(@TempDir Path dir) throws IOException {
        Path csv = writeGoldenCsv(dir);
        try (StorageEngine engine = new StorageEngine(dir)) {
            engine.createTable("trips", TRIPS_COLUMNS);
            engine.copyFile("trips", csv.toString());
            engine.delete("trips", Optional.of(new Predicate("distance", Comparison.GREATER_THAN, 200L)));
        }

        try (StorageEngine restarted = new StorageEngine(dir)) {
            assertEquals(2, restarted.currentVersion("trips")); // 0 create, 1 copy, 2 delete
            List<Object[]> rows = restarted.select("trips", "distance", Comparison.GREATER_THAN, -1L);
            assertEquals(6, rows.size()); // 8 loaded, minus 210 and 299
            assertEquals(3, restarted.copyFile("trips", csv.toString()).version());
        }

        try (StorageEngine restartedAgain = new StorageEngine(dir)) {
            assertEquals(3, restartedAgain.currentVersion("trips"));
        }
    }

    @Test
    void retainedSnapshotReadsItsVersionAfterANewerCommit(@TempDir Path dir) throws IOException {
        Path csv = writeGoldenCsv(dir);
        try (StorageEngine engine = new StorageEngine(dir)) {
            engine.createTable("trips", TRIPS_COLUMNS);
            engine.copyFile("trips", csv.toString());
            StorageEngine.TableSnapshot copiedVersion = engine.snapshot("trips", 1);

            SelectStatement select = new SelectStatement("trips", Optional.empty(), Optional.empty());
            new Binder(engine).bind(select, Optional.of(copiedVersion));
            Plan plan = new Planner(engine).plan(select, copiedVersion);
            Operator scan = plan.root();
            scan.open();

            engine.delete("trips",
                    Optional.of(new Predicate("distance", Comparison.GREATER_THAN, 200L)));

            int rowsFromPinnedVersion = 0;
            while (scan.next() != null) {
                rowsFromPinnedVersion++;
            }
            scan.close();

            assertEquals(8, rowsFromPinnedVersion);
            assertEquals(6, engine.select("trips", "distance", Comparison.GREATER_THAN, -1L).size());
            assertThrows(IllegalArgumentException.class, () -> engine.snapshot("trips", 99));
        }
    }

    @Test
    void parkedDeleteLosesToCopyAndCanBeRetried(@TempDir Path dir) throws Exception {
        Path csv = writeGoldenCsv(dir);
        Path additionalCsv = dir.resolve("additional.csv");
        Files.writeString(additionalCsv, "Herning,50,75.0\n");
        CountDownLatch parked = new CountDownLatch(1);
        CountDownLatch winnerDone = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try (StorageEngine engine = new StorageEngine(dir)) {
            engine.createTable("trips", TRIPS_COLUMNS);
            engine.copyFile("trips", csv.toString());
            int logLinesBefore = logLineCount();
            Executor sqlExecutor = new Executor(engine);
            engine.beforeCommit = () -> {
                parked.countDown();
                await(winnerDone);
            };

            Future<?> loser = executor.submit(() -> sqlExecutor.run("DELETE FROM trips WHERE distance > 200;"));
            assertTrue(parked.await(5, TimeUnit.SECONDS), "DELETE did not reach the pre-commit hook");

            engine.beforeCommit = () -> {
            };
            sqlExecutor.run("COPY trips FROM '" + additionalCsv + "';");
            winnerDone.countDown();

            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> loser.get(5, TimeUnit.SECONDS));
            assertInstanceOf(ConflictException.class, failure.getCause());
            assertConflictLogUsesDistinctWriterThreads(logLinesBefore,
                    "statement=COPY table=trips rows=1 partitions=1 version=2");
            assertEquals(2, engine.currentVersion("trips"));
            assertEquals(9, engine.select("trips", "distance", Comparison.GREATER_THAN, -1L).size());

            DeleteResult retry = engine.delete("trips",
                    Optional.of(new Predicate("distance", Comparison.GREATER_THAN, 200L)));
            assertEquals(3, retry.version());
            assertEquals(7, engine.select("trips", "distance", Comparison.GREATER_THAN, -1L).size());
        } finally {
            winnerDone.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void parkedCopyLosesToCopyAndCanBeRetried(@TempDir Path dir) throws Exception {
        Path baseCsv = writeGoldenCsv(dir);
        Path loserCsv = dir.resolve("loser.csv");
        Path winnerCsv = dir.resolve("winner.csv");
        Files.writeString(loserCsv, "LoserCopy,50,75.0\n");
        Files.writeString(winnerCsv, "WinnerCopy,60,85.0\n");
        CountDownLatch parked = new CountDownLatch(1);
        CountDownLatch winnerDone = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try (StorageEngine engine = new StorageEngine(dir)) {
            engine.createTable("trips", TRIPS_COLUMNS);
            engine.copyFile("trips", baseCsv.toString());
            int logLinesBefore = logLineCount();
            Executor sqlExecutor = new Executor(engine);
            engine.beforeCommit = () -> {
                parked.countDown();
                await(winnerDone);
            };

            Future<?> loser = executor.submit(() -> sqlExecutor.run("COPY trips FROM '" + loserCsv + "';"));
            assertTrue(parked.await(5, TimeUnit.SECONDS), "COPY did not reach the pre-commit hook");

            engine.beforeCommit = () -> {
            };
            sqlExecutor.run("COPY trips FROM '" + winnerCsv + "';");
            winnerDone.countDown();

            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> loser.get(5, TimeUnit.SECONDS));
            assertInstanceOf(ConflictException.class, failure.getCause());
            assertConflictLogUsesDistinctWriterThreads(logLinesBefore,
                    "statement=COPY table=trips rows=1 partitions=1 version=2");
            assertEquals(2, engine.currentVersion("trips"));
            assertEquals(9, engine.select("trips", "distance", Comparison.GREATER_THAN, -1L).size());
            assertTrue(hasCity(engine, "WinnerCopy"));
            assertFalse(hasCity(engine, "LoserCopy"));

            CopyResult retry = engine.copyFile("trips", loserCsv.toString());
            assertEquals(3, retry.version());
            assertEquals(10, engine.select("trips", "distance", Comparison.GREATER_THAN, -1L).size());
            assertTrue(hasCity(engine, "WinnerCopy"));
            assertTrue(hasCity(engine, "LoserCopy"));
        } finally {
            winnerDone.countDown();
            executor.shutdownNow();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for the competing commit");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for the competing commit", e);
        }
    }

    private static int logLineCount() throws IOException {
        Path logFile = Path.of("logs", "engine.log");
        return Files.exists(logFile) ? Files.readAllLines(logFile, StandardCharsets.UTF_8).size() : 0;
    }

    private static void assertConflictLogUsesDistinctWriterThreads(int firstNewLine, String winningCopy)
            throws IOException {
        List<String> allLines = Files.readAllLines(Path.of("logs", "engine.log"), StandardCharsets.UTF_8);
        List<String> newLines = allLines.subList(firstNewLine, allLines.size());
        String conflict = newLines.stream()
                .filter(line -> line.contains(",ERROR,") && line.contains("conflict table=trips"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing ERROR log for the losing writer"));
        String winner = newLines.stream()
                .filter(line -> line.contains(",DEBUG,Executor," + winningCopy))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing winning writer log"));

        assertEquals(7, conflict.split(",", 7).length);
        assertEquals(7, winner.split(",", 7).length);
        assertNotEquals(conflict.split(",", 7)[3], winner.split(",", 7)[3],
                "The losing and winning writer should have different thread IDs");
    }

    private static boolean hasCity(StorageEngine engine, String city) {
        return engine.select("trips", "distance", Comparison.GREATER_THAN, -1L).stream()
                .anyMatch(row -> city.equals(row[0]));
    }

    @Test
    void staleCopyConflictsWithoutPublishingAndCanBeRetried(@TempDir Path dir) throws IOException {
        Path goldenCsv = writeGoldenCsv(dir);
        Path additionalCsv = dir.resolve("additional.csv");
        Files.writeString(additionalCsv, "Herning,50,75.0\n");

        try (StorageEngine engine = new StorageEngine(dir)) {
            engine.createTable("trips", TRIPS_COLUMNS);
            StorageEngine.TableSnapshot staleSnapshot = engine.snapshot("trips");

            engine.copyFile("trips", goldenCsv.toString());

            ConflictException conflict = assertThrows(ConflictException.class,
                    () -> engine.copyFile(staleSnapshot, additionalCsv.toString()));
            assertEquals("conflict table=trips base=0 current=1", conflict.getMessage());
            assertEquals(1, engine.currentVersion("trips"));
            assertEquals(8, engine.select("trips", "distance", Comparison.GREATER_THAN, -1L).size());

            CopyResult retry = engine.copyFile("trips", additionalCsv.toString());
            assertEquals(2, retry.version());
            assertEquals(9, engine.select("trips", "distance", Comparison.GREATER_THAN, -1L).size());
        }
    }

    // ------------------------------------------------------------------
    // 6. Integration: one script with CREATE, COPY, COPY, DELETE, SELECT
    // through ./engine -f returns the rows the two COPY statements loaded
    // minus the ones DELETE removed.
    // ------------------------------------------------------------------

    // Engine.main hardcodes its data directory to "data" under the working
    // directory (the module root when run via Maven), matching Week4Tests'
    // own handling of the real front door.
    private static final Path REAL_DATA_DIRECTORY = Path.of("data");

    @Test
    void frontDoorScriptWithDeleteReturnsRemainingRows(@TempDir Path scriptDirectory) throws IOException {
        deleteRecursively(REAL_DATA_DIRECTORY);
        try {
            Path script = scriptDirectory.resolve("q.sql");
            Files.writeString(script, """
                    CREATE TABLE trips (city STRING, distance LONG, price DOUBLE);
                    COPY trips FROM 'src/test/resources/trips.csv';
                    COPY trips FROM 'src/test/resources/trips.csv';
                    DELETE FROM trips WHERE distance > 200;
                    SELECT * FROM trips;
                    """);

            String stdout = runEngine("-f", script.toString());

            // the golden CSV has 8 rows, 2 of which (210, 299) are removed by the
            // DELETE; two COPY statements load it twice, so 12 rows remain
            String[] lines = stdout.isEmpty() ? new String[0] : stdout.split(System.lineSeparator());
            assertEquals(12, lines.length);
            for (String line : lines) {
                assertEquals(3, line.split(",", -1).length);
            }
        } finally {
            deleteRecursively(REAL_DATA_DIRECTORY);
        }
    }

    private static String runEngine(String... args) {
        PrintStream originalOut = System.out;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            Engine.main(args);
        } finally {
            System.setOut(originalOut);
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (var paths = Files.walk(path)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            });
        }
    }
}

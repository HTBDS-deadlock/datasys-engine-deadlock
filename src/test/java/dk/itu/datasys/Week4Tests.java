package dk.itu.datasys;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Week 4's required test suite: see the exercise's "Required tests" section.
 */
class Week4Tests {

    private static final List<ColumnSpec> TRIPS_COLUMNS = List.of(
            new ColumnSpec("city", ColumnType.STRING),
            new ColumnSpec("distance", ColumnType.LONG),
            new ColumnSpec("price", ColumnType.DOUBLE));

    // ------------------------------------------------------------------
    // Unit 1: FilterOperator over a stub child
    // ------------------------------------------------------------------

    /**
     * Serves rows from a fixed list; lets FilterOperator be tested without any real
     * Scan/storage.
     */
    private static final class TestListOperator implements Operator {
        private final List<Object[]> rows;
        private Iterator<Object[]> iterator;

        TestListOperator(List<Object[]> rows) {
            this.rows = rows;
        }

        @Override
        public void open() {
            iterator = rows.iterator();
        }

        @Override
        public Object[] next() {
            return iterator.hasNext() ? iterator.next() : null;
        }

        @Override
        public void close() {
        }
    }

    @Test
    void filterOperatorOverStubChildEmitsOnlyMatchingRows() {
        TestListOperator child = new TestListOperator(List.of(
                new Object[] { "Copenhagen", 12L },
                new Object[] { "Aarhus", 187L },
                new Object[] { "Odense", 95L }));
        List<ColumnSpec> schema = List.of(
                new ColumnSpec("city", ColumnType.STRING),
                new ColumnSpec("distance", ColumnType.LONG));
        FilterOperator filter = new FilterOperator(child, schema,
                new Predicate("distance", Comparison.GREATER_THAN, 100L));

        filter.open();
        List<Object[]> rows = drain(filter);
        filter.close();

        assertEquals(1, rows.size()); // only aarhus
        assertArrayEquals(new Object[] { "Aarhus", 187L }, rows.get(0));
    }

    // ------------------------------------------------------------------
    // Unit 2: ScanOperator returns exactly the rows of the partitions it is
    // handed, including the empty-partition-list case
    // ------------------------------------------------------------------

    @Test
    void scanOperatorReturnsExactlyTheRowsOfThePartitionsItIsHanded(@TempDir Path dataDirectory) throws IOException {
        // 4 partitions of 2 rows each; handing it just partition 2 should yield
        // exactly that partition's 2 rows, not all 8.
        StorageEngine engine = sortedTripsEngine(dataDirectory);
        ScanOperator scan = new ScanOperator(engine, "trips", List.of(2));

        scan.open();
        List<Object[]> rows = drain(scan);
        scan.close();

        assertEquals(2, rows.size());
        assertArrayEquals(new Object[] { "Copenhagen", 140L, 210.0 }, rows.get(0));
        assertArrayEquals(new Object[] { "Aarhus", 187L, 301.0 }, rows.get(1));
    }

    @Test
    void scanOperatorWithEmptyPartitionListReadsNothing(@TempDir Path dataDirectory) {
        // partitionNumbers is empty, so ScanOperator never touches the engine at
        // all -- a fully pruned query reads nothing and returns nothing.
        StorageEngine engine = new StorageEngine(dataDirectory);
        ScanOperator scan = new ScanOperator(engine, "trips", List.of());

        scan.open();
        Object[] first = scan.next();
        scan.close();

        assertNull(first);
    }

    // ------------------------------------------------------------------
    // Unit 3 & 4: Planner pruning and plan shape
    // ------------------------------------------------------------------

    private static final String SORTED_BY_DISTANCE = """
            Copenhagen,12,23.5
            Roskilde,31,45.0
            Copenhagen,88,99.99
            Odense,95,120.75
            Copenhagen,140,210.0
            Aarhus,187,301.0
            Aalborg,210,340.5
            Esbjerg,299,450.25
            """;

    private StorageEngine sortedTripsEngine(Path dataDirectory) throws IOException {
        Path csv = dataDirectory.resolve("trips_sorted.csv");
        Files.writeString(csv, SORTED_BY_DISTANCE);

        StorageEngine engine = new StorageEngine(dataDirectory, 2);
        engine.createTable("trips", TRIPS_COLUMNS);
        engine.copyFile("trips", csv.toString());
        return engine;
    }

    @Test
    void plannerKeepsExactlyThePartitionsThatCanMatch(@TempDir Path dataDirectory) throws IOException {
        // 8 rows, maxRowsPerPartition=2 -> 4 partitions: [12,31] [88,95] [140,187]
        // [210,299].
        // distance > 200 can only match the last partition (max=299); the other
        // three all have max <= 200 and must be pruned.
        StorageEngine engine = sortedTripsEngine(dataDirectory);
        Planner planner = new Planner(engine);
        SelectStatement statement = new SelectStatement("trips", Optional.empty(),
                Optional.of(new Predicate("distance", Comparison.GREATER_THAN, 200L)));

        Plan plan = planner.plan(statement);

        assertEquals(new ScanStats(4, 1, 3), plan.stats());
    }

    @Test
    void plannerShapeWithWhereIsFilterOverScan(@TempDir Path dataDirectory) throws IOException {
        StorageEngine engine = sortedTripsEngine(dataDirectory);
        Planner planner = new Planner(engine);
        SelectStatement statement = new SelectStatement("trips", Optional.empty(),
                Optional.of(new Predicate("distance", Comparison.GREATER_THAN, 100L)));

        Plan plan = planner.plan(statement);

        assertInstanceOf(FilterOperator.class, plan.root());
    }

    @Test
    void plannerShapeWithoutWhereIsBareScanOverAllPartitions(@TempDir Path dataDirectory) throws IOException {
        StorageEngine engine = sortedTripsEngine(dataDirectory);
        Planner planner = new Planner(engine);
        SelectStatement statement = new SelectStatement("trips", Optional.empty(), Optional.empty());

        Plan plan = planner.plan(statement);

        assertInstanceOf(ScanOperator.class, plan.root());
        assertEquals(new ScanStats(4, 4, 0), plan.stats());
    }

    // ------------------------------------------------------------------
    // Integration 5: the Exercise 2 integration tests are unchanged and green.
    // Nothing to add here -- see StorageEngineIT, run untouched by this exercise.
    // ------------------------------------------------------------------

    // ------------------------------------------------------------------
    // Integration 6: front door end-to-end
    // ------------------------------------------------------------------

    // Engine.main hardcodes its data directory to "data" under the working
    // directory (the module root when run via Maven), so these tests manage
    // that real directory directly rather than injecting a temp path.
    private static final Path REAL_DATA_DIRECTORY = Path.of("data");

    @BeforeEach
    @AfterEach
    void resetRealDataDirectory() throws IOException {
        if (Files.exists(REAL_DATA_DIRECTORY)) {
            try (var paths = Files.walk(REAL_DATA_DIRECTORY)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.delete(path);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
            }
        }
    }

    private record CapturedOutput(String stdout, String stderr) {
    }

    private CapturedOutput runEngine(String... args) {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            Engine.main(args);
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
        return new CapturedOutput(out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void frontDoorRunsScriptAndProducesExactCsvOnStdout(@TempDir Path scriptDirectory) throws IOException {
        Path script = scriptDirectory.resolve("q.sql");
        Files.writeString(script, """
                CREATE TABLE trips (city STRING, distance LONG, price DOUBLE);
                COPY trips FROM 'src/test/resources/trips.csv';
                SELECT * FROM trips WHERE distance > 100;
                """);

        CapturedOutput output = runEngine("-f", script.toString());

        String expectedCsv = String.join(System.lineSeparator(),
                "Aarhus,187,301.0",
                "Copenhagen,140,210.0",
                "Aalborg,210,340.5",
                "Esbjerg,299,450.25") + System.lineSeparator();
        assertArrayEquals(expectedCsv.getBytes(StandardCharsets.UTF_8),
                output.stdout().getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void frontDoorFailingScriptLeavesStdoutCleanAndReportsOnStderr(@TempDir Path scriptDirectory)
            throws IOException {
        Path script = scriptDirectory.resolve("bad.sql");
        Files.writeString(script, "SELECT * FROM ghost;");

        CapturedOutput output = runEngine("-f", script.toString());

        assertEquals("", output.stdout());
        assertTrue(output.stderr().contains("Unknown table: ghost"));
    }

    private List<Object[]> drain(Operator operator) {
        List<Object[]> rows = new ArrayList<>();
        Object[] row;
        while ((row = operator.next()) != null) {
            rows.add(row);
        }
        return rows;
    }
}

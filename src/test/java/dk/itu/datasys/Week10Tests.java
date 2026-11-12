package dk.itu.datasys;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Exercise 10's required test suite, numbered as in section 7 "Required
 * tests". All tests run over the section 5 testbed (orders, products,
 * customers, cities) in src/test/resources/testbed.
 */
class Week10Tests {

    private static final Path TESTBED = Path.of("src/test/resources/testbed");

    private static final List<ColumnSpec> ORDERS = List.of(
            new ColumnSpec("o_orderkey", ColumnType.LONG),
            new ColumnSpec("o_custname", ColumnType.STRING),
            new ColumnSpec("o_prodname", ColumnType.STRING),
            new ColumnSpec("o_quantity", ColumnType.LONG));
    private static final List<ColumnSpec> PRODUCTS = List.of(
            new ColumnSpec("p_name", ColumnType.STRING),
            new ColumnSpec("p_price", ColumnType.DOUBLE));
    private static final List<ColumnSpec> CUSTOMERS = List.of(
            new ColumnSpec("c_name", ColumnType.STRING),
            new ColumnSpec("c_city", ColumnType.STRING));
    private static final List<ColumnSpec> CITIES = List.of(
            new ColumnSpec("ct_city", ColumnType.STRING),
            new ColumnSpec("ct_country", ColumnType.STRING),
            new ColumnSpec("ct_population", ColumnType.LONG));

    /** Loads the testbed into a fresh engine; each partition holds at most maxRowsPerPartition rows. */
    private static StorageEngine testbedEngine(Path dataDirectory, int maxRowsPerPartition) {
        StorageEngine engine = new StorageEngine(dataDirectory, maxRowsPerPartition);
        engine.createTable("orders", ORDERS);
        engine.createTable("products", PRODUCTS);
        engine.createTable("customers", CUSTOMERS);
        engine.createTable("cities", CITIES);
        for (String table : List.of("orders", "products", "customers", "cities")) {
            engine.copyFile(table, TESTBED.resolve(table + ".csv").toString());
        }
        return engine;
    }

    // ------------------------------------------------------------------
    // 1. Parser: table lists and AND chains parse; column-to-column lands in
    //    joins, column-to-constant in filters; the printer round-trip covers
    //    the new shapes.
    // ------------------------------------------------------------------

    private final SqlParser parser = new SqlParser();
    private final SqlPrinter printer = new SqlPrinter();

    private static final String FOUR_TABLE_SQL = "SELECT * FROM orders, customers, products, cities "
            + "WHERE o_custname = c_name AND o_quantity > 1 AND o_prodname = p_name "
            + "AND c_city = ct_city AND p_price < 500.0;";

    @Test
    void parserSortsJoinsAndFiltersOfAFourTableAndChain() {
        SelectStatement select = (SelectStatement) parser.parse(FOUR_TABLE_SQL).get(0);

        assertEquals(List.of("orders", "customers", "products", "cities"), select.tableNames());
        assertEquals(List.of(
                new JoinPredicate("o_custname", "c_name"),
                new JoinPredicate("o_prodname", "p_name"),
                new JoinPredicate("c_city", "ct_city")), select.joins());
        assertEquals(List.of(
                new Predicate("o_quantity", Comparison.GREATER_THAN, 1L),
                new Predicate("p_price", Comparison.LESS_THAN, 500.0)), select.filters());
    }

    @Test
    void parserTreatsColumnEqualsColumnAsJoinAndColumnEqualsLiteralAsFilter() {
        SelectStatement select = (SelectStatement) parser
                .parse("SELECT * FROM orders, customers WHERE o_custname = c_name AND c_name = 'c_name';").get(0);

        assertEquals(List.of(new JoinPredicate("o_custname", "c_name")), select.joins());
        assertEquals(List.of(new Predicate("c_name", Comparison.EQUALS, "c_name")), select.filters());
    }

    @Test
    void printerRoundTripsFourTableJoinWithFilters() {
        Statement parsed = parser.parse(FOUR_TABLE_SQL).get(0);
        assertEquals(List.of(parsed), parser.parse(printer.print(parsed)));
    }

    @Test
    void printerRoundTripsJoinsOnly() {
        SelectStatement statement = new SelectStatement(List.of("orders", "customers", "products"), List.of(),
                List.of(new JoinPredicate("o_custname", "c_name"), new JoinPredicate("o_prodname", "p_name")));
        assertEquals(List.of(statement), parser.parse(printer.print(statement)));
    }

    // ------------------------------------------------------------------
    // 2. Binder: unknown column, ambiguous column, a join across mismatched
    //    types, and a join whose two sides land in the same table each throw
    //    IllegalArgumentException.
    // ------------------------------------------------------------------

    @Test
    void binderAcceptsTheFourTestbedQueries(@TempDir Path dataDirectory) throws IOException {
        Binder binder = new Binder(testbedEngine(dataDirectory, 1000));
        for (int q = 1; q <= 4; q++) {
            Statement statement = parser.parse(Files.readString(TESTBED.resolve("q" + q + ".sql"))).get(0);
            binder.bind(statement);
        }
    }

    @Test
    void binderRejectsUnknownColumn(@TempDir Path dataDirectory) {
        Binder binder = new Binder(testbedEngine(dataDirectory, 1000));
        assertThrows(IllegalArgumentException.class, () -> binder.bind(new SelectStatement(
                List.of("orders", "customers"), List.of(), List.of(new JoinPredicate("o_custname", "ghost")))));
        assertThrows(IllegalArgumentException.class, () -> binder.bind(new SelectStatement(
                List.of("orders", "customers"), List.of(new Predicate("ghost", Comparison.EQUALS, 1L)),
                List.of(new JoinPredicate("o_custname", "c_name")))));
    }

    @Test
    void binderRejectsColumnNameSharedByTwoTables(@TempDir Path dataDirectory) {
        StorageEngine engine = new StorageEngine(dataDirectory);
        engine.createTable("a", List.of(new ColumnSpec("id", ColumnType.LONG)));
        engine.createTable("b", List.of(new ColumnSpec("id", ColumnType.LONG),
                new ColumnSpec("a_id", ColumnType.LONG)));
        Binder binder = new Binder(engine);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> binder.bind(
                new SelectStatement(List.of("a", "b"), List.of(), List.of(new JoinPredicate("id", "a_id")))));
        assertTrue(e.getMessage().contains("ambiguous"), e.getMessage());
    }

    @Test
    void binderRejectsJoinAcrossMismatchedTypes(@TempDir Path dataDirectory) {
        Binder binder = new Binder(testbedEngine(dataDirectory, 1000));
        // o_quantity is LONG, p_price is DOUBLE.
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> binder.bind(
                new SelectStatement(List.of("orders", "products"), List.of(),
                        List.of(new JoinPredicate("o_quantity", "p_price")))));
        assertTrue(e.getMessage().contains("LONG") && e.getMessage().contains("DOUBLE"), e.getMessage());
    }

    @Test
    void binderRejectsJoinWithBothSidesInTheSameTable(@TempDir Path dataDirectory) {
        Binder binder = new Binder(testbedEngine(dataDirectory, 1000));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> binder.bind(
                new SelectStatement(List.of("orders", "customers"), List.of(),
                        List.of(new JoinPredicate("c_name", "c_city")))));
        assertTrue(e.getMessage().contains("same table"), e.getMessage());
    }

    // ------------------------------------------------------------------
    // 3. Column resolution helper: name to table and to index in the
    //    concatenated row, including the error cases.
    // ------------------------------------------------------------------

    private static final JoinedSchema TESTBED_SCHEMA = new JoinedSchema(
            List.of("orders", "customers", "products", "cities"),
            List.of(ORDERS, CUSTOMERS, PRODUCTS, CITIES));

    @Test
    void resolvesColumnToOwningTable() {
        assertEquals(0, TESTBED_SCHEMA.tableIndexOf("o_orderkey"));
        assertEquals(1, TESTBED_SCHEMA.tableIndexOf("c_city"));
        assertEquals(2, TESTBED_SCHEMA.tableIndexOf("p_price"));
        assertEquals(3, TESTBED_SCHEMA.tableIndexOf("ct_population"));
    }

    @Test
    void resolvesColumnToIndexInConcatenatedRow() {
        // orders(4) ++ customers(2) ++ products(2) ++ cities(3)
        assertEquals(0, TESTBED_SCHEMA.rowIndexOf("o_orderkey"));
        assertEquals(3, TESTBED_SCHEMA.rowIndexOf("o_quantity"));
        assertEquals(4, TESTBED_SCHEMA.rowIndexOf("c_name"));
        assertEquals(5, TESTBED_SCHEMA.rowIndexOf("c_city"));
        assertEquals(7, TESTBED_SCHEMA.rowIndexOf("p_price"));
        assertEquals(8, TESTBED_SCHEMA.rowIndexOf("ct_city"));
        assertEquals(10, TESTBED_SCHEMA.rowIndexOf("ct_population"));
        assertEquals(new ColumnSpec("p_price", ColumnType.DOUBLE), TESTBED_SCHEMA.columnSpec("p_price"));
    }

    @Test
    void resolutionRejectsUnknownColumn() {
        assertThrows(IllegalArgumentException.class, () -> TESTBED_SCHEMA.tableIndexOf("ghost"));
        assertThrows(IllegalArgumentException.class, () -> TESTBED_SCHEMA.rowIndexOf("ghost"));
    }

    @Test
    void resolutionRejectsAmbiguousColumn() {
        List<ColumnSpec> withId = List.of(new ColumnSpec("id", ColumnType.LONG));
        JoinedSchema schema = new JoinedSchema(List.of("a", "b"), List.of(withId, withId));
        assertThrows(IllegalArgumentException.class, () -> schema.tableIndexOf("id"));
        assertThrows(IllegalArgumentException.class, () -> schema.rowIndexOf("id"));
    }

    // ------------------------------------------------------------------
    // 4. HashJoinOperator over TestListOperator stubs: duplicates multiply,
    //    unmatched rows are dropped, empty build side, empty probe side.
    // ------------------------------------------------------------------

    /** Serves rows from a fixed list, so HashJoinOperator runs without any storage. */
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

    private static List<Object[]> rows(Object[]... rows) {
        return List.of(rows);
    }

    @Test
    void duplicatesOnBothSidesMultiply() {
        // 2 build rows and 3 probe rows with key 7: 2 x 3 = 6 output rows.
        Operator build = new TestListOperator(rows(new Object[] { 7L, "b1" }, new Object[] { 7L, "b2" }));
        Operator probe = new TestListOperator(rows(
                new Object[] { "p1", 7L }, new Object[] { "p2", 7L }, new Object[] { "p3", 7L }));

        List<Object[]> out = Operator.drain(new HashJoinOperator(build, probe, 0, 1));

        assertEquals(6, out.size());
        List<String> pairs = new ArrayList<>();
        for (Object[] row : out) {
            assertEquals(4, row.length);
            pairs.add(row[1] + "-" + row[2]);
        }
        pairs.sort(null);
        assertEquals(List.of("b1-p1", "b1-p2", "b1-p3", "b2-p1", "b2-p2", "b2-p3"), pairs);
    }

    @Test
    void unmatchedRowsOnBothSidesAreDropped() {
        Operator build = new TestListOperator(rows(new Object[] { 1L, "one" }, new Object[] { 2L, "two" }));
        Operator probe = new TestListOperator(rows(new Object[] { 2L, "x" }, new Object[] { 3L, "y" }));

        List<Object[]> out = Operator.drain(new HashJoinOperator(build, probe, 0, 0));

        assertEquals(1, out.size());
        assertArrayEquals(new Object[] { 2L, "two", 2L, "x" }, out.get(0));
    }

    @Test
    void emptyBuildSideYieldsNoRows() {
        Operator build = new TestListOperator(List.of());
        Operator probe = new TestListOperator(rows(new Object[] { 1L }, new Object[] { 2L }));
        assertEquals(List.of(), Operator.drain(new HashJoinOperator(build, probe, 0, 0)));
    }

    @Test
    void emptyProbeSideYieldsNoRows() {
        Operator build = new TestListOperator(rows(new Object[] { 1L }, new Object[] { 2L }));
        Operator probe = new TestListOperator(List.of());
        assertEquals(List.of(), Operator.drain(new HashJoinOperator(build, probe, 0, 0)));
    }

    @Test
    void exhaustedJoinKeepsReturningNull() {
        HashJoinOperator join = new HashJoinOperator(new TestListOperator(rows(new Object[] { 1L })),
                new TestListOperator(rows(new Object[] { 1L })), 0, 0);
        join.open();
        assertArrayEquals(new Object[] { 1L, 1L }, join.next());
        assertNull(join.next());
        assertNull(join.next());
        join.close();
    }

    // ------------------------------------------------------------------
    // 5. Planner: a four-table query builds three hash joins, left-deep in
    //    FROM-list order, with filters below the joins; per-table pruning
    //    still visible in ScanStats; an unconnected FROM list is rejected.
    // ------------------------------------------------------------------

    @Test
    void fourTableQueryBuildsThreeLeftDeepHashJoinsWithFiltersBelow(@TempDir Path dataDirectory) {
        StorageEngine engine = testbedEngine(dataDirectory, 1000);
        SelectStatement statement = (SelectStatement) parser.parse(
                "SELECT * FROM orders, customers, products, cities "
                        + "WHERE o_custname = c_name AND o_prodname = p_name AND c_city = ct_city "
                        + "AND c_city = 'Copenhagen';").get(0);

        Plan plan = new Planner(engine).plan(statement);

        // ((orders JOIN customers) JOIN products) JOIN cities: each join's
        // build side is the join so far, its probe side the next FROM table.
        HashJoinOperator joinCities = assertInstanceOf(HashJoinOperator.class, plan.root());
        HashJoinOperator joinProducts = assertInstanceOf(HashJoinOperator.class, joinCities.build());
        HashJoinOperator joinCustomers = assertInstanceOf(HashJoinOperator.class, joinProducts.build());

        assertScanOf("orders", joinCustomers.build());
        // The customers filter sits below the join, directly over the customers scan.
        FilterOperator customersFilter = assertInstanceOf(FilterOperator.class, joinCustomers.probe());
        assertScanOf("customers", customersFilter.child());
        assertScanOf("products", joinProducts.probe());
        assertScanOf("cities", joinCities.probe());

        List<Object[]> rows = Operator.drain(plan.root());
        assertEquals(3, rows.size());
        for (Object[] row : rows) {
            assertEquals(11, row.length); // 4 + 2 + 2 + 3 columns, FROM-list order
            assertEquals("Copenhagen", row[5]);
            assertEquals("Copenhagen", row[8]);
        }
    }

    @Test
    void perTablePruningIsVisibleInScanStats(@TempDir Path dataDirectory) {
        // One row per partition, so a filter can prune individual rows' partitions.
        StorageEngine engine = testbedEngine(dataDirectory, 1);
        SelectStatement statement = (SelectStatement) parser.parse(
                "SELECT * FROM orders, customers, products, cities "
                        + "WHERE o_custname = c_name AND o_prodname = p_name AND c_city = ct_city "
                        + "AND c_city = 'Aarhus' AND p_price > 1000.0;").get(0);

        Plan plan = new Planner(engine).plan(statement);

        // One ScanStats per table, FROM-list order: (total, read, pruned).
        assertEquals(List.of(
                new ScanStats(4, 4, 0),   // orders: no filter
                new ScanStats(3, 1, 2),   // customers: only Bob lives in Aarhus
                new ScanStats(2, 1, 1),   // products: only Bike costs more than 1000
                new ScanStats(2, 2, 0)),  // cities: no filter
                plan.stats());
        // Bob only bought a Helmet, so nothing survives both filters.
        assertEquals(List.of(), Operator.drain(plan.root()));
    }

    @Test
    void plannerRejectsUnconnectedFromList(@TempDir Path dataDirectory) {
        StorageEngine engine = testbedEngine(dataDirectory, 1000);
        // products is never connected to orders or customers.
        SelectStatement statement = new SelectStatement(List.of("orders", "products", "customers"), List.of(),
                List.of(new JoinPredicate("o_custname", "c_name")));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new Planner(engine).plan(statement));
        assertTrue(e.getMessage().contains("products"), e.getMessage());
    }

    private static void assertScanOf(String table, Operator operator) {
        assertInstanceOf(ScanOperator.class, operator, "expected a bare scan of " + table);
        operator.open();
        Object[] first = operator.next();
        operator.close();
        int width = Map.of("orders", 4, "customers", 2, "products", 2, "cities", 3).get(table);
        assertEquals(width, first.length, "scan width does not match table " + table);
    }

    // ------------------------------------------------------------------
    // 6. Integration: the testbed script through ./engine -f; sorted output
    //    equals the sorted DuckDB output for all four queries.
    // ------------------------------------------------------------------

    // Engine.main hardcodes its data directory to "data" under the working
    // directory, so these tests reset that real directory, as Week4Tests does.
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

    @Test
    void testbedQueriesMatchDuckDbThroughTheFrontDoor() throws IOException {
        assertEquals("", runEngine("-f", TESTBED.resolve("load.sql").toString()));

        int[] expectedRowCounts = { 4, 4, 4, 3 };
        for (int q = 1; q <= 4; q++) {
            String ours = runEngine("-f", TESTBED.resolve("q" + q + ".sql").toString());
            List<String> oursSorted = sortedLines(ours);
            List<String> theirsSorted = sortedLines(Files.readString(TESTBED.resolve("duckdb-q" + q + ".csv")));

            assertEquals(expectedRowCounts[q - 1], oursSorted.size(), "row count of q" + q);
            assertEquals(theirsSorted, oursSorted, "sorted output of q" + q + " differs from DuckDB");
        }
    }

    private static String runEngine(String... args) {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
            Engine.main(args);
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    /** Non-empty lines, line endings normalized, sorted: `sort ours.csv` vs `sort theirs.csv`. */
    private static List<String> sortedLines(String text) {
        return text.lines().map(String::strip).filter(line -> !line.isEmpty()).sorted().toList();
    }
}

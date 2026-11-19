package dk.itu.datasys;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.MDC;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Exercise 11's required tests, numbered as in section 8. Most run over
 * lecture 11's running example (slide 6: 7 orders, 4 customers, 2 products, 3
 * cities), so the expected estimates are the numbers on the slides.
 */
class Week11Tests {

    private static final Path TESTBED = Path.of("src/test/resources/testbed");
    private static final Path LOG_FILE = Path.of("logs/engine.log");

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

    private static final Map<String, String> LECTURE11_CSV = Map.of(
            "orders", """
                    100,Anna,Bike,1
                    101,Bob,Helmet,2
                    102,Carol,Bike,1
                    103,Anna,Helmet,1
                    104,Carol,Bike,1
                    105,Anna,Bike,1
                    106,Dan,Helmet,3
                    """,
            "products", """
                    Bike,2999.0
                    Helmet,299.0
                    """,
            "customers", """
                    Anna,Copenhagen
                    Bob,Aarhus
                    Carol,Copenhagen
                    Dan,Odense
                    """,
            "cities", """
                    Copenhagen,Denmark,1400000
                    Aarhus,Denmark,290000
                    Odense,Denmark,180000
                    """);

    /** Slide 19's query: bike orders of customers in big cities. */
    private static final String SLIDE19_SQL = "SELECT * FROM orders, customers, products, cities "
            + "WHERE o_custname = c_name AND o_prodname = p_name AND c_city = ct_city "
            + "AND ct_population > 1000000 AND p_price > 1000.0;";

    private static StorageEngine engine(Path dataDirectory, int maxRowsPerPartition) {
        StorageEngine engine = new StorageEngine(dataDirectory, maxRowsPerPartition);
        createTables(engine);
        return engine;
    }

    private static void createTables(StorageEngine engine) {
        engine.createTable("orders", ORDERS);
        engine.createTable("products", PRODUCTS);
        engine.createTable("customers", CUSTOMERS);
        engine.createTable("cities", CITIES);
    }

    /** Lecture 11's tables (slide 6), loaded into a fresh engine in dataDirectory/data. */
    private static StorageEngine lecture11Engine(Path dir, int maxRowsPerPartition) throws IOException {
        StorageEngine engine = engine(dir.resolve("data"), maxRowsPerPartition);
        for (Map.Entry<String, String> table : LECTURE11_CSV.entrySet()) {
            Path csv = dir.resolve(table.getKey() + ".csv");
            Files.writeString(csv, table.getValue());
            engine.copyFile(table.getKey(), csv.toString());
        }
        return engine;
    }

    /** Week 10's testbed (src/test/resources/testbed). */
    private static StorageEngine testbedEngine(Path dir) {
        StorageEngine engine = engine(dir.resolve("testbed-data"), 1000);
        for (String table : List.of("orders", "products", "customers", "cities")) {
            engine.copyFile(table, TESTBED.resolve(table + ".csv").toString());
        }
        return engine;
    }

    private final SqlParser parser = new SqlParser();

    private SelectStatement select(String sql) {
        return (SelectStatement) parser.parse(sql).get(0);
    }

    // ------------------------------------------------------------------
    // 1. NDV: a partition with known duplicates yields the exact
    //    per-partition count; the merge helper implements the documented
    //    rule (maximum partition NDV; unknown partitions skipped).
    // ------------------------------------------------------------------

    @Test
    void ndvIsExactPerPartition(@TempDir Path dir) throws IOException {
        // 4 rows per partition: orders 100-103 and 104-106.
        StorageEngine engine = lecture11Engine(dir, 4);
        StorageEngine.TableSnapshot orders = engine.snapshot("orders");

        // Anna, Bob, Carol, Anna | Carol, Anna, Dan
        assertEquals(List.of(3L, 3L), engine.columnNdvs(orders, "o_custname"));
        // 1, 2, 1, 1 | 1, 1, 3
        assertEquals(List.of(2L, 2L), engine.columnNdvs(orders, "o_quantity"));
        // a key: every value distinct
        assertEquals(List.of(4L, 3L), engine.columnNdvs(orders, "o_orderkey"));
        assertEquals(3L, StorageEngine.ndv(List.of("Anna", "Bob", "Anna", "Carol", "Bob")));
    }

    @Test
    void ndvSurvivesRestart(@TempDir Path dir) throws IOException {
        try (StorageEngine engine = lecture11Engine(dir, 4)) {
            assertEquals(List.of(3L, 3L), engine.columnNdvs(engine.snapshot("orders"), "o_custname"));
        }
        try (StorageEngine restarted = new StorageEngine(dir.resolve("data"), 4)) {
            assertEquals(List.of(3L, 3L), restarted.columnNdvs(restarted.snapshot("orders"), "o_custname"));
        }
    }

    @Test
    void tableNdvIsTheMaximumPartitionNdv() {
        assertEquals(3L, Estimator.tableNdv(List.of(3L, 3L)));
        // slide 21: partitions holding 640 and 665 of 1,000 customers
        assertEquals(665L, Estimator.tableNdv(List.of(640L, 665L, 650L)));
        // roll forward: partitions without an NDV are skipped
        assertEquals(4L, Estimator.tableNdv(Arrays.asList(null, 4L)));
        assertEquals(Estimator.UNKNOWN_NDV, Estimator.tableNdv(Arrays.asList(null, null)));
        assertEquals(Estimator.UNKNOWN_NDV, Estimator.tableNdv(List.of()));
    }

    @Test
    void catalogWithoutNdvRollsForwardAsUnknown(@TempDir Path dir) throws IOException {
        lecture11Engine(dir, 1000).close();
        // Make it a v0.10 catalog: no ndv anywhere.
        rewriteCatalog(dir.resolve("data"), stats -> stats.remove("ndv"));

        try (StorageEngine engine = new StorageEngine(dir.resolve("data"))) {
            assertEquals(Arrays.asList((Long) null),
                    engine.columnNdvs(engine.snapshot("orders"), "o_custname"));
            // The planner falls back (section 4.3) and the result is still right.
            List<Object[]> rows = new Executor(engine).run(SLIDE19_SQL).get(0).rows().orElseThrow();
            assertEquals(4, rows.size());
        }
    }

    @Test
    void catalogReaderIgnoresUnknownFields(@TempDir Path dir) throws IOException {
        lecture11Engine(dir, 1000).close();
        // A field a later release might add: the tolerant reader ignores it.
        rewriteCatalog(dir.resolve("data"), stats -> stats.put("histogram", "future"));

        try (StorageEngine engine = new StorageEngine(dir.resolve("data"))) {
            assertEquals(List.of(4L), engine.columnNdvs(engine.snapshot("orders"), "o_custname"));
        }
    }

    private static void rewriteCatalog(Path dataDirectory, java.util.function.Consumer<ObjectNode> perStats)
            throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        Path catalog = dataDirectory.resolve("catalog.json");
        JsonNode root = mapper.readTree(catalog.toFile());
        for (JsonNode table : root.path("tables")) {
            for (JsonNode partition : table.path("partitions")) {
                for (JsonNode stats : partition.path("stats")) {
                    perStats.accept((ObjectNode) stats);
                }
            }
        }
        mapper.writeValue(catalog.toFile(), root);
    }

    // ------------------------------------------------------------------
    // 2. Estimator: hand-made statistics produce the expected numbers for a
    //    base table, a filtered table, and a join.
    // ------------------------------------------------------------------

    @Test
    void baseTableIsTheSumOfSurvivingPartitions() {
        assertEquals(7L, Estimator.baseTable(List.of(7)));
        assertEquals(2048L, Estimator.baseTable(List.of(1024, 1024)));
        assertEquals(0L, Estimator.baseTable(List.of()));
    }

    @Test
    void filterEstimates() {
        // slide 12: 7 / ndv(o_quantity) = 7 / 3 = 2.33 -> 2
        assertEquals(2L, Estimator.filter(7, 3, Comparison.EQUALS));
        // slide 19: cities 3 / 3 = 1; products 2 / 3 = 0.67 -> 1, never 0
        assertEquals(1L, Estimator.filter(3, 3, Comparison.GREATER_THAN));
        assertEquals(1L, Estimator.filter(2, 2, Comparison.GREATER_THAN));
        assertEquals(1L, Estimator.filter(2, 2, Comparison.LESS_THAN));
        // an empty input stays 0
        assertEquals(0L, Estimator.filter(0, 3, Comparison.EQUALS));
        // unknown NDV: Selinger's 1/10
        assertEquals(10L, Estimator.filter(100, Estimator.UNKNOWN_NDV, Comparison.EQUALS));
    }

    @Test
    void joinEstimates() {
        // slide 15: foreign-key joins to a unique key are exact
        assertEquals(7L, Estimator.join(7, 4, 4, 4));
        assertEquals(4L, Estimator.join(4, 3, 3, 3));
        // slide 30: 7 * 1 / max(2, 1) = 3.5 -> 4; 4 * 1 / max(3, 1) = 1.33 -> 1
        assertEquals(4L, Estimator.join(7, 1, 2, 1));
        assertEquals(1L, Estimator.join(4, 1, 3, 1));
        // slide 31: 1 * 7 / max(4, 4) = 1.75 -> 2
        assertEquals(2L, Estimator.join(1, 7, 4, 4));
        // slide 20, the benchmark
        assertEquals(15_037_594L, Estimator.join(100_000, 100_000, 662, 665));
        assertEquals(1_504L, Estimator.join(100_000, 10, 665, 10));
        // unknown NDV: the known side divides alone; both unknown: the smaller side is a key
        assertEquals(200L, Estimator.join(100, 10, Estimator.UNKNOWN_NDV, 5));
        assertEquals(100L, Estimator.join(100, 10, Estimator.UNKNOWN_NDV, Estimator.UNKNOWN_NDV));
    }

    @Test
    void ndvIsClampedToTheFilteredRows() {
        assertEquals(1L, Estimator.clampNdv(3, 1));
        assertEquals(3L, Estimator.clampNdv(3, 7));
        assertEquals(Estimator.UNKNOWN_NDV, Estimator.clampNdv(Estimator.UNKNOWN_NDV, 1));
    }

    // ------------------------------------------------------------------
    // 3. Ordering: hand-made statistics under which the FROM-list order is
    //    the worst; the planner picks the expected order, and never a cross
    //    product while a connected join exists.
    // ------------------------------------------------------------------

    /** Slide 30's statistics, after the filters: products and cities are down to 1 row. */
    private static final List<JoinOrderer.TableEstimate> SLIDE30_TABLES = List.of(
            new JoinOrderer.TableEstimate("orders", 7, Map.of("o_custname", 4L, "o_prodname", 2L)),
            new JoinOrderer.TableEstimate("customers", 4, Map.of("c_name", 4L, "c_city", 3L)),
            new JoinOrderer.TableEstimate("products", 1, Map.of("p_name", 1L)),
            new JoinOrderer.TableEstimate("cities", 1, Map.of("ct_city", 1L)));
    private static final List<JoinOrderer.JoinEdge> SLIDE30_EDGES = List.of(
            new JoinOrderer.JoinEdge(0, "o_custname", 1, "c_name", "o_custname=c_name"),
            new JoinOrderer.JoinEdge(0, "o_prodname", 2, "p_name", "o_prodname=p_name"),
            new JoinOrderer.JoinEdge(1, "c_city", 3, "ct_city", "c_city=ct_city"));

    @Test
    void greedyPicksTheBestOrderWhereTheFromListIsTheWorst() {
        JoinOrderer.JoinOrder fromList = JoinOrderer.inOrder(SLIDE30_TABLES, List.of(0, 1, 2, 3),
                List.of(SLIDE30_EDGES.get(0), SLIDE30_EDGES.get(1), SLIDE30_EDGES.get(2)));
        JoinOrderer.JoinOrder greedy = JoinOrderer.greedy(SLIDE30_TABLES, SLIDE30_EDGES);

        // slide 31: customers, cities, orders, products with estimates 1, 2, 1
        assertEquals(List.of(1, 3, 0, 2), greedy.tables());
        assertEquals(List.of(1L, 2L, 1L), greedy.steps().stream().map(JoinOrderer.Step::estRows).toList());
        // slide 28: cost = the intermediate results; FROM list 7 + 4 = 11 (the worst), greedy 1 + 2 = 3
        assertEquals(11L, cost(fromList));
        assertEquals(3L, cost(greedy));
    }

    @Test
    void plannerChoosesTheSlide31OrderFromRealStatistics(@TempDir Path dir) throws IOException {
        StorageEngine engine = lecture11Engine(dir, 1000);

        Plan optimized = new Planner(engine, JoinMode.OPTIMIZED).plan(select(SLIDE19_SQL));
        Plan syntax = new Planner(engine, JoinMode.SYNTAX).plan(select(SLIDE19_SQL));

        assertEquals(List.of("customers", "cities", "orders", "products"), optimized.joinOrder());
        assertEquals(List.of("orders", "customers", "products", "cities"), syntax.joinOrder());
    }

    @Test
    void neverACrossProductWhileAConnectedJoinExists() {
        // a and c are tiny, so a x c (4 rows) would be the smallest pair -- but
        // no predicate connects them; both connect only to the big b.
        List<JoinOrderer.TableEstimate> tables = List.of(
                new JoinOrderer.TableEstimate("a", 2, Map.of("a_id", 2L)),
                new JoinOrderer.TableEstimate("b", 1000, Map.of("b_a", 500L, "b_c", 500L)),
                new JoinOrderer.TableEstimate("c", 2, Map.of("c_id", 2L)));
        List<JoinOrderer.JoinEdge> edges = List.of(
                new JoinOrderer.JoinEdge(0, "a_id", 1, "b_a", "a_id=b_a"),
                new JoinOrderer.JoinEdge(1, "b_c", 2, "c_id", "b_c=c_id"));

        JoinOrderer.JoinOrder order = JoinOrderer.greedy(tables, edges);

        // Every step joins along a predicate, so b is in the first pair.
        assertTrue(order.tables().subList(0, 2).contains(1), order.tables().toString());
        for (JoinOrderer.Step step : order.steps()) {
            assertTrue(edges.contains(step.edge()));
        }
    }

    @Test
    void unconnectedTableIsRejectedInsteadOfCrossed() {
        List<JoinOrderer.TableEstimate> tables = SLIDE30_TABLES.subList(0, 3);
        List<JoinOrderer.JoinEdge> edges = List.of(SLIDE30_EDGES.get(0));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> JoinOrderer.greedy(tables, edges));
        assertTrue(e.getMessage().contains("products"), e.getMessage());
    }

    private static long cost(JoinOrderer.JoinOrder order) {
        // the last join's output is the result, so it adds no cost
        List<JoinOrderer.Step> steps = order.steps();
        return steps.subList(0, steps.size() - 1).stream().mapToLong(JoinOrderer.Step::estRows).sum();
    }

    // ------------------------------------------------------------------
    // 4. Build side: the smaller estimated input builds.
    // ------------------------------------------------------------------

    @Test
    void smallerEstimatedInputBuilds(@TempDir Path dir) throws IOException {
        StorageEngine engine = lecture11Engine(dir, 1000);
        Plan plan = new Planner(engine, JoinMode.OPTIMIZED).plan(select(SLIDE19_SQL));

        // slide 38: products (1) builds over join 2 (2); join 1 (1) builds over
        // orders (7); cities (1) builds over customers (4).
        assertEquals("J(products J(J(cities customers) orders))", plan.shape());

        HashJoinOperator join3 = assertInstanceOf(HashJoinOperator.class,
                assertInstanceOf(ProjectOperator.class, plan.root()).child());
        assertBuildsFrom("products", join3.build());
        HashJoinOperator join2 = assertInstanceOf(HashJoinOperator.class, join3.probe());
        HashJoinOperator join1 = assertInstanceOf(HashJoinOperator.class, join2.build());
        assertScan("orders", join2.probe());
        assertBuildsFrom("cities", join1.build());
        assertScan("customers", join1.probe());

        assertEquals(List.of(1L, 2L, 1L), List.of(join1.estRows(), join2.estRows(), join3.estRows()));
    }

    @Test
    void filteredFirstTableBuildsWhenSmaller(@TempDir Path dir) throws IOException {
        StorageEngine engine = lecture11Engine(dir, 1000);
        // slide 16: orders filtered to an estimated 2 rows, customers 4.
        String sql = "SELECT * FROM customers, orders WHERE o_custname = c_name AND o_quantity = 2;";

        assertEquals("J(orders customers)", new Planner(engine, JoinMode.OPTIMIZED).plan(select(sql)).shape());
        // -j syntax: the FROM list's first table builds, as in week 10.
        assertEquals("J(customers orders)", new Planner(engine, JoinMode.SYNTAX).plan(select(sql)).shape());
    }

    private static void assertBuildsFrom(String table, Operator operator) {
        // a filtered table: Filter over Scan
        FilterOperator filter = assertInstanceOf(FilterOperator.class, operator, "expected filtered " + table);
        assertScan(table, filter.child());
    }

    private static void assertScan(String table, Operator operator) {
        assertInstanceOf(ScanOperator.class, operator, "expected a scan of " + table);
        operator.open();
        Object[] first = operator.next();
        operator.close();
        int width = Map.of("orders", 4, "customers", 2, "products", 2, "cities", 3).get(table);
        assertEquals(width, first.length, "scan width does not match table " + table);
    }

    // ------------------------------------------------------------------
    // 5. Column order: under a rearranged plan, SELECT * still emits columns
    //    in FROM-list order.
    // ------------------------------------------------------------------

    @Test
    void selectStarKeepsFromListColumnOrderUnderARearrangedPlan(@TempDir Path dir) throws IOException {
        StorageEngine engine = lecture11Engine(dir, 1000);
        Plan plan = new Planner(engine, JoinMode.OPTIMIZED).plan(select(SLIDE19_SQL));
        assertEquals(List.of("customers", "cities", "orders", "products"), plan.joinOrder());

        // slide 39, sorted
        assertEquals(List.of(
                "100,Anna,Bike,1,Anna,Copenhagen,Bike,2999.0,Copenhagen,Denmark,1400000",
                "102,Carol,Bike,1,Carol,Copenhagen,Bike,2999.0,Copenhagen,Denmark,1400000",
                "104,Carol,Bike,1,Carol,Copenhagen,Bike,2999.0,Copenhagen,Denmark,1400000",
                "105,Anna,Bike,1,Anna,Copenhagen,Bike,2999.0,Copenhagen,Denmark,1400000"),
                sortedCsv(Operator.drain(plan.root())));
    }

    @Test
    void columnListIsResolvedAgainstTheRearrangedRow(@TempDir Path dir) throws IOException {
        StorageEngine engine = lecture11Engine(dir, 1000);
        String sql = "SELECT ct_population, o_orderkey, c_name FROM orders, customers, products, cities "
                + "WHERE o_custname = c_name AND o_prodname = p_name AND c_city = ct_city "
                + "AND ct_population > 1000000 AND p_price > 1000.0;";

        assertEquals(List.of("1400000,100,Anna", "1400000,102,Carol", "1400000,104,Carol", "1400000,105,Anna"),
                sortedCsv(Operator.drain(new Planner(engine, JoinMode.OPTIMIZED).plan(select(sql)).root())));
    }

    // ------------------------------------------------------------------
    // 6. Metamorphic integration: the testbed queries under both flags
    //    return identical sorted rows while the plans, read from the log,
    //    differ; every executed join leaves a log line with estRows and
    //    rowsOut.
    // ------------------------------------------------------------------

    @Test
    void testbedQueriesReturnTheSameRowsUnderBothModes(@TempDir Path dir) throws IOException {
        StorageEngine engine = testbedEngine(dir);
        for (int q = 1; q <= 4; q++) {
            String sql = Files.readString(TESTBED.resolve("q" + q + ".sql"));
            List<String> duckdb = Files.readString(TESTBED.resolve("duckdb-q" + q + ".csv")).lines()
                    .map(String::strip).filter(line -> !line.isEmpty()).sorted().toList();

            List<String> rows = assertSameRowsDifferentPlans(engine, sql);
            assertEquals(duckdb, rows, "q" + q + " differs from DuckDB");
        }
    }

    @Test
    void lectureQueriesReturnTheSameRowsUnderBothModes(@TempDir Path dir) throws IOException {
        StorageEngine engine = lecture11Engine(dir, 1000);
        assertEquals(4, assertSameRowsDifferentPlans(engine, SLIDE19_SQL).size());
        // slide 23: Copenhageners ride bikes
        assertEquals(4, assertSameRowsDifferentPlans(engine, "SELECT * FROM orders, customers, products "
                + "WHERE o_custname = c_name AND o_prodname = p_name "
                + "AND c_city = 'Copenhagen' AND p_name = 'Bike';").size());
    }

    @Test
    void joinModeParsesTheCommandLineValues() {
        assertEquals(JoinMode.OPTIMIZED, JoinMode.parse("optimized"));
        assertEquals(JoinMode.SYNTAX, JoinMode.parse("syntax"));
        assertThrows(IllegalArgumentException.class, () -> JoinMode.parse("fastest"));
    }

    /**
     * Runs sql under -j syntax and -j optimized, asserts identical sorted rows,
     * different plans in the log, and one HashJoinOperator line per join with
     * estRows and rowsOut. Returns the sorted rows.
     */
    private static List<String> assertSameRowsDifferentPlans(StorageEngine engine, String sql) throws IOException {
        LoggedRun syntax = runLogged(new Executor(engine, JoinMode.SYNTAX), sql);
        LoggedRun optimized = runLogged(new Executor(engine, JoinMode.OPTIMIZED), sql);

        assertEquals(syntax.rows(), optimized.rows(), "rows differ between modes for " + sql);
        assertNotEquals(syntax.plan(), optimized.plan(), "plans should differ for " + sql);

        int joins = ((SelectStatement) new SqlParser().parse(sql).get(0)).tables().size() - 1;
        for (LoggedRun run : List.of(syntax, optimized)) {
            assertEquals(joins, run.joinLines().size(), "join log lines for " + sql);
            for (String line : run.joinLines()) {
                assertTrue(line.matches(".*estRows=\\d+ .*rowsOut=\\d+.*"), line);
            }
        }
        return optimized.rows();
    }

    private record LoggedRun(List<String> rows, String plan, List<String> joinLines) {
    }

    /** Runs sql under its own sessionId, so its lines can be read back from logs/engine.log. */
    private static LoggedRun runLogged(Executor executor, String sql) throws IOException {
        String sessionId = UUID.randomUUID().toString();
        String previous = MDC.get("sessionId");
        MDC.put("sessionId", sessionId);
        List<Object[]> rows;
        try {
            rows = executor.run(sql).get(0).rows().orElseThrow();
        } finally {
            if (previous == null) {
                MDC.remove("sessionId");
            } else {
                MDC.put("sessionId", previous);
            }
        }

        String plan = null;
        List<String> joinLines = new ArrayList<>();
        for (String line : Files.readAllLines(LOG_FILE, StandardCharsets.ISO_8859_1)) {
            // date,sessionId,statementNumber,thread,level,logger,message
            String[] fields = line.split(",", 7);
            if (fields.length < 7 || !fields[1].equals(sessionId)) {
                continue;
            }
            String message = fields[6];
            if (fields[5].equals("Executor") && message.startsWith("statement=SELECT")) {
                plan = message.substring(message.indexOf("plan=") + 5, message.indexOf(" rowsOut="));
            } else if (fields[5].equals("HashJoinOperator")) {
                joinLines.add(message);
            }
        }
        assertTrue(plan != null, "no statement=SELECT line logged for session " + sessionId);
        return new LoggedRun(sortedCsv(rows), plan, joinLines);
    }

    private static List<String> sortedCsv(List<Object[]> rows) {
        return rows.stream()
                .map(row -> Arrays.stream(row).map(String::valueOf).collect(Collectors.joining(",")))
                .sorted().toList();
    }
}

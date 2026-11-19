package dk.itu.datasys;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Exercise 11, section 7 (optional extension): GROUP BY with min, max, count
 * and sum, by hash aggregation. DuckDB is the oracle: the expected outputs in
 * src/test/resources/groupby/duckdb-g*.csv were produced by the DuckDB CLI
 * over the same tables (lecture 11's running example) and queries.
 */
class Week11GroupByTests {

    private static final Path GROUPBY = Path.of("src/test/resources/groupby");
    private static final int QUERIES = 8;

    private final SqlParser parser = new SqlParser();
    private final SqlPrinter printer = new SqlPrinter();

    private SelectStatement select(String sql) {
        return (SelectStatement) parser.parse(sql).get(0);
    }

    // ------------------------------------------------------------------
    // Grammar: aggregates in the SELECT list, GROUP BY, round trip.
    // ------------------------------------------------------------------

    @Test
    void parsesAggregatesAndGroupBy() throws IOException {
        SelectStatement statement = select(Files.readString(GROUPBY.resolve("g1.sql")));

        assertEquals(List.of("c_city"), statement.columns());
        assertEquals(List.of(
                new Aggregate(AggregateFunction.MAX, "p_price", 1),
                new Aggregate(AggregateFunction.COUNT, null, 2)), statement.aggregates());
        assertEquals(List.of("c_city"), statement.groupBy());
        assertEquals(List.of("c_city", statement.aggregates().get(0), statement.aggregates().get(1)),
                statement.selectItems());
        assertTrue(statement.isAggregation());
    }

    @Test
    void functionNamesAreCaseInsensitiveAndStayUsableAsColumnNames() {
        SelectStatement statement = select("SELECT MAX(count), Count(*) FROM t GROUP BY count;");
        assertEquals(List.of(
                new Aggregate(AggregateFunction.MAX, "count", 0),
                new Aggregate(AggregateFunction.COUNT, null, 1)), statement.aggregates());
        assertEquals(List.of("count"), statement.groupBy());
    }

    @Test
    void printerRoundTripsGroupByQueries() throws IOException {
        for (int q = 1; q <= QUERIES; q++) {
            Statement parsed = parser.parse(Files.readString(GROUPBY.resolve("g" + q + ".sql"))).get(0);
            assertEquals(List.of(parsed), parser.parse(printer.print(parsed)), "g" + q);
        }
    }

    @Test
    void parserRejectsUnsupportedAggregates() {
        assertThrows(SqlParseException.class, () -> parser.parse("SELECT sum(*) FROM orders;"));
        assertThrows(SqlParseException.class, () -> parser.parse("SELECT avg(o_quantity) FROM orders;"));
    }

    // ------------------------------------------------------------------
    // Binder: the rules DuckDB enforces too.
    // ------------------------------------------------------------------

    @Test
    void binderRejectsInvalidAggregations(@TempDir Path dir) {
        Binder binder = new Binder(loadedEngine(dir));

        IllegalArgumentException notGrouped = assertThrows(IllegalArgumentException.class,
                () -> binder.bind(select("SELECT c_name, count(*) FROM customers GROUP BY c_city;")));
        assertTrue(notGrouped.getMessage().contains("GROUP BY"), notGrouped.getMessage());

        IllegalArgumentException notGroupedWithoutGroupBy = assertThrows(IllegalArgumentException.class,
                () -> binder.bind(select("SELECT c_name, count(*) FROM customers;")));
        assertTrue(notGroupedWithoutGroupBy.getMessage().contains("c_name"), notGroupedWithoutGroupBy.getMessage());

        IllegalArgumentException sumOfString = assertThrows(IllegalArgumentException.class,
                () -> binder.bind(select("SELECT sum(c_name) FROM customers;")));
        assertTrue(sumOfString.getMessage().contains("STRING"), sumOfString.getMessage());

        assertThrows(IllegalArgumentException.class,
                () -> binder.bind(select("SELECT * FROM customers GROUP BY c_city;")));
        assertThrows(IllegalArgumentException.class,
                () -> binder.bind(select("SELECT max(ghost) FROM customers;")));
    }

    @Test
    void qualifiedAndBareReferencesToTheGroupingColumnMatch(@TempDir Path dir) {
        StorageEngine engine = loadedEngine(dir);
        Executor executor = new Executor(engine);
        List<String> rows = csv(executor.run(
                "SELECT customers.c_city, count(*) FROM customers GROUP BY c_city;").get(0).rows().orElseThrow());
        assertEquals(List.of("Aarhus,1", "Copenhagen,2", "Odense,1"), rows);
    }

    // ------------------------------------------------------------------
    // Accumulators and the operator: init, update, finish.
    // ------------------------------------------------------------------

    @Test
    void accumulatorsFollowInitUpdateFinish() {
        assertEquals(3L, feed(Accumulator.of(AggregateFunction.COUNT, ColumnType.STRING), "a", "b", "a"));
        assertEquals(6L, feed(Accumulator.of(AggregateFunction.SUM, ColumnType.LONG), 1L, 2L, 3L));
        assertEquals(3298.0, feed(Accumulator.of(AggregateFunction.SUM, ColumnType.DOUBLE), 2999.0, 299.0));
        assertEquals("Odense", feed(Accumulator.of(AggregateFunction.MAX, ColumnType.STRING),
                "Copenhagen", "Odense", "Aarhus"));
        assertEquals(-5L, feed(Accumulator.of(AggregateFunction.MIN, ColumnType.LONG), 3L, -5L, 0L));
        // no rows: count 0, the others no value
        assertEquals(0L, feed(Accumulator.of(AggregateFunction.COUNT, ColumnType.LONG)));
        assertNull(feed(Accumulator.of(AggregateFunction.SUM, ColumnType.LONG)));
        assertNull(feed(Accumulator.of(AggregateFunction.MAX, ColumnType.DOUBLE)));
    }

    @Test
    void operatorEmitsOneRowPerGroup() {
        Operator rows = new ListOperator(List.of(
                new Object[] { "Copenhagen", 2999.0 },
                new Object[] { "Aarhus", 299.0 },
                new Object[] { "Copenhagen", 299.0 }));
        List<Supplier<Accumulator>> accumulators = List.of(
                () -> Accumulator.of(AggregateFunction.MAX, ColumnType.DOUBLE),
                () -> Accumulator.of(AggregateFunction.COUNT, ColumnType.LONG));

        List<Object[]> out = Operator.drain(
                new HashAggregateOperator(rows, List.of(0), List.of(1, -1), accumulators, "test", 2));

        // groups come out in first-seen order
        assertEquals(2, out.size());
        assertArrayEquals(new Object[] { "Copenhagen", 2999.0, 2L }, out.get(0));
        assertArrayEquals(new Object[] { "Aarhus", 299.0, 1L }, out.get(1));
    }

    @Test
    void emptyInputGivesOneRowWithoutGroupByAndNoneWithIt() {
        List<Supplier<Accumulator>> accumulators = List.of(
                () -> Accumulator.of(AggregateFunction.COUNT, ColumnType.LONG),
                () -> Accumulator.of(AggregateFunction.MAX, ColumnType.DOUBLE));

        List<Object[]> global = Operator.drain(new HashAggregateOperator(new ListOperator(List.of()),
                List.of(), List.of(-1, 0), accumulators, "test", 1));
        assertEquals(1, global.size());
        assertArrayEquals(new Object[] { 0L, null }, global.get(0));

        List<Object[]> grouped = Operator.drain(new HashAggregateOperator(new ListOperator(List.of()),
                List.of(0), List.of(-1, 0), accumulators, "test", 1));
        assertEquals(List.of(), grouped);
    }

    // ------------------------------------------------------------------
    // Planner: the aggregate sits on top of the joins (slide 44).
    // ------------------------------------------------------------------

    @Test
    void aggregateSitsAboveTheJoins(@TempDir Path dir) throws IOException {
        StorageEngine engine = loadedEngine(dir);
        Plan plan = new Planner(engine, JoinMode.SYNTAX).plan(select(Files.readString(GROUPBY.resolve("g1.sql"))));

        assertEquals("Agg(J(J(orders customers) products))", plan.shape());
        HashAggregateOperator aggregate = (HashAggregateOperator) plan.root();
        assertEquals(List.of("Copenhagen,2999.0,5", "Aarhus,299.0,1", "Odense,299.0,1"),
                Operator.drain(aggregate).stream().map(Week11GroupByTests::csvLine).toList());
    }

    // ------------------------------------------------------------------
    // Oracle: every query, in both join modes, equals DuckDB (sorted).
    // ------------------------------------------------------------------

    @Test
    void groupByQueriesMatchDuckDbInBothJoinModes(@TempDir Path dir) throws IOException {
        StorageEngine engine = loadedEngine(dir);
        for (JoinMode mode : JoinMode.values()) {
            Executor executor = new Executor(engine, mode);
            for (int q = 1; q <= QUERIES; q++) {
                List<String> theirs = Files.readString(GROUPBY.resolve("duckdb-g" + q + ".csv")).lines()
                        .map(String::strip).filter(line -> !line.isEmpty()).sorted().toList();
                List<String> ours = csv(executor.run(Files.readString(GROUPBY.resolve("g" + q + ".sql")))
                        .get(0).rows().orElseThrow());
                assertEquals(theirs, ours, "g" + q + " under -j " + mode.label() + " differs from DuckDB");
            }
        }
    }

    // ------------------------------------------------------------------
    // -j optimized accepts any connected FROM order; -j syntax needs the
    // FROM list itself to fold left-deep.
    // ------------------------------------------------------------------

    @Test
    void optimizedAcceptsAFromListThatCannotBeFoldedInOrder(@TempDir Path dir) {
        StorageEngine engine = loadedEngine(dir);
        // cities connects only to customers, which comes after it.
        String sql = "SELECT * FROM orders, cities, customers WHERE o_custname = c_name AND c_city = ct_city;";

        assertEquals(7, new Executor(engine, JoinMode.OPTIMIZED).run(sql).get(0).rows().orElseThrow().size());
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new Executor(engine, JoinMode.SYNTAX).run(sql));
        assertTrue(e.getMessage().contains("cities"), e.getMessage());
    }

    // ------------------------------------------------------------------

    private static StorageEngine loadedEngine(Path dir) {
        StorageEngine engine = new StorageEngine(dir.resolve("data"));
        try {
            new Executor(engine).run(Files.readString(GROUPBY.resolve("load.sql")));
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return engine;
    }

    private static Object feed(Accumulator accumulator, Object... values) {
        for (Object value : values) {
            accumulator.update(value);
        }
        return accumulator.finish();
    }

    /** As Engine prints them: null is an empty field. Sorted, like `sort` on the CSV. */
    private static List<String> csv(List<Object[]> rows) {
        return rows.stream().map(Week11GroupByTests::csvLine).sorted().toList();
    }

    private static String csvLine(Object[] row) {
        return Arrays.stream(row).map(value -> value == null ? "" : String.valueOf(value))
                .collect(Collectors.joining(","));
    }

    /** Serves rows from a fixed list. */
    private static final class ListOperator implements Operator {
        private final List<Object[]> rows;
        private Iterator<Object[]> iterator;

        ListOperator(List<Object[]> rows) {
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
}

package dk.itu.datasys;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

class SqlPrinterTest {
    private final SqlPrinter printer = new SqlPrinter();
    private final SqlParser parser = new SqlParser();

    @Test
    void roundTrips_createTable() {
        assertRoundTrips(new CreateTableStatement("trips", List.of(
                new ColumnSpec("city", ColumnType.STRING),
                new ColumnSpec("distance", ColumnType.LONG),
                new ColumnSpec("price", ColumnType.DOUBLE))));
    }

    @Test
    void roundTrips_copy() {
        assertRoundTrips(new CopyStatement("trips", "trips.csv"));
    }

    @Test
    void roundTrips_selectWithoutWhere() {
        assertRoundTrips(new SelectStatement(List.of("trips"), List.of(), List.of()));
    }

    @Test
    void roundTrips_selectWithStringPredicate() {
        assertRoundTrips(new SelectStatement(List.of("trips"),
                List.of(new Predicate("city", Comparison.EQUALS, "Copenhagen")), List.of()));
    }

    @Test
    void roundTrips_selectWithLongPredicate() {
        assertRoundTrips(new SelectStatement(List.of("trips"),
                List.of(new Predicate("distance", Comparison.GREATER_THAN, 100L)), List.of()));
    }

    @Test
    void roundTrips_selectWithDoublePredicate() {
        assertRoundTrips(new SelectStatement(List.of("trips"),
                List.of(new Predicate("price", Comparison.LESS_THAN, 50.0)), List.of()));
    }

    @Test
    void roundTrips_selectWithNegativeLongPredicate() {
        assertRoundTrips(new SelectStatement(List.of("trips"),
                List.of(new Predicate("distance", Comparison.EQUALS, -1L)), List.of()));
    }

    @Test
    void roundTrips_selectWithNegativeDoublePredicate() {
        assertRoundTrips(new SelectStatement(List.of("trips"),
                List.of(new Predicate("price", Comparison.EQUALS, -1.5)), List.of()));
    }

    @Test
    void roundTrips_selectWithSmallDoublePredicate() {
        assertRoundTrips(new SelectStatement(List.of("trips"),
                List.of(new Predicate("price", Comparison.EQUALS, 0.0000001)), List.of()));
    }

    @Test
    void roundTrips_selectWithLargeDoublePredicate() {
        assertRoundTrips(new SelectStatement(List.of("trips"),
                List.of(new Predicate("price", Comparison.EQUALS, 100000000000000000.0)), List.of()));
    }

    @Test
    void roundTrips_selectWithAndChainOfFilters() {
        assertRoundTrips(new SelectStatement(List.of("trips"),
                List.of(new Predicate("city", Comparison.EQUALS, "Copenhagen"),
                        new Predicate("distance", Comparison.GREATER_THAN, 100L)),
                List.of()));
    }

    @Test
    void roundTrips_selectWithMultipleTablesAndJoin() {
        assertRoundTrips(new SelectStatement(List.of("trips", "drivers"),
                List.of(new Predicate("city", Comparison.EQUALS, "Copenhagen")),
                List.of(new JoinPredicate("driver_id", "id"))));
    }

    @Test
    void roundTrips_deleteWithoutWhere() {
        assertRoundTrips(new DeleteStatement("trips", Optional.empty()));
    }

    @Test
    void roundTrips_deleteWithPredicate() {
        assertRoundTrips(new DeleteStatement("trips",
                Optional.of(new Predicate("distance", Comparison.GREATER_THAN, 100L))));
    }

    /** The parse(print(s)) round-trip property, for a single statement. */
    private void assertRoundTrips(Statement statement) {
        String printed = printer.print(statement);
        List<Statement> reparsed = parser.parse(printed);
        assertEquals(1, reparsed.size());
        assertEquals(statement, reparsed.get(0));
    }

}

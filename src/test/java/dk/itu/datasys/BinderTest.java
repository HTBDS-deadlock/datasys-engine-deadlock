package dk.itu.datasys;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BinderTest {
        @TempDir
        Path dataDirectory;

        private StorageEngine engineWithTripsTable() {
                StorageEngine engine = new StorageEngine(dataDirectory);
                engine.createTable("trips", List.of(
                                new ColumnSpec("city", ColumnType.STRING),
                                new ColumnSpec("distance", ColumnType.LONG)));
                return engine;
        }

        private StorageEngine engineWithJoinableTables() {
                StorageEngine engine = new StorageEngine(dataDirectory);
                engine.createTable("trips", List.of(
                                new ColumnSpec("distance", ColumnType.LONG),
                                new ColumnSpec("driver_id", ColumnType.LONG),
                                new ColumnSpec("city_code", ColumnType.LONG)));
                engine.createTable("drivers", List.of(
                                new ColumnSpec("id", ColumnType.LONG),
                                new ColumnSpec("name", ColumnType.STRING),
                                new ColumnSpec("hometown_code", ColumnType.LONG)));
                engine.createTable("cities", List.of(
                                new ColumnSpec("code", ColumnType.LONG),
                                new ColumnSpec("name", ColumnType.STRING)));
                return engine;
        }

        @Test
        void bindsValidStatements() {
                Binder binder = new Binder(engineWithTripsTable());

                assertDoesNotThrow(() -> binder.bind(new CopyStatement("trips", "trips.csv")));
                assertDoesNotThrow(() -> binder.bind(new SelectStatement(List.of("trips"),
                                List.of(new Predicate("distance", Comparison.GREATER_THAN, 100L)), List.of())));
                assertDoesNotThrow(() -> binder.bind(new SelectStatement(List.of("trips"), List.of(), List.of())));
                assertDoesNotThrow(() -> binder.bind(new SelectStatement(List.of("trips"),
                                List.of(new Predicate("distance", Comparison.GREATER_THAN, 100L),
                                                new Predicate("city", Comparison.EQUALS, "Copenhagen")),
                                List.of())));
                assertDoesNotThrow(() -> binder.bind(new CreateTableStatement("other",
                                List.of(new ColumnSpec("x", ColumnType.LONG)))));
                assertDoesNotThrow(() -> binder.bind(new DeleteStatement("trips",
                                Optional.of(new Predicate("distance", Comparison.GREATER_THAN, 100L)))));
                assertDoesNotThrow(() -> binder.bind(new DeleteStatement("trips", Optional.empty())));
        }

        @Test
        void rejectsUnknownTableInDelete() {
                Binder binder = new Binder(engineWithTripsTable());
                assertThrows(IllegalArgumentException.class,
                                () -> binder.bind(new DeleteStatement("ghost", Optional.empty())));
        }

        @Test
        void rejectsUnknownColumnInDeleteWhere() {
                Binder binder = new Binder(engineWithTripsTable());
                assertThrows(IllegalArgumentException.class,
                                () -> binder.bind(new DeleteStatement("trips",
                                                Optional.of(new Predicate("ghost_column", Comparison.EQUALS, "x")))));
        }

        @Test
        void rejectsWrongConstantTypeInDeleteWhere() {
                Binder binder = new Binder(engineWithTripsTable());
                assertThrows(IllegalArgumentException.class,
                                () -> binder.bind(new DeleteStatement("trips",
                                                Optional.of(new Predicate("distance", Comparison.GREATER_THAN,
                                                                "not-a-long")))));
        }

        @Test
        void rejectsUnknownTableInSelect() {
                Binder binder = new Binder(engineWithTripsTable());
                assertThrows(IllegalArgumentException.class,
                                () -> binder.bind(new SelectStatement(List.of("ghost"), List.of(), List.of())));
        }

        @Test
        void rejectsUnknownTableInCopy() {
                Binder binder = new Binder(engineWithTripsTable());
                assertThrows(IllegalArgumentException.class,
                                () -> binder.bind(new CopyStatement("ghost", "trips.csv")));
        }

        @Test
        void rejectsUnknownColumnInWhere() {
                Binder binder = new Binder(engineWithTripsTable());
                assertThrows(IllegalArgumentException.class,
                                () -> binder.bind(new SelectStatement(List.of("trips"),
                                                List.of(new Predicate("ghost_column", Comparison.EQUALS, "x")),
                                                List.of())));
        }

        @Test
        void rejectsWrongConstantTypeInWhere() {
                Binder binder = new Binder(engineWithTripsTable());
                assertThrows(IllegalArgumentException.class,
                                () -> binder.bind(new SelectStatement(List.of("trips"),
                                                List.of(new Predicate("distance", Comparison.GREATER_THAN,
                                                                "not-a-long")),
                                                List.of())));
        }

        @Test
        void rejectsSelectWithJoinOnASingleTable() {
                Binder binder = new Binder(engineWithTripsTable());
                assertThrows(UnsupportedOperationException.class,
                                () -> binder.bind(new SelectStatement(List.of("trips"), List.of(),
                                                List.of(new JoinPredicate("city", "city")))));
        }

        @Test
        void bindsValidTwoTableJoin() {
                Binder binder = new Binder(engineWithJoinableTables());
                assertDoesNotThrow(() -> binder.bind(new SelectStatement(List.of("trips", "drivers"),
                                List.of(new Predicate("distance", Comparison.GREATER_THAN, 100L)),
                                List.of(new JoinPredicate("driver_id", "id")))));
        }

        @Test
        void bindsValidThreeTableLeftDeepJoin() {
                Binder binder = new Binder(engineWithJoinableTables());
                assertDoesNotThrow(() -> binder.bind(new SelectStatement(List.of("trips", "drivers", "cities"),
                                List.of(),
                                List.of(new JoinPredicate("driver_id", "id"),
                                                new JoinPredicate("city_code", "code")))));
        }

        @Test
        void rejectsMultiTableSelectWithNoConnectingPredicate() {
                Binder binder = new Binder(engineWithJoinableTables());
                assertThrows(IllegalArgumentException.class,
                                () -> binder.bind(new SelectStatement(List.of("trips", "drivers"), List.of(), List.of())));
        }

        @Test
        void rejectsTableWithMoreThanOneConnectingPredicate() {
                Binder binder = new Binder(engineWithJoinableTables());
                // cities connects to {trips, drivers} via two different predicates.
                assertThrows(IllegalArgumentException.class,
                                () -> binder.bind(new SelectStatement(List.of("trips", "drivers", "cities"), List.of(),
                                                List.of(new JoinPredicate("driver_id", "id"),
                                                                new JoinPredicate("city_code", "code"),
                                                                new JoinPredicate("hometown_code", "code")))));
        }

        @Test
        void rejectsWrongConstantTypeInJoinedFilter() {
                Binder binder = new Binder(engineWithJoinableTables());
                assertThrows(IllegalArgumentException.class,
                                () -> binder.bind(new SelectStatement(List.of("trips", "drivers"),
                                                List.of(new Predicate("distance", Comparison.GREATER_THAN, "not-a-long")),
                                                List.of(new JoinPredicate("driver_id", "id")))));
        }

        @Test
        void rejectsEmptyColumnListInCreateTable() {
                Binder binder = new Binder(engineWithTripsTable());
                assertThrows(IllegalArgumentException.class,
                                () -> binder.bind(new CreateTableStatement("empty", List.of())));
        }

        @Test
        void rejectsDuplicateColumnNamesInCreateTable() {
                Binder binder = new Binder(engineWithTripsTable());
                assertThrows(IllegalArgumentException.class,
                                () -> binder.bind(new CreateTableStatement("dup", List.of(
                                                new ColumnSpec("x", ColumnType.LONG),
                                                new ColumnSpec("x", ColumnType.DOUBLE)))));
        }
}

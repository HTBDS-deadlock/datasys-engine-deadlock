package dk.itu.datasys;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.List;

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

    @Test
    void bindsValidStatements() {
        Binder binder = new Binder(engineWithTripsTable());

        assertDoesNotThrow(() -> binder.bind(new CopyStatement("trips", "trips.csv")));
        assertDoesNotThrow(() -> binder.bind(new SelectStatement("trips",
                new Predicate("distance", Comparison.GREATER_THAN, 100L))));
        assertDoesNotThrow(() -> binder.bind(new SelectStatement("trips", null)));
        assertDoesNotThrow(() -> binder.bind(new CreateTableStatement("other",
                List.of(new ColumnSpec("x", ColumnType.LONG)))));
    }

    @Test
    void rejectsUnknownTableInSelect() {
        Binder binder = new Binder(engineWithTripsTable());
        assertThrows(IllegalArgumentException.class,
                () -> binder.bind(new SelectStatement("ghost", null)));
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
                () -> binder.bind(new SelectStatement("trips",
                        new Predicate("ghost_column", Comparison.EQUALS, "x"))));
    }

    @Test
    void rejectsWrongConstantTypeInWhere() {
        Binder binder = new Binder(engineWithTripsTable());
        assertThrows(IllegalArgumentException.class,
                () -> binder.bind(new SelectStatement("trips",
                        new Predicate("distance", Comparison.GREATER_THAN, "not-a-long"))));
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

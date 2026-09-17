package dk.itu.datasys;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OperatorTest {
    @TempDir
    Path dataDirectory;

    private StorageEngine engineWithTripsTable() {
        StorageEngine engine = new StorageEngine(dataDirectory, 2);
        engine.createTable("trips", List.of(
                new ColumnSpec("city", ColumnType.STRING),
                new ColumnSpec("distance", ColumnType.LONG),
                new ColumnSpec("price", ColumnType.DOUBLE)));
        engine.copyFile("trips", Path.of("src", "test", "resources", "trips.csv").toString());
        return engine;
    }

    @Test
    void scanOperatorReturnsEveryRowInOrder() {
        StorageEngine engine = engineWithTripsTable();
        ScanOperator scan = new ScanOperator(engine, "trips", List.of(0, 1, 2, 3));

        scan.open();
        List<Object[]> rows = drain(scan);
        scan.close();

        assertEquals(8, rows.size());
        assertArrayEquals(new Object[] { "Copenhagen", 12L, 23.5 }, rows.get(0));
        assertArrayEquals(new Object[] { "Esbjerg", 299L, 450.25 }, rows.get(7));
    }

    @Test
    void filterOperatorEmitsOnlyRowsThatPass() {
        StorageEngine engine = engineWithTripsTable();
        ScanOperator scan = new ScanOperator(engine, "trips", List.of(0, 1, 2, 3));
        Predicate predicate = new Predicate("distance", Comparison.GREATER_THAN, 100L);
        FilterOperator filter = new FilterOperator(scan, engine.schema("trips"), predicate);

        filter.open();
        List<Object[]> rows = drain(filter);
        filter.close();

        List<Object[]> expected = engine.select("trips", "distance", Comparison.GREATER_THAN, 100L);
        assertEquals(expected.size(), rows.size());
        for (int i = 0; i < rows.size(); i++) {
            assertArrayEquals(expected.get(i), rows.get(i));
        }
    }

    @Test
    void filterOperatorReturnsNullWhenChildIsExhausted() {
        StorageEngine engine = engineWithTripsTable();
        ScanOperator scan = new ScanOperator(engine, "trips", List.of(0, 1, 2, 3));
        FilterOperator filter = new FilterOperator(scan, engine.schema("trips"),
                new Predicate("city", Comparison.EQUALS, "Nowhere"));

        filter.open();
        assertNull(filter.next());
        filter.close();
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

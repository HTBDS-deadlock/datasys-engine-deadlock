package dk.itu.datasys;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Integration tests driving the public StorageEngine API end-to-end. */
class StorageEngineIT {

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

    @Test
    void schemaPersistsAcrossRestart(@TempDir Path dir) {
        new StorageEngine(dir).createTable("trips", TRIPS_COLUMNS);

        StorageEngine restarted = new StorageEngine(dir);
        assertThrows(IllegalArgumentException.class,
                () -> restarted.createTable("trips", TRIPS_COLUMNS));
    }

    @Test
    void duplicateTableThrows(@TempDir Path dir) {
        StorageEngine engine = new StorageEngine(dir);
        engine.createTable("trips", TRIPS_COLUMNS);

        assertThrows(IllegalArgumentException.class, () -> engine.createTable("trips", TRIPS_COLUMNS));
    }

    @Test
    void roundTripReturnsAllRows(@TempDir Path dir) throws IOException {
        Path csv = writeGoldenCsv(dir);
        StorageEngine engine = new StorageEngine(dir);
        engine.createTable("trips", TRIPS_COLUMNS);
        engine.copyFile("trips", csv.toString());

        List<Object[]> rows = engine.select("trips", "distance", Comparison.GREATER_THAN, -1L);
        assertEquals(8, rows.size());
        assertEquals("Copenhagen", rows.get(0)[0]);
        assertEquals(12L, rows.get(0)[1]);
        assertEquals(23.5, rows.get(0)[2]);
    }

    @Test
    void allComparisonsAcrossTypes(@TempDir Path dir) throws IOException {
        Path csv = writeGoldenCsv(dir);
        StorageEngine engine = new StorageEngine(dir);
        engine.createTable("trips", TRIPS_COLUMNS);
        engine.copyFile("trips", csv.toString());

        assertEquals(4, engine.select("trips", "distance", Comparison.GREATER_THAN, 100L).size());
        assertEquals(3, engine.select("trips", "distance", Comparison.LESS_THAN, 90L).size());
        assertEquals(1, engine.select("trips", "distance", Comparison.EQUALS, 187L).size());

        assertEquals(3, engine.select("trips", "price", Comparison.GREATER_THAN, 300.0).size());
        assertEquals(2, engine.select("trips", "price", Comparison.LESS_THAN, 50.0).size());
        assertEquals(1, engine.select("trips", "price", Comparison.EQUALS, 45.0).size());

        assertEquals(3, engine.select("trips", "city", Comparison.GREATER_THAN, "Copenhagen").size());
        assertEquals(1, engine.select("trips", "city", Comparison.LESS_THAN, "Aarhus").size());
        assertEquals(3, engine.select("trips", "city", Comparison.EQUALS, "Copenhagen").size());
    }

    @Test
    void emptyResult(@TempDir Path dir) throws IOException {
        Path csv = writeGoldenCsv(dir);
        StorageEngine engine = new StorageEngine(dir);
        engine.createTable("trips", TRIPS_COLUMNS);
        engine.copyFile("trips", csv.toString());

        assertTrue(engine.select("trips", "distance", Comparison.GREATER_THAN, 1000L).isEmpty());
    }

    @Test
    void errorsOnUnknownTableColumnAndTypeMismatch(@TempDir Path dir) throws IOException {
        Path csv = writeGoldenCsv(dir);
        StorageEngine engine = new StorageEngine(dir);
        engine.createTable("trips", TRIPS_COLUMNS);
        engine.copyFile("trips", csv.toString());

        assertThrows(IllegalArgumentException.class,
                () -> engine.select("missing", "distance", Comparison.EQUALS, 1L));
        assertThrows(IllegalArgumentException.class,
                () -> engine.select("trips", "missing", Comparison.EQUALS, 1L));
        // An Integer constant against a LONG column is an error, not a widening.
        assertThrows(IllegalArgumentException.class,
                () -> engine.select("trips", "distance", Comparison.EQUALS, 1));
    }

    @Test
    void partitioningProducesCorrectPartitionCount(@TempDir Path dir) throws IOException {
        Path csv = writeGoldenCsv(dir);
        StorageEngine engine = new StorageEngine(dir, 2);
        engine.createTable("trips", TRIPS_COLUMNS);
        engine.copyFile("trips", csv.toString());

        List<Object[]> all = engine.select("trips", "distance", Comparison.GREATER_THAN, -1L);
        assertEquals(8, all.size());
        assertEquals(4, engine.lastScanStats().partitionsTotal());
    }

    @Test
    void pruningSkipsPartitions(@TempDir Path dir) throws IOException {
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

        StorageEngine engine = new StorageEngine(dir, 2);
        engine.createTable("trips", TRIPS_COLUMNS);
        engine.copyFile("trips", csv.toString());

        List<Object[]> rows = engine.select("trips", "distance", Comparison.GREATER_THAN, 200L);
        ScanStats stats = engine.lastScanStats();

        assertEquals(2, rows.size());
        assertTrue(stats.partitionsPruned() >= 2);
    }

    @Test
    void dataPersistsAcrossRestart(@TempDir Path dir) throws IOException {
        Path csv = writeGoldenCsv(dir);
        StorageEngine engineA = new StorageEngine(dir);
        engineA.createTable("trips", TRIPS_COLUMNS);
        engineA.copyFile("trips", csv.toString());

        StorageEngine engineB = new StorageEngine(dir);
        List<Object[]> rows = engineB.select("trips", "distance", Comparison.GREATER_THAN, -1L);
        assertEquals(8, rows.size());
    }
}

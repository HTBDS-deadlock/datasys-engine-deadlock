package dk.itu.datasys;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/** Unit tests against StorageEngine's package-private internal methods. */
class StorageEngineTest {

    private static final List<ColumnSpec> TRIPS_COLUMNS = List.of(
            new ColumnSpec("city", ColumnType.STRING),
            new ColumnSpec("distance", ColumnType.LONG),
            new ColumnSpec("price", ColumnType.DOUBLE));

    // ---- Value encoding round trip ----

    @Test
    void encodeDecodeLongRoundTrip() {
        byte[] bytes = StorageEngine.encode(ColumnType.LONG, 42L);
        assertEquals(42L, StorageEngine.decode(ColumnType.LONG, bytes));
    }

    @Test
    void encodeDecodeDoubleRoundTrip() {
        byte[] bytes = StorageEngine.encode(ColumnType.DOUBLE, 3.14);
        assertEquals(3.14, (Double) StorageEngine.decode(ColumnType.DOUBLE, bytes));
    }

    @Test
    void encodeDecodeStringRoundTrip() {
        byte[] bytes = StorageEngine.encode(ColumnType.STRING, "Copenhagen");
        assertEquals("Copenhagen", StorageEngine.decode(ColumnType.STRING, bytes));
    }

    // ---- Min/max computation ----

    @Test
    void minMaxMultipleValues() {
        Object[] result = StorageEngine.minMax(List.of(12L, 187L, 95L, 31L));
        assertEquals(12L, result[0]);
        assertEquals(187L, result[1]);
    }

    @Test
    void minMaxSingleValue() {
        Object[] result = StorageEngine.minMax(List.of(42L));
        assertEquals(42L, result[0]);
        assertEquals(42L, result[1]);
    }

    @Test
    void minMaxNegativeValues() {
        Object[] result = StorageEngine.minMax(List.of(-5L, -20L, -1L));
        assertEquals(-20L, result[0]);
        assertEquals(-1L, result[1]);
    }

    // ---- Pruning decision ----

    @Test
    void pruningEqualsOutOfRangePrunes() {
        assertTrue(StorageEngine.canPrune(Comparison.EQUALS, "Odense", "Aalborg", "Copenhagen"));
    }

    @Test
    void pruningEqualsInRangeReads() {
        assertFalse(StorageEngine.canPrune(Comparison.EQUALS, "Aarhus", "Aalborg", "Copenhagen"));
    }

    @Test
    void pruningGreaterThanMaxTooSmallPrunes() {
        assertTrue(StorageEngine.canPrune(Comparison.GREATER_THAN, 100L, 12L, 87L));
    }

    @Test
    void pruningGreaterThanReadsWhenMaxAboveConst() {
        assertFalse(StorageEngine.canPrune(Comparison.GREATER_THAN, 100L, 140L, 187L));
    }

    @Test
    void pruningLessThanMinTooLargePrunes() {
        assertTrue(StorageEngine.canPrune(Comparison.LESS_THAN, 50.0, 88.0, 120.0));
    }

    @Test
    void pruningLessThanReadsWhenMinBelowConst() {
        assertFalse(StorageEngine.canPrune(Comparison.LESS_THAN, 50.0, 10.0, 45.0));
    }

    // ---- CSV line parsing ----

    @Test
    void parseLineWellFormed() {
        Object[] row = StorageEngine.parseLine("Copenhagen,12,23.5", TRIPS_COLUMNS, "trips.csv", 1);
        assertArrayEquals(new Object[] { "Copenhagen", 12L, 23.5 }, row);
    }

    @Test
    void parseLineMalformedValueThrows() {
        List<ColumnSpec> columns = List.of(new ColumnSpec("distance", ColumnType.LONG));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> StorageEngine.parseLine("notanumber", columns, "trips.csv", 3));
        assertTrue(ex.getMessage().contains("trips.csv"));
        assertTrue(ex.getMessage().contains("3"));
    }

    @Test
    void parseLineWrongFieldCountThrows() {
        List<ColumnSpec> columns = List.of(
                new ColumnSpec("city", ColumnType.STRING),
                new ColumnSpec("distance", ColumnType.LONG));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> StorageEngine.parseLine("Copenhagen,12,23.5", columns, "trips.csv", 5));
        assertTrue(ex.getMessage().contains("trips.csv"));
        assertTrue(ex.getMessage().contains("5"));
    }
}

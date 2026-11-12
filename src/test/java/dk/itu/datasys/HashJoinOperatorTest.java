package dk.itu.datasys;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Iterator;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Exercise 10, point 3's required test suite: HashJoinOperator over stub
 * children, with no Scan/Binder/Planner involved.
 */
class HashJoinOperatorTest {

    /** Serves rows from a fixed list; same stub as Week4Tests' FilterOperator tests. */
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

    // A single Object[] argument to List.of(...) is ambiguous with its varargs
    // overload, so rows are built through this instead.
    private static List<Object[]> rows(Object[]... rows) {
        return List.of(rows);
    }

    private static final List<ColumnSpec> TRIPS_SCHEMA = List.of(
            new ColumnSpec("city", ColumnType.STRING),
            new ColumnSpec("driver_id", ColumnType.LONG));

    private static final List<ColumnSpec> DRIVERS_SCHEMA = List.of(
            new ColumnSpec("id", ColumnType.LONG),
            new ColumnSpec("name", ColumnType.STRING));

    @Test
    void emitsOneRowPerMatchingPairWithBuildColumnsFirst() {
        Operator trips = new TestListOperator(rows(
                new Object[] { "Copenhagen", 1L },
                new Object[] { "Aarhus", 2L }));
        Operator drivers = new TestListOperator(rows(
                new Object[] { 1L, "Alice" },
                new Object[] { 2L, "Bob" }));

        HashJoinOperator join = new HashJoinOperator(drivers, DRIVERS_SCHEMA, "id", trips, TRIPS_SCHEMA, "driver_id");

        List<Object[]> result = Operator.drain(join);

        assertEquals(2, result.size());
        assertArrayEquals(new Object[] { 1L, "Alice", "Copenhagen", 1L }, result.get(0));
        assertArrayEquals(new Object[] { 2L, "Bob", "Aarhus", 2L }, result.get(1));
    }

    @Test
    void dropsProbeRowsWithNoMatchingBuildRow() {
        Operator trips = new TestListOperator(rows(
                new Object[] { "Copenhagen", 1L },
                new Object[] { "Odense", 99L })); // no driver 99
        Operator drivers = new TestListOperator(rows(
                new Object[] { 1L, "Alice" }));

        HashJoinOperator join = new HashJoinOperator(drivers, DRIVERS_SCHEMA, "id", trips, TRIPS_SCHEMA, "driver_id");

        List<Object[]> result = Operator.drain(join);

        assertEquals(1, result.size());
        assertArrayEquals(new Object[] { 1L, "Alice", "Copenhagen", 1L }, result.get(0));
    }

    @Test
    void dropsBuildRowsWithNoMatchingProbeRow() {
        Operator trips = new TestListOperator(rows(
                new Object[] { "Copenhagen", 1L }));
        Operator drivers = new TestListOperator(rows(
                new Object[] { 1L, "Alice" },
                new Object[] { 2L, "Bob" })); // never probed

        HashJoinOperator join = new HashJoinOperator(drivers, DRIVERS_SCHEMA, "id", trips, TRIPS_SCHEMA, "driver_id");

        List<Object[]> result = Operator.drain(join);

        assertEquals(1, result.size());
        assertArrayEquals(new Object[] { 1L, "Alice", "Copenhagen", 1L }, result.get(0));
    }

    @Test
    void oneBuildKeyMatchesMultipleProbeRows() {
        Operator trips = new TestListOperator(rows(
                new Object[] { "Copenhagen", 1L },
                new Object[] { "Roskilde", 1L }));
        Operator drivers = new TestListOperator(rows(
                new Object[] { 1L, "Alice" }));

        HashJoinOperator join = new HashJoinOperator(drivers, DRIVERS_SCHEMA, "id", trips, TRIPS_SCHEMA, "driver_id");

        List<Object[]> result = Operator.drain(join);

        assertEquals(2, result.size());
        assertArrayEquals(new Object[] { 1L, "Alice", "Copenhagen", 1L }, result.get(0));
        assertArrayEquals(new Object[] { 1L, "Alice", "Roskilde", 1L }, result.get(1));
    }

    @Test
    void oneProbeKeyMatchesMultipleBuildRows() {
        Operator trips = new TestListOperator(rows(
                new Object[] { "Copenhagen", 1L }));
        Operator drivers = new TestListOperator(rows(
                new Object[] { 1L, "Alice" },
                new Object[] { 1L, "Alice2" })); // duplicate key on build side

        HashJoinOperator join = new HashJoinOperator(drivers, DRIVERS_SCHEMA, "id", trips, TRIPS_SCHEMA, "driver_id");

        List<Object[]> result = Operator.drain(join);

        assertEquals(2, result.size());
        assertArrayEquals(new Object[] { 1L, "Alice", "Copenhagen", 1L }, result.get(0));
        assertArrayEquals(new Object[] { 1L, "Alice2", "Copenhagen", 1L }, result.get(1));
    }

    @Test
    void emptyBuildSideYieldsNoRows() {
        Operator trips = new TestListOperator(rows(
                new Object[] { "Copenhagen", 1L }));
        Operator drivers = new TestListOperator(rows());

        HashJoinOperator join = new HashJoinOperator(drivers, DRIVERS_SCHEMA, "id", trips, TRIPS_SCHEMA, "driver_id");

        assertTrue(Operator.drain(join).isEmpty());
    }

    @Test
    void emptyProbeSideYieldsNoRows() {
        Operator trips = new TestListOperator(rows());
        Operator drivers = new TestListOperator(rows(
                new Object[] { 1L, "Alice" }));

        HashJoinOperator join = new HashJoinOperator(drivers, DRIVERS_SCHEMA, "id", trips, TRIPS_SCHEMA, "driver_id");

        assertTrue(Operator.drain(join).isEmpty());
    }

    @Test
    void rejectsUnknownBuildColumn() {
        Operator trips = new TestListOperator(rows());
        Operator drivers = new TestListOperator(rows());

        HashJoinOperator join = new HashJoinOperator(drivers, DRIVERS_SCHEMA, "ghost_column", trips, TRIPS_SCHEMA,
                "driver_id");

        assertThrows(IllegalArgumentException.class, join::open);
    }
}

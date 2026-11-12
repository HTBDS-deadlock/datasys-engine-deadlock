package dk.itu.datasys;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Exercise 10, points 2 and 4: Planner folds a multi-table FROM list
 * left-deep into a chain of HashJoinOperators, and each table's own
 * ScanStats stays individually visible in Plan.stats().
 */
class PlannerJoinTest {

    private static final List<ColumnSpec> TRIPS_COLUMNS = List.of(
            new ColumnSpec("distance", ColumnType.LONG),
            new ColumnSpec("driver_id", ColumnType.LONG));

    private static final List<ColumnSpec> DRIVERS_COLUMNS = List.of(
            new ColumnSpec("id", ColumnType.LONG),
            new ColumnSpec("name", ColumnType.STRING));

    private static final List<ColumnSpec> TRIPS_WITH_CITY_COLUMNS = List.of(
            new ColumnSpec("distance", ColumnType.LONG),
            new ColumnSpec("driver_id", ColumnType.LONG),
            new ColumnSpec("city_code", ColumnType.LONG));

    private static final List<ColumnSpec> CITIES_COLUMNS = List.of(
            new ColumnSpec("code", ColumnType.LONG),
            new ColumnSpec("name", ColumnType.STRING));

    private StorageEngine twoTableEngine(Path dataDirectory) throws IOException {
        StorageEngine engine = new StorageEngine(dataDirectory);
        engine.createTable("trips", TRIPS_COLUMNS);
        engine.createTable("drivers", DRIVERS_COLUMNS);

        Path tripsCsv = dataDirectory.resolve("trips.csv");
        Files.writeString(tripsCsv, """
                12,1
                187,2
                95,1
                30,3
                """);
        engine.copyFile("trips", tripsCsv.toString());

        Path driversCsv = dataDirectory.resolve("drivers.csv");
        Files.writeString(driversCsv, """
                1,Alice
                2,Bob
                4,Carol
                """);
        engine.copyFile("drivers", driversCsv.toString());

        return engine;
    }

    @Test
    void joinsTwoTablesLeftDeepAndDropsUnmatchedRows(@TempDir Path dataDirectory) throws IOException {
        StorageEngine engine = twoTableEngine(dataDirectory);
        Planner planner = new Planner(engine);
        SelectStatement statement = new SelectStatement(List.of("trips", "drivers"), List.of(),
                List.of(new JoinPredicate("driver_id", "id")));

        Plan plan = planner.plan(statement);
        List<Object[]> rows = Operator.drain(plan.root());

        // trips.driver_id=3 has no matching driver, and drivers.id=4 (Carol) has
        // no matching trip -- both are dropped (inner join only).
        assertEquals(3, rows.size());
        assertArrayEquals(new Object[] { 12L, 1L, 1L, "Alice" }, rows.get(0));
        assertArrayEquals(new Object[] { 95L, 1L, 1L, "Alice" }, rows.get(1));
        assertArrayEquals(new Object[] { 187L, 2L, 2L, "Bob" }, rows.get(2));
    }

    @Test
    void perTablePruningStaysVisibleInPlanStats(@TempDir Path dataDirectory) throws IOException {
        StorageEngine engine = twoTableEngine(dataDirectory);
        Planner planner = new Planner(engine);
        SelectStatement statement = new SelectStatement(List.of("trips", "drivers"),
                List.of(new Predicate("distance", Comparison.GREATER_THAN, 50L)),
                List.of(new JoinPredicate("driver_id", "id")));

        Plan plan = planner.plan(statement);

        // One ScanStats per table, in FROM order: trips (filtered) then drivers
        // (no filter of its own).
        assertEquals(2, plan.stats().size());
        assertEquals(1, plan.stats().get(0).partitionsTotal());
        assertEquals(1, plan.stats().get(1).partitionsTotal());
    }

    @Test
    void foldsThreeTablesLeftDeepUsingTheAccumulatedJoinAsTheBuildSide(@TempDir Path dataDirectory)
            throws IOException {
        StorageEngine engine = new StorageEngine(dataDirectory);
        engine.createTable("trips", TRIPS_WITH_CITY_COLUMNS);
        engine.createTable("drivers", DRIVERS_COLUMNS);
        engine.createTable("cities", CITIES_COLUMNS);

        Path tripsCsv = dataDirectory.resolve("trips.csv");
        Files.writeString(tripsCsv, """
                12,1,10
                187,2,20
                95,1,10
                """);
        engine.copyFile("trips", tripsCsv.toString());

        Path driversCsv = dataDirectory.resolve("drivers.csv");
        Files.writeString(driversCsv, """
                1,Alice
                2,Bob
                """);
        engine.copyFile("drivers", driversCsv.toString());

        Path citiesCsv = dataDirectory.resolve("cities.csv");
        Files.writeString(citiesCsv, """
                10,Copenhagen
                20,Aarhus
                """);
        engine.copyFile("cities", citiesCsv.toString());

        Planner planner = new Planner(engine);
        // city_code belongs to trips (the base table), so the second fold step
        // must resolve its build column against the accumulated trips+drivers
        // schema, not just drivers' own schema.
        SelectStatement statement = new SelectStatement(List.of("trips", "drivers", "cities"), List.of(),
                List.of(new JoinPredicate("driver_id", "id"), new JoinPredicate("city_code", "code")));

        Plan plan = planner.plan(statement);
        List<Object[]> rows = Operator.drain(plan.root());

        assertEquals(3, plan.stats().size());
        assertEquals(3, rows.size());
        assertArrayEquals(new Object[] { 12L, 1L, 10L, 1L, "Alice", 10L, "Copenhagen" }, rows.get(0));
        assertArrayEquals(new Object[] { 95L, 1L, 10L, 1L, "Alice", 10L, "Copenhagen" }, rows.get(1));
        assertArrayEquals(new Object[] { 187L, 2L, 20L, 2L, "Bob", 20L, "Aarhus" }, rows.get(2));
    }
}

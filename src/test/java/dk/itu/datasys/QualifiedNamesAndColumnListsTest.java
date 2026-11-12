package dk.itu.datasys;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Exercise 10, optional extensions (section 6): qualified column names, table
 * aliases (including self-joins), and column lists over joins. Runs over the
 * section 5 testbed.
 */
class QualifiedNamesAndColumnListsTest {

    private final SqlParser parser = new SqlParser();
    private final SqlPrinter printer = new SqlPrinter();

    // ---- parser and printer ------------------------------------------------

    @Test
    void parsesColumnListAliasesAndQualifiedNames() {
        SelectStatement select = (SelectStatement) parser.parse(
                "SELECT c.c_name, o_quantity FROM orders, customers AS c "
                        + "WHERE o_custname = c.c_name AND c.c_city = 'Aarhus';").get(0);

        assertEquals(List.of(new TableRef("orders"), new TableRef("customers", "c")), select.tables());
        assertEquals(List.of("c.c_name", "o_quantity"), select.columns());
        assertEquals(List.of(new JoinPredicate("o_custname", "c.c_name")), select.joins());
        assertEquals(List.of(new Predicate("c.c_city", Comparison.EQUALS, "Aarhus")), select.filters());
        assertEquals(List.of("orders", "customers"), select.tableNames());
        assertEquals(List.of("orders", "c"), select.aliases());
    }

    @Test
    void selectStarHasEmptyColumnList() {
        SelectStatement select = (SelectStatement) parser.parse("SELECT * FROM orders o;").get(0);
        assertEquals(List.of(), select.columns());
        assertEquals(List.of(new TableRef("orders", "o")), select.tables());
    }

    @Test
    void roundTripsSelfJoinWithColumnList() {
        SelectStatement statement = new SelectStatement(
                List.of(new TableRef("customers", "c1"), new TableRef("customers", "c2")),
                List.of("c1.c_name", "c2.c_name"),
                List.of(new Predicate("c1.c_name", Comparison.EQUALS, "Anna")),
                List.of(new JoinPredicate("c1.c_city", "c2.c_city")));

        assertEquals(List.of(statement), parser.parse(printer.print(statement)));
    }

    @Test
    void deleteRejectsQualifiedColumnName() {
        assertThrows(SqlParseException.class,
                () -> parser.parse("DELETE FROM orders WHERE orders.o_quantity > 1;"));
    }

    // ---- column resolution -------------------------------------------------

    @Test
    void joinedSchemaResolvesQualifiedNamesInSelfJoin() {
        List<ColumnSpec> customers = List.of(
                new ColumnSpec("c_name", ColumnType.STRING),
                new ColumnSpec("c_city", ColumnType.STRING));
        JoinedSchema schema = new JoinedSchema(List.of("c1", "c2"), List.of(customers, customers));

        assertEquals(0, schema.tableIndexOf("c1.c_city"));
        assertEquals(1, schema.tableIndexOf("c2.c_city"));
        assertEquals(1, schema.rowIndexOf("c1.c_city"));
        assertEquals(3, schema.rowIndexOf("c2.c_city"));
        assertEquals(1, schema.indexInTable("c2.c_city"));

        IllegalArgumentException ambiguous = assertThrows(IllegalArgumentException.class,
                () -> schema.tableIndexOf("c_city"));
        assertTrue(ambiguous.getMessage().contains("ambiguous"));
        assertThrows(IllegalArgumentException.class, () -> schema.tableIndexOf("c3.c_city"));
        assertThrows(IllegalArgumentException.class, () -> schema.tableIndexOf("c1.ghost"));
    }

    @Test
    void joinedSchemaRejectsDuplicateTableNames() {
        List<ColumnSpec> customers = List.of(new ColumnSpec("c_name", ColumnType.STRING));
        assertThrows(IllegalArgumentException.class,
                () -> new JoinedSchema(List.of("customers", "customers"), List.of(customers, customers)));
    }

    // ---- end to end through the Executor -----------------------------------

    @Test
    void columnListOverJoin(@TempDir Path dir) throws IOException {
        assertEquals(List.of("Anna,1", "Anna,1", "Bob,2", "Carol,1"),
                select(dir, "SELECT c_name, o_quantity FROM orders, customers WHERE o_custname = c_name;"));
    }

    @Test
    void columnListReordersAndRepeatsColumns(@TempDir Path dir) throws IOException {
        assertEquals(List.of("Aarhus,Bob,Aarhus"),
                select(dir, "SELECT c_city, c_name, c_city FROM customers WHERE c_name = 'Bob';"));
    }

    @Test
    void qualifiedNamesWithAliasesAndPushedDownFilter(@TempDir Path dir) throws IOException {
        assertEquals(List.of("100,Copenhagen", "102,Copenhagen", "103,Copenhagen"),
                select(dir, "SELECT o.o_orderkey, c.c_city FROM orders o, customers c "
                        + "WHERE o.o_custname = c.c_name AND c.c_city = 'Copenhagen';"));
    }

    @Test
    void qualifiedNameWithoutAliasUsesTableName(@TempDir Path dir) throws IOException {
        assertEquals(List.of("Bob,Aarhus"),
                select(dir, "SELECT * FROM customers WHERE customers.c_city = 'Aarhus';"));
    }

    @Test
    void selfJoinPairsCustomersInTheSameCity(@TempDir Path dir) throws IOException {
        assertEquals(List.of("Anna,Anna", "Anna,Carol", "Bob,Bob", "Carol,Anna", "Carol,Carol"),
                select(dir, "SELECT c1.c_name, c2.c_name FROM customers c1, customers c2 "
                        + "WHERE c1.c_city = c2.c_city;"));
    }

    @Test
    void selfJoinBuildKeyUsesTheRightCopyOfADuplicatedColumn(@TempDir Path dir) throws IOException {
        // The build side is c1 ++ c2, which holds c_name twice. The third join
        // must key on c2.c_name (row index 2), not the first c_name (index 0).
        assertEquals(List.of(
                "Anna,Anna,100", "Anna,Anna,103", "Anna,Carol,102",
                "Bob,Bob,101",
                "Carol,Anna,100", "Carol,Anna,103", "Carol,Carol,102"),
                select(dir, "SELECT c1.c_name, c2.c_name, o_orderkey FROM customers c1, customers c2, orders "
                        + "WHERE c1.c_city = c2.c_city AND c2.c_name = o_custname;"));
    }

    @Test
    void selfJoinFilterOnOneCopyOnly(@TempDir Path dir) throws IOException {
        assertEquals(List.of("Carol,Anna", "Carol,Carol"),
                select(dir, "SELECT c1.c_name, c2.c_name FROM customers c1, customers c2 "
                        + "WHERE c1.c_city = c2.c_city AND c1.c_name = 'Carol';"));
    }

    @Test
    void bindErrors(@TempDir Path dir) throws IOException {
        Executor executor = testbed(dir);
        // Bare name in a self-join is ambiguous.
        assertThrows(IllegalArgumentException.class, () -> executor.run(
                "SELECT * FROM customers c1, customers c2 WHERE c_city = c_city;"));
        // Same table twice without aliases.
        assertThrows(IllegalArgumentException.class, () -> executor.run(
                "SELECT * FROM customers, customers WHERE c_city = c_city;"));
        // Unknown alias.
        assertThrows(IllegalArgumentException.class, () -> executor.run(
                "SELECT x.c_name FROM customers c;"));
        // With an alias, the table name no longer qualifies (as in DuckDB).
        assertThrows(IllegalArgumentException.class, () -> executor.run(
                "SELECT customers.c_name FROM customers c;"));
        // Column exists, but not in the qualifying table.
        assertThrows(IllegalArgumentException.class, () -> executor.run(
                "SELECT * FROM orders o, customers c WHERE o.o_custname = c.o_custname;"));
        // Unknown column in the SELECT list.
        assertThrows(IllegalArgumentException.class, () -> executor.run(
                "SELECT ghost FROM orders, customers WHERE o_custname = c_name;"));
    }

    // ---- helpers -----------------------------------------------------------

    /** Runs the query over a fresh testbed and returns its rows as sorted CSV lines. */
    private List<String> select(Path dir, String sql) throws IOException {
        List<Object[]> rows = testbed(dir).run(sql).get(0).rows().orElseThrow();
        return rows.stream()
                .map(row -> Arrays.stream(row).map(String::valueOf).collect(Collectors.joining(",")))
                .sorted()
                .toList();
    }

    /** The section 5 testbed: orders, products, customers, cities. */
    private Executor testbed(Path dir) throws IOException {
        Path orders = Files.writeString(dir.resolve("orders.csv"),
                "100,Anna,Bike,1\n101,Bob,Helmet,2\n102,Carol,Bike,1\n103,Anna,Helmet,1\n");
        Path products = Files.writeString(dir.resolve("products.csv"), "Bike,2999.0\nHelmet,299.0\n");
        Path customers = Files.writeString(dir.resolve("customers.csv"),
                "Anna,Copenhagen\nBob,Aarhus\nCarol,Copenhagen\n");
        Path cities = Files.writeString(dir.resolve("cities.csv"),
                "Copenhagen,Denmark,1400000\nAarhus,Denmark,290000\n");

        Executor executor = new Executor(new StorageEngine(dir.resolve("data")));
        executor.run("""
                CREATE TABLE orders (o_orderkey LONG, o_custname STRING, o_prodname STRING, o_quantity LONG);
                CREATE TABLE products (p_name STRING, p_price DOUBLE);
                CREATE TABLE customers (c_name STRING, c_city STRING);
                CREATE TABLE cities (ct_city STRING, ct_country STRING, ct_population LONG);
                COPY orders FROM '%s';
                COPY products FROM '%s';
                COPY customers FROM '%s';
                COPY cities FROM '%s';
                """.formatted(slashes(orders), slashes(products), slashes(customers), slashes(cities)));
        return executor;
    }

    private static String slashes(Path path) {
        return path.toString().replace('\\', '/');
    }
}

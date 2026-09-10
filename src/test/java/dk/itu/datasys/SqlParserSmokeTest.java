package dk.itu.datasys;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

class SqlParserSmokeTest {
    @Test
    void parsesAllFourStatementsToExpectedAST() {
        String sql = """
                CREATE TABLE trips (city STRING, distance LONG, price DOUBLE);
                COPY trips FROM 'trips.csv';
                SELECT * FROM trips WHERE distance > 100;
                SELECT * FROM trips;              -- WHERE is optional, as in DuckDB
                """;

        List<Statement> statements = new SqlParser().parse(sql);

        assertEquals(4, statements.size());
        assertInstanceOf(CreateTableStatement.class, statements.get(0));
        assertInstanceOf(CopyStatement.class, statements.get(1));

        SelectStatement withPredicate = (SelectStatement) statements.get(2);
        assertEquals("trips", withPredicate.tableName());
        assertEquals(new Predicate("distance", Comparison.GREATER_THAN, 100L), withPredicate.filters());

        SelectStatement withoutPredicate = (SelectStatement) statements.get(3);
        assertNull(withoutPredicate.filters());
    }

    @Test
    void throwsSqlParseExceptionOnSyntaxError() {
        assertThrows(SqlParseException.class, () -> new SqlParser().parse("SELECT FROM;"));
    }

    @Test
    void literalsAreTypedByShape() {
        String sql = """
                SELECT * FROM trips WHERE a = 12;
                SELECT * FROM trips WHERE a = 12.0;
                SELECT * FROM trips WHERE a = '12';
                SELECT * FROM trips WHERE a = -1;
                SELECT * FROM trips WHERE a = -1.5;
                """;

        List<Statement> statements = new SqlParser().parse(sql);

        assertEquals(12L, filterConstant(statements.get(0)));
        assertEquals(12.0, filterConstant(statements.get(1)));
        assertEquals("12", filterConstant(statements.get(2)));
        assertEquals(-1L, filterConstant(statements.get(3)));
        assertEquals(-1.5, filterConstant(statements.get(4)));
    }

    private Object filterConstant(Statement statement) {
        return ((SelectStatement) statement).filters().constant();
    }

    @Test
    void keywordsAreCaseInsensitiveButIdentifierCasingIsPreserved() {
        List<Statement> statements = new SqlParser().parse("select * from Trips;");

        SelectStatement select = (SelectStatement) statements.get(0);
        assertEquals("Trips", select.tableName());
    }

    @Test
    void malformedInput_missingSemicolon_reportsLineAndColumn() {
        SqlParseException e = assertThrows(SqlParseException.class,
                () -> new SqlParser().parse("SELECT * FROM trips"));
        assertEquals(1, e.line());
        assertEquals(19, e.column());
    }

    @Test
    void malformedInput_unbalancedParens_reportsLineAndColumn() {
        SqlParseException e = assertThrows(SqlParseException.class,
                () -> new SqlParser().parse("CREATE TABLE trips (city STRING;"));
        assertEquals(1, e.line());
        assertEquals(31, e.column());
    }

    @Test
    void malformedInput_unknownTypeName_reportsLineAndColumn() {
        SqlParseException e = assertThrows(SqlParseException.class,
                () -> new SqlParser().parse("CREATE TABLE trips (city TEXT);"));
        assertEquals(1, e.line());
        assertEquals(25, e.column());
    }

    @Test
    void malformedInput_unterminatedStringLiteral_reportsLineAndColumn() {
        SqlParseException e = assertThrows(SqlParseException.class,
                () -> new SqlParser().parse("COPY trips FROM 'trips.csv;"));
        assertEquals(1, e.line());
        assertEquals(16, e.column());
    }

    @Test
    void malformedInput_missingFrom_reportsLineAndColumn() {
        SqlParseException e = assertThrows(SqlParseException.class,
                () -> new SqlParser().parse("SELECT * trips;"));
        assertEquals(1, e.line());
        assertEquals(9, e.column());
    }

    @Test
    void commentsAndWhitespaceAreSkipped() {
        String sql = """


                -- a leading comment on its own line
                CREATE TABLE trips (city STRING);   -- trailing comment

                -- another comment
                SELECT * FROM trips;
                """;

        List<Statement> statements = new SqlParser().parse(sql);

        assertEquals(2, statements.size());
        assertInstanceOf(CreateTableStatement.class, statements.get(0));
        assertInstanceOf(SelectStatement.class, statements.get(1));
    }
}

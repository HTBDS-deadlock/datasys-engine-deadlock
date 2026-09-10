package dk.itu.datasys;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

class SqlParserSmokeTest {
    @Test
    void parsesAllFourStatements() {
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
}

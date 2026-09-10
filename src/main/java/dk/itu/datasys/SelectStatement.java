package dk.itu.datasys;

public record SelectStatement(String tableName, Predicate filters)
        implements Statement {
}

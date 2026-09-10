package dk.itu.datasys;

import java.util.stream.Collectors;

public final class SqlPrinter {
    /** Renders a statement back to SQL text that parses to an equal statement. */
    public String print(Statement s) {
        return switch (s) {
            case CreateTableStatement stmt -> printCreateTable(stmt);
            case CopyStatement stmt -> printCopy(stmt);
            case SelectStatement stmt -> printSelect(stmt);
        };
    }

    private String printCreateTable(CreateTableStatement stmt) {
        String columns = stmt.columns().stream()
                .map(c -> c.name() + " " + c.type())
                .collect(Collectors.joining(", "));
        return "CREATE TABLE " + stmt.tableName() + " (" + columns + ");";
    }

    private String printCopy(CopyStatement stmt) {
        return "COPY " + stmt.tableName() + " FROM " + quote(stmt.csvFilePath()) + ";";
    }

    private String printSelect(SelectStatement stmt) {
        String sql = "SELECT * FROM " + stmt.tableName();
        if (stmt.filters() != null) {
            sql += " WHERE " + printPredicate(stmt.filters());
        }
        return sql + ";";
    }

    private String printPredicate(Predicate predicate) {
        return predicate.columnName() + " " + symbol(predicate.comparison()) + " " + literal(predicate.constant());
    }

    private String symbol(Comparison comparison) {
        return switch (comparison) {
            case EQUALS -> "=";
            case LESS_THAN -> "<";
            case GREATER_THAN -> ">";
        };
    }

    private String literal(Object constant) {
        if (constant instanceof String value) {
            return quote(value);
        }
        return constant.toString();
    }

    private String quote(String value) {
        return "'" + value + "'";
    }
}

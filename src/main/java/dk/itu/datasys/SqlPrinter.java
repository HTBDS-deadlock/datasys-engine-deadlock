package dk.itu.datasys;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public final class SqlPrinter {
    /** Renders a statement back to SQL text that parses to an equal statement. */
    public String print(Statement s) {
        return switch (s) {
            case CreateTableStatement stmt -> printCreateTable(stmt);
            case CopyStatement stmt -> printCopy(stmt);
            case SelectStatement stmt -> printSelect(stmt);
            case DeleteStatement stmt -> printDelete(stmt);
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
        String tables = String.join(", ", stmt.tableNames());
        String sql = "SELECT * FROM " + tables;

        List<String> conditions = new ArrayList<>();
        for (Predicate filter : stmt.filters()) {
            conditions.add(printPredicate(filter));
        }
        for (JoinPredicate join : stmt.joins()) {
            conditions.add(join.leftColumn() + " = " + join.rightColumn());
        }
        if (!conditions.isEmpty()) {
            sql += " WHERE " + String.join(" AND ", conditions);
        }
        return sql + ";";
    }

    private String printDelete(DeleteStatement stmt) {
        String sql = "DELETE FROM " + stmt.tableName();
        if (stmt.where().isPresent()) {
            sql += " WHERE " + printPredicate(stmt.where().get());
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
        } else if (constant instanceof Double value) {
            String bidDecimal = BigDecimal.valueOf(value).toPlainString();
            return bidDecimal.contains(".") ? bidDecimal : bidDecimal + ".0";

        }
        return constant.toString();
    }

    private String quote(String value) {
        return "'" + value + "'";
    }
}

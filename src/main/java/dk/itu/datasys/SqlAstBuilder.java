package dk.itu.datasys;

import java.util.List;

import dk.itu.datasys.sql.sqlBaseVisitor;
import dk.itu.datasys.sql.sqlParser;

public class SqlAstBuilder extends sqlBaseVisitor<Statement> {
    @Override
    public Statement visitCopy(sqlParser.CopyContext ctx) {
        String tableName = ctx.IDENTIFIER().getText();
        String csvFilePath = stripQuotes(ctx.STRING_LITERAL().getText());
        return new CopyStatement(tableName, csvFilePath);
    }

    @Override
    public Statement visitCreateTable(sqlParser.CreateTableContext ctx) {
        String tableName = ctx.IDENTIFIER().getText();
        List<ColumnSpec> columns = ctx.columnDef().stream()
                .map(this::buildColumnSpec)
                .toList();
        return new CreateTableStatement(tableName, columns);
    }

    @Override
    public Statement visitSelect(sqlParser.SelectContext ctx) {
        // SelectContext : SELECT, FROM, IDENTIFIER, WHERE etc.
        String tableName = ctx.IDENTIFIER().getText();
        Predicate filters = ctx.predicate() != null
                ? buildPredicate(ctx.predicate())
                : null;
        return new SelectStatement(tableName, filters);
    }

    private Predicate buildPredicate(sqlParser.PredicateContext ctx) {
        String columnName = ctx.IDENTIFIER().getText();
        Comparison comparison = switch (ctx.comparison.getText()) {
            case "=" -> Comparison.EQUALS;
            case "<" -> Comparison.LESS_THAN;
            case ">" -> Comparison.GREATER_THAN;
            default -> throw new IllegalStateException("Unknown comparison: " + ctx.comparison.getText());
        };
        Object constant = literalValue(ctx.literal());
        return new Predicate(columnName, comparison, constant);
    }

    private Object literalValue(sqlParser.LiteralContext ctx) {
        if (ctx.STRING_LITERAL() != null) {
            return stripQuotes(ctx.STRING_LITERAL().getText());
        }
        if (ctx.LONG_LITERAL() != null) {
            return Long.parseLong(ctx.LONG_LITERAL().getText());
        }
        return Double.parseDouble(ctx.DOUBLE_LITERAL().getText());
    }

    private ColumnSpec buildColumnSpec(sqlParser.ColumnDefContext ctx) {
        String name = ctx.IDENTIFIER().getText();
        ColumnType type = columnType(ctx.columnType());
        return new ColumnSpec(name, type);
    }

    private ColumnType columnType(sqlParser.ColumnTypeContext ctx) {
        if (ctx.STRING() != null) {
            return ColumnType.STRING;
        }
        if (ctx.LONG() != null) {
            return ColumnType.LONG;
        }
        return ColumnType.DOUBLE;
    }

    private String stripQuotes(String text) {
        return text.substring(1, text.length() - 1);
    }
}

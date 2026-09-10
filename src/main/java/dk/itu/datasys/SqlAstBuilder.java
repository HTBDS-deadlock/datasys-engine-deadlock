package dk.itu.datasys;

import dk.itu.datasys.sql.sqlBaseVisitor;
import dk.itu.datasys.sql.sqlParser;

public class SqlAstBuilder extends sqlBaseVisitor<Statement> {
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
            String text = ctx.STRING_LITERAL().getText();
            return text.substring(1, text.length() - 1);
        }
        if (ctx.LONG_LITERAL() != null) {
            return Long.parseLong(ctx.LONG_LITERAL().getText());
        }
        return Double.parseDouble(ctx.DOUBLE_LITERAL().getText());
    }

}

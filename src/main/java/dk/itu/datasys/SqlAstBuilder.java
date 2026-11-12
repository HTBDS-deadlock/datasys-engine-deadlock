package dk.itu.datasys;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.antlr.v4.runtime.tree.TerminalNode;

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
        List<String> tableNames = ctx.IDENTIFIER().stream().map(TerminalNode::getText).toList();

        List<Predicate> filters = new ArrayList<>();
        List<JoinPredicate> joins = new ArrayList<>();
        if (ctx.condition() != null) {
            for (sqlParser.PredicateContext predicateCtx : ctx.condition().predicate()) {
                if (predicateCtx.filter() != null) {
                    filters.add(buildFilter(predicateCtx.filter()));
                } else {
                    joins.add(buildJoinPredicate(predicateCtx.join()));
                }
            }
        }
        return new SelectStatement(tableNames, filters, joins);
    }

    @Override
    public Statement visitDelete(sqlParser.DeleteContext ctx) {
        String tableName = ctx.IDENTIFIER().getText();
        Optional<Predicate> where = ctx.filter() != null
                ? Optional.of(buildFilter(ctx.filter()))
                : Optional.empty();
        return new DeleteStatement(tableName, where);
    }

    private JoinPredicate buildJoinPredicate(sqlParser.JoinContext ctx) {
        return new JoinPredicate(ctx.left.getText(), ctx.right.getText());
    }

    private Predicate buildFilter(sqlParser.FilterContext ctx) {
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

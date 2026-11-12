package dk.itu.datasys;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.antlr.v4.runtime.Token;

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
        List<TableRef> tables = ctx.tableRef().stream().map(this::buildTableRef).toList();
        // '*' has no columnRefs, so it yields the empty list that means "all columns".
        List<String> columns = ctx.selectList().columnRef().stream().map(this::columnRef).toList();

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
        return new SelectStatement(tables, columns, filters, joins);
    }

    @Override
    public Statement visitDelete(sqlParser.DeleteContext ctx) {
        String tableName = ctx.IDENTIFIER().getText();
        // DELETE keeps its restricted one-table form: no qualified column names.
        if (ctx.filter() != null && ctx.filter().columnRef().qualifier != null) {
            Token qualifier = ctx.filter().columnRef().qualifier;
            throw new SqlParseException("DELETE does not support qualified column names: "
                    + ctx.filter().columnRef().getText(), qualifier.getLine(), qualifier.getCharPositionInLine());
        }
        Optional<Predicate> where = ctx.filter() != null
                ? Optional.of(buildFilter(ctx.filter()))
                : Optional.empty();
        return new DeleteStatement(tableName, where);
    }

    private JoinPredicate buildJoinPredicate(sqlParser.JoinContext ctx) {
        return new JoinPredicate(columnRef(ctx.left), columnRef(ctx.right));
    }

    private TableRef buildTableRef(sqlParser.TableRefContext ctx) {
        String name = ctx.table.getText();
        return ctx.alias == null ? new TableRef(name) : new TableRef(name, ctx.alias.getText());
    }

    /** "column" or "qualifier.column", whitespace around the dot dropped. */
    private String columnRef(sqlParser.ColumnRefContext ctx) {
        String column = ctx.column.getText();
        return ctx.qualifier == null ? column : ctx.qualifier.getText() + "." + column;
    }

    private Predicate buildFilter(sqlParser.FilterContext ctx) {
        String columnName = columnRef(ctx.columnRef());
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

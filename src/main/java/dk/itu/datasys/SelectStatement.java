package dk.itu.datasys;

import java.util.ArrayList;
import java.util.List;

/**
 * tables is the FROM list. columns are the plain columns of the SELECT list;
 * aggregates its aggregate functions, each knowing its position in the list.
 * Both empty means {@code SELECT *}. filters are column-vs-constant
 * predicates (each binds to exactly one table); joins are column-vs-column
 * predicates. Both lists are AND-ed together; there is no OR and no
 * parentheses. groupBy is the GROUP BY list, empty without one.
 *
 * Every column reference (in columns, aggregates, filters, joins and groupBy)
 * is either a bare name ({@code c_city}) or qualified with a table alias
 * ({@code c1.c_city}).
 */
public record SelectStatement(List<TableRef> tables, List<String> columns, List<Predicate> filters,
        List<JoinPredicate> joins, List<Aggregate> aggregates, List<String> groupBy) implements Statement {

    /** Without aggregates or GROUP BY. */
    public SelectStatement(List<TableRef> tables, List<String> columns, List<Predicate> filters,
            List<JoinPredicate> joins) {
        this(tables, columns, filters, joins, List.of(), List.of());
    }

    /** SELECT * over plain table names, without aliases. */
    public SelectStatement(List<String> tableNames, List<Predicate> filters, List<JoinPredicate> joins) {
        this(tableNames.stream().map(TableRef::new).toList(), List.of(), filters, joins);
    }

    /** The stored tables, in FROM-list order (a self-join lists a table twice). */
    public List<String> tableNames() {
        return tables.stream().map(TableRef::name).toList();
    }

    /** The names columns are qualified with, in FROM-list order. */
    public List<String> aliases() {
        return tables.stream().map(TableRef::alias).toList();
    }

    /** True for SELECT *: no plain columns and no aggregates. */
    public boolean isSelectStar() {
        return columns.isEmpty() && aggregates.isEmpty();
    }

    /** True when the statement groups or aggregates: it then returns one row per group. */
    public boolean isAggregation() {
        return !aggregates.isEmpty() || !groupBy.isEmpty();
    }

    /**
     * The SELECT list in order, each item either a plain column name or an
     * Aggregate. Empty for SELECT *.
     */
    public List<Object> selectItems() {
        List<Object> items = new ArrayList<>();
        int column = 0;
        int aggregate = 0;
        for (int position = 0; position < columns.size() + aggregates.size(); position++) {
            if (aggregate < aggregates.size() && aggregates.get(aggregate).position() == position) {
                items.add(aggregates.get(aggregate++));
            } else {
                items.add(columns.get(column++));
            }
        }
        return items;
    }
}

package dk.itu.datasys;

import java.util.List;

/**
 * tables is the FROM list. columns is the SELECT list; empty means
 * {@code SELECT *}. filters are column-vs-constant predicates (each binds to
 * exactly one table); joins are column-vs-column predicates. Both lists are
 * AND-ed together; there is no OR and no parentheses.
 *
 * Every column reference (in columns, filters and joins) is either a bare
 * name ({@code c_city}) or qualified with a table alias ({@code c1.c_city}).
 */
public record SelectStatement(List<TableRef> tables, List<String> columns, List<Predicate> filters,
        List<JoinPredicate> joins) implements Statement {

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
}

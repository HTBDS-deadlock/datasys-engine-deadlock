package dk.itu.datasys;

import java.util.List;

/**
 * filters are column-vs-constant predicates (each binds to exactly one
 * table); joins are column-vs-column predicates. Both lists are AND-ed
 * together; there is no OR and no parentheses.
 */
public record SelectStatement(List<String> tableNames, List<Predicate> filters, List<JoinPredicate> joins)
        implements Statement {
}

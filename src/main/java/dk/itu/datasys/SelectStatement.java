package dk.itu.datasys;

import java.util.List;
import java.util.Optional;

/** columns is empty for SELECT *, or the requested column names in order. */
public record SelectStatement(String tableName, Optional<List<String>> columns, Optional<Predicate> filters)
        implements Statement {
}

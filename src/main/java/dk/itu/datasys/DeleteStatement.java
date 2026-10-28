package dk.itu.datasys;

import java.util.Optional;

public record DeleteStatement(String tableName, Optional<Predicate> where)
        implements Statement {
}

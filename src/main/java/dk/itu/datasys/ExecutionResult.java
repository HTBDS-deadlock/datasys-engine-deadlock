package dk.itu.datasys;

import java.util.List;
import java.util.Optional;

/** The statement that was run, and its rows if it was a SELECT. */
public record ExecutionResult(Statement statement, Optional<List<Object[]>> rows) {
}

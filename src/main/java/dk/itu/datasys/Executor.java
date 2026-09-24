package dk.itu.datasys;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Runs parse -> bind -> plan -> execute over a script, stopping at the first
 * error.
 */
public final class Executor {
    private final StorageEngine engine;
    private final SqlParser parser = new SqlParser();
    private final Binder binder;
    private final Planner planner;

    public Executor(StorageEngine engine) {
        this.engine = engine;
        this.binder = new Binder(engine);
        this.planner = new Planner(engine);
    }

    public List<ExecutionResult> run(String sqlText) {
        List<Statement> statements = parser.parse(sqlText);
        List<ExecutionResult> results = new ArrayList<>(statements.size());
        for (Statement statement : statements) {
            results.add(execute(statement));
        }
        return results;
    }

    private ExecutionResult execute(Statement statement) {
        binder.bind(statement);
        Optional<List<Object[]>> rows = switch (statement) {
            case CreateTableStatement s -> {
                engine.createTable(s.tableName(), s.columns());
                yield Optional.empty();
            }
            case CopyStatement s -> {
                engine.copyFile(s.tableName(), s.csvFilePath());
                yield Optional.empty();
            }
            case SelectStatement s -> Optional.of(Operator.drain(planner.plan(s).root()));
        };
        return new ExecutionResult(statement, rows);
    }
}

package dk.itu.datasys;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Runs parse -> bind -> plan -> execute over a script, stopping at the first
 * error.
 */
public final class Executor {
    private static final Logger LOGGER = LoggerFactory.getLogger(Executor.class);

    private final StorageEngine engine;
    private final SqlParser parser = new SqlParser();
    private final SqlPrinter printer = new SqlPrinter();
    private final Binder binder;
    private final Planner planner;

    public Executor(StorageEngine engine) {
        this.engine = engine;
        this.binder = new Binder(engine);
        this.planner = new Planner(engine);
    }

    public List<ExecutionResult> run(String sqlText) {
        // Script-level parsing happens before the counter starts, so it keeps
        // the 0 set at engine startup.
        List<Statement> statements = parser.parse(sqlText);
        List<ExecutionResult> results = new ArrayList<>(statements.size());
        int statementNumber = 0;
        try {
            for (Statement statement : statements) {
                MDC.put("statementNumber", String.valueOf(++statementNumber));
                results.add(execute(statement));
            }
        } finally {
            // Puts the 0 back so lines after the script (e.g. engine stopped)
            // are outside the count again.
            MDC.put("statementNumber", "0");
        }
        return results;
    }

    private ExecutionResult execute(Statement statement) {
        // console log only (stderr, see log4j2.xml) -- keeps stdout free for CSV rows
        LOGGER.debug("executing statement={}", printer.print(statement));
        binder.bind(statement);
        long start = System.currentTimeMillis();
        Optional<List<Object[]>> rows = switch (statement) {
            case CreateTableStatement s -> {
                engine.createTable(s.tableName(), s.columns());
                LOGGER.debug("statement=CREATE_TABLE table={} durationMs={}",
                        s.tableName(), System.currentTimeMillis() - start);
                yield Optional.empty();
            }
            case CopyStatement s -> {
                engine.copyFile(s.tableName(), s.csvFilePath());
                LOGGER.debug("statement=COPY table={} durationMs={}",
                        s.tableName(), System.currentTimeMillis() - start);
                yield Optional.empty();
            }
            case SelectStatement s -> {
                List<Object[]> result = Operator.drain(planner.plan(s).root());
                LOGGER.debug("statement=SELECT table={} rowsOut={} durationMs={}",
                        s.tableName(), result.size(), System.currentTimeMillis() - start);
                yield Optional.of(result);
            }
        };
        return new ExecutionResult(statement, rows);
    }
}

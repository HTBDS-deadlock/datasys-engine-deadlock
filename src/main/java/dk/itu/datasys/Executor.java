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
        int statementNumber = 0;
        List<Statement> statements = parser.parse(sqlText);
        List<ExecutionResult> results = new ArrayList<>(statements.size());
        try {
            for (Statement statement : statements) {
                MDC.put("statementNumber", String.valueOf(++statementNumber));
                try {
                    results.add(execute(statement));
                } catch (RuntimeException e) {
                    LOGGER.error("Statement failed reason ={}", e.getMessage());
                    throw e;
                }
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
        String str = printer.print(statement);
        String columnsWithoutCommas = str.replace(",", "");

        LOGGER.debug("executing statement={}", columnsWithoutCommas);
        Optional<StorageEngine.TableSnapshot> snapshot =
                engine.snapshotIfPresent(tableName(statement));
        binder.bind(statement, snapshot);
        long start = System.currentTimeMillis();
        Optional<List<Object[]>> rows = switch (statement) {
            case CreateTableStatement s -> {
                engine.createTable(s.tableName(), s.columns());
                // CREATE TABLE always produces the table's first version, 0
                LOGGER.debug("statement=CREATE_TABLE table={} version={} durationMs={}",
                        s.tableName(), 0, System.currentTimeMillis() - start);
                yield Optional.empty();
            }
            case CopyStatement s -> {
                CopyResult result = engine.copyFile(snapshot.orElseThrow(), s.csvFilePath());
                LOGGER.debug("statement=COPY table={} rows={} partitions={} version={} durationMs={}",
                        s.tableName(), result.rows(), result.partitions(), result.version(),
                        System.currentTimeMillis() - start);
                yield Optional.empty();
            }
            case DeleteStatement s -> {
                DeleteResult result = engine.delete(snapshot.orElseThrow(), s.where());
                LOGGER.debug(
                        "statement=DELETE table={} rowsDeleted={} partitionsRewritten={} partitionsCarried={} version={} durationMs={}",
                        s.tableName(), result.rowsDeleted(), result.partitionsRewritten(),
                        result.partitionsCarried(), result.version(), System.currentTimeMillis() - start);
                yield Optional.empty();
            }
            case SelectStatement s -> {
                StorageEngine.TableSnapshot pinned = snapshot.orElseThrow();
                int version = pinned.version();
                Plan plan = planner.plan(s, pinned);
                List<Object[]> result = Operator.drain(plan.root());
                LOGGER.debug(
                        "statement=SELECT table={} rowsOut={} partitionsRead={} partitionsPruned={} version={} durationMs={}",
                        s.tableName(), result.size(), plan.stats().partitionsRead(),
                        plan.stats().partitionsPruned(), version, System.currentTimeMillis() - start);
                yield Optional.of(result);
            }
        };
        return new ExecutionResult(statement, rows);
    }

    private static String tableName(Statement statement) {
        return switch (statement) {
            case CreateTableStatement s -> s.tableName();
            case CopyStatement s -> s.tableName();
            case DeleteStatement s -> s.tableName();
            case SelectStatement s -> s.tableName();
        };
    }
}

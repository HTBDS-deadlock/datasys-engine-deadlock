package dk.itu.datasys;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

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
        List<ExecutionResult> results = new ArrayList<>();
        run(sqlText, results::add);
        return results;
    }

    /**
     * Like run(String), but hands each statement's result to onResult as soon
     * as that statement finishes, so a caller still has the results of the
     * statements before a failing one.
     */
    public void run(String sqlText, Consumer<ExecutionResult> onResult) {
        // Script-level parsing happens before the counter starts, so it keeps
        // the 0 set at engine startup.
        int statementNumber = 0;
        List<Statement> statements = parser.parse(sqlText);
        try {
            for (Statement statement : statements) {
                MDC.put("statementNumber", String.valueOf(++statementNumber));
                try {
                    onResult.accept(execute(statement));
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
    }

    private ExecutionResult execute(Statement statement) {
        // console log only (stderr, see log4j2.xml) -- keeps stdout free for CSV rows
        String str = printer.print(statement);
        String columnsWithoutCommas = str.replace(",", "");

        LOGGER.debug("executing statement={}", columnsWithoutCommas);
        // Pinned once per table, at statement start, before binding or planning.
        Map<String, StorageEngine.TableSnapshot> snapshots = new LinkedHashMap<>();
        for (String tableName : tableNames(statement)) {
            engine.snapshotIfPresent(tableName).ifPresent(snapshot -> snapshots.put(tableName, snapshot));
        }
        binder.bind(statement, snapshots);
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
                CopyResult result = engine.copyFile(snapshots.get(s.tableName()), s.csvFilePath());
                LOGGER.debug("statement=COPY table={} rows={} partitions={} version={} durationMs={}",
                        s.tableName(), result.rows(), result.partitions(), result.version(),
                        System.currentTimeMillis() - start);
                yield Optional.empty();
            }
            case DeleteStatement s -> {
                DeleteResult result = engine.delete(snapshots.get(s.tableName()), s.where());
                LOGGER.debug(
                        "statement=DELETE table={} rowsDeleted={} partitionsRewritten={} partitionsCarried={} version={} durationMs={}",
                        s.tableName(), result.rowsDeleted(), result.partitionsRewritten(),
                        result.partitionsCarried(), result.version(), System.currentTimeMillis() - start);
                yield Optional.empty();
            }
            case SelectStatement s -> {
                Plan plan = planner.plan(s, snapshots);
                List<Object[]> result = Operator.drain(plan.root());
                int partitionsRead = plan.stats().stream().mapToInt(ScanStats::partitionsRead).sum();
                int partitionsPruned = plan.stats().stream().mapToInt(ScanStats::partitionsPruned).sum();
                LOGGER.debug(
                        "statement=SELECT tables={} rowsOut={} partitionsRead={} partitionsPruned={} durationMs={}",
                        s.tableNames(), result.size(), partitionsRead, partitionsPruned,
                        System.currentTimeMillis() - start);
                yield Optional.of(result);
            }
        };
        return new ExecutionResult(statement, rows);
    }

    private static List<String> tableNames(Statement statement) {
        return switch (statement) {
            case CreateTableStatement s -> List.of();
            case CopyStatement s -> List.of(s.tableName());
            case DeleteStatement s -> List.of(s.tableName());
            case SelectStatement s -> s.tableNames();
        };
    }
}

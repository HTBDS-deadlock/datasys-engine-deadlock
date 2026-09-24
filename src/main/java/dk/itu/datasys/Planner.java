package dk.itu.datasys;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turns a bound SELECT statement into an operator pipeline. This is where
 * partition pruning happens, based on each partition's min/max summary in the
 * catalog -- no data file is opened while planning.
 */
public final class Planner {
    private static final Logger LOGGER = LoggerFactory.getLogger(Planner.class);

    private final StorageEngine engine;

    public Planner(StorageEngine engine) {
        this.engine = engine;
    }

    public Plan plan(SelectStatement statement) {
        String tableName = statement.tableName();
        Optional<Predicate> filters = statement.filters();

        Operator root;
        ScanStats stats;
        if (filters.isEmpty()) {
            int partitionsTotal = engine.partitionCount(tableName);
            List<Integer> allPartitions = new ArrayList<>(partitionsTotal);
            for (int p = 0; p < partitionsTotal; p++) {
                allPartitions.add(p);
            }
            root = new ScanOperator(engine, tableName, allPartitions);
            stats = new ScanStats(partitionsTotal, partitionsTotal, 0);
        } else {
            Predicate predicate = filters.get();
            List<Object[]> columnStats = engine.columnStats(tableName, predicate.columnName());
            List<Integer> survivingPartitions = new ArrayList<>();
            for (int p = 0; p < columnStats.size(); p++) {
                Object[] minMax = columnStats.get(p);
                boolean prune = StorageEngine.canPrune(predicate.comparison(), predicate.constant(), minMax[0],
                        minMax[1]);
                LOGGER.debug("table={} column={} comparison={} const={} partition={} min={} max={} decision={}",
                        tableName, predicate.columnName(), predicate.comparison(), predicate.constant(),
                        p, minMax[0], minMax[1], prune ? "PRUNED" : "READ");
                if (!prune) {
                    survivingPartitions.add(p);
                }
            }

            int partitionsTotal = columnStats.size();
            int partitionsRead = survivingPartitions.size();
            stats = new ScanStats(partitionsTotal, partitionsRead, partitionsTotal - partitionsRead);

            Operator scan = new ScanOperator(engine, tableName, survivingPartitions);
            root = new FilterOperator(scan, engine.schema(tableName), predicate);
        }

        if (statement.columns().isPresent()) {
            root = new ProjectOperator(root, engine.schema(tableName), statement.columns().get());
        }
        return new Plan(root, stats);
    }
}

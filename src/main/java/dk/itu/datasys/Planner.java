package dk.itu.datasys;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turns a bound SELECT statement into an operator pipeline. This is where
 * partition pruning happens, based on each partition's min/max summary in the
 * catalog -- no data file is opened while planning. Multi-table SELECTs are
 * folded left-deep, in FROM-list order, into a chain of HashJoinOperators.
 */
public final class Planner {
    private static final Logger LOGGER = LoggerFactory.getLogger(Planner.class);

    private final StorageEngine engine;

    public Planner(StorageEngine engine) {
        this.engine = engine;
    }

    public Plan plan(SelectStatement statement) {
        Map<String, StorageEngine.TableSnapshot> snapshots = new HashMap<>();
        for (String tableName : statement.tableNames()) {
            snapshots.put(tableName, engine.snapshot(tableName));
        }
        return plan(statement, snapshots);
    }

    /** Convenience overload for single-table SELECTs with an already-pinned snapshot. */
    public Plan plan(SelectStatement statement, StorageEngine.TableSnapshot snapshot) {
        return plan(statement, Map.of(snapshot.tableName(), snapshot));
    }

    public Plan plan(SelectStatement statement, Map<String, StorageEngine.TableSnapshot> snapshots) {
        if (statement.tableNames().size() == 1 && statement.joins().isEmpty()) {
            ScanResult result = scanTable(snapshots.get(statement.tableNames().get(0)), statement.filters());
            return new Plan(result.root(), List.of(result.stats()));
        }
        if (statement.tableNames().size() == 1) {
            throw new UnsupportedOperationException("A single-table SELECT cannot have a join predicate");
        }
        return planJoin(statement, snapshots);
    }

    private Plan planJoin(SelectStatement statement, Map<String, StorageEngine.TableSnapshot> snapshots) {
        List<String> tableNames = statement.tableNames();
        List<StorageEngine.TableSnapshot> tableSnapshots = tableNames.stream().map(snapshots::get).toList();
        List<List<ColumnSpec>> perTableSchema = tableSnapshots.stream().map(engine::schema).toList();
        JoinedSchema joinedSchema = new JoinedSchema(tableNames, perTableSchema);

        // Each filter binds to exactly one table; partition them that way so
        // every table is pruned/scanned using only its own predicates.
        List<List<Predicate>> filtersPerTable = new ArrayList<>();
        for (int i = 0; i < tableNames.size(); i++) {
            filtersPerTable.add(new ArrayList<>());
        }
        for (Predicate predicate : statement.filters()) {
            int table = joinedSchema.tableIndexOf(predicate.columnName());
            filtersPerTable.get(table).add(predicate);
        }

        // Throws unless the join predicates fold left-to-right unambiguously.
        List<JoinPredicate> foldSteps = joinedSchema.resolveFoldOrder(statement.joins());

        List<ScanStats> stats = new ArrayList<>();
        ScanResult base = scanTable(tableSnapshots.get(0), filtersPerTable.get(0));
        stats.add(base.stats());
        Operator root = base.root();
        List<ColumnSpec> joinedSoFarSchema = perTableSchema.get(0);

        for (int i = 1; i < tableNames.size(); i++) {
            ScanResult next = scanTable(tableSnapshots.get(i), filtersPerTable.get(i));
            stats.add(next.stats());

            JoinPredicate predicate = foldSteps.get(i - 1);
            // The side whose column belongs to the new table is the probe
            // side; the other side (already in the joined set) is the build
            // side -- the accumulated join so far is always the build side.
            boolean leftIsNewTable = joinedSchema.tableIndexOf(predicate.leftColumn()) == i;
            String probeColumn = leftIsNewTable ? predicate.leftColumn() : predicate.rightColumn();
            String buildColumn = leftIsNewTable ? predicate.rightColumn() : predicate.leftColumn();

            root = new HashJoinOperator(root, joinedSoFarSchema, buildColumn, next.root(), perTableSchema.get(i),
                    probeColumn);

            List<ColumnSpec> combined = new ArrayList<>(joinedSoFarSchema);
            combined.addAll(perTableSchema.get(i));
            joinedSoFarSchema = combined;
        }

        return new Plan(root, stats);
    }

    private ScanResult scanTable(StorageEngine.TableSnapshot snapshot, List<Predicate> filters) {
        String tableName = snapshot.tableName();

        Operator root;
        ScanStats stats;
        if (filters.isEmpty()) {
            int partitionsTotal = engine.partitionCount(snapshot);
            List<Integer> allPartitions = new ArrayList<>(partitionsTotal);
            for (int p = 0; p < partitionsTotal; p++) {
                allPartitions.add(p);
                LOGGER.debug("table={}  partition={}  decision={} reason={}", tableName, p, "READ", "noPredicate");
            }
            root = new ScanOperator(engine, snapshot, allPartitions, null);
            stats = new ScanStats(partitionsTotal, partitionsTotal, 0);
        } else {
            int partitionsTotal = engine.partitionCount(snapshot);

            // columnStats per filter gives the finished list of min/max per
            // partition, e.g. for distance: [[12,31],[88,95],[140,187],[210,299]].
            List<List<Object[]>> columnStatsPerFilter = new ArrayList<>(filters.size());
            for (Predicate predicate : filters) {
                columnStatsPerFilter.add(engine.columnStats(snapshot, predicate.columnName()));
            }

            List<Integer> survivingPartitions = new ArrayList<>();
            // a partition is pruned if ANY filter's min/max rules it out.
            for (int p = 0; p < partitionsTotal; p++) {
                boolean prune = false;
                for (int i = 0; i < filters.size(); i++) {
                    Predicate predicate = filters.get(i);
                    Object[] minMax = columnStatsPerFilter.get(i).get(p); // [12,31]
                    boolean thisPrune = StorageEngine.canPrune(predicate.comparison(), predicate.constant(),
                            minMax[0], minMax[1]);
                    LOGGER.debug(
                            "table={} column={} comparison={} const={} partition={} min={} max={} decision={}",
                            tableName, predicate.columnName(), predicate.comparison(), predicate.constant(),
                            p, minMax[0], minMax[1], thisPrune ? "PRUNED" : "READ");
                    if (thisPrune) {
                        prune = true;
                        break;
                    }
                }
                if (!prune) {
                    survivingPartitions.add(p);
                }
            }

            int partitionsRead = survivingPartitions.size();
            stats = new ScanStats(partitionsTotal, partitionsRead, partitionsTotal - partitionsRead);

            Operator scan = new ScanOperator(engine, snapshot, survivingPartitions, null);
            root = scan;
            for (Predicate predicate : filters) {
                root = new FilterOperator(root, engine.schema(snapshot), predicate);
            }
        }

        return new ScanResult(root, stats);
    }

    private record ScanResult(Operator root, ScanStats stats) {
    }
}

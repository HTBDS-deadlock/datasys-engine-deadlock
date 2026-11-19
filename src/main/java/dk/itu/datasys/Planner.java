package dk.itu.datasys;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turns a bound SELECT statement into an operator pipeline. This is where
 * partition pruning happens, based on each partition's min/max summary in the
 * catalog -- no data file is opened while planning.
 *
 * Multi-table SELECTs are joined in an order chosen by the JoinMode:
 * OPTIMIZED estimates every table and join from catalog statistics (row
 * counts, NDV) and orders the joins greedily, smallest estimated intermediate
 * result first, with the smaller estimated input building each hash table;
 * SYNTAX folds left-deep in FROM-list order with the join so far always
 * building, exactly as in week 10. A final projection restores FROM-list
 * column order, so SELECT * does not depend on the plan.
 * See docs/optimizer-design.md.
 */
public final class Planner {
    private static final Logger LOGGER = LoggerFactory.getLogger(Planner.class);

    private final StorageEngine engine;
    private final JoinMode mode;

    public Planner(StorageEngine engine) {
        this(engine, JoinMode.OPTIMIZED);
    }

    public Planner(StorageEngine engine, JoinMode mode) {
        this.engine = engine;
        this.mode = mode;
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
        if (statement.tables().size() == 1 && !statement.joins().isEmpty()) {
            throw new UnsupportedOperationException("A single-table SELECT cannot have a join predicate");
        }

        int tableCount = statement.tables().size();
        // A self-join lists the same table twice; both occurrences read the same pinned snapshot.
        List<StorageEngine.TableSnapshot> tableSnapshots = statement.tableNames().stream().map(snapshots::get)
                .toList();
        List<List<ColumnSpec>> perTableSchema = tableSnapshots.stream().map(engine::schema).toList();
        JoinedSchema joinedSchema = new JoinedSchema(statement.aliases(), perTableSchema);

        // Each filter binds to exactly one table; partition them that way so
        // every table is pruned/scanned using only its own predicates. The
        // pushed-down copy uses the bare column name, which is what the
        // table's own schema and partition statistics know.
        List<List<Predicate>> filtersPerTable = new ArrayList<>();
        for (int i = 0; i < tableCount; i++) {
            filtersPerTable.add(new ArrayList<>());
        }
        for (Predicate predicate : statement.filters()) {
            int table = joinedSchema.tableIndexOf(predicate.columnName());
            filtersPerTable.get(table).add(new Predicate(JoinedSchema.bareName(predicate.columnName()),
                    predicate.comparison(), predicate.constant()));
        }

        // Scan (and estimate) every table in FROM-list order, so the ScanStats
        // keep FROM-list order whatever the join order.
        List<ScanStats> stats = new ArrayList<>();
        List<ScanResult> scans = new ArrayList<>();
        List<JoinOrderer.TableEstimate> estimates = new ArrayList<>();
        for (int i = 0; i < tableCount; i++) {
            ScanResult scan = scanTable(tableSnapshots.get(i), statement.aliases().get(i), filtersPerTable.get(i));
            scans.add(scan);
            stats.add(scan.stats());
            estimates.add(scan.estimate());
        }

        JoinOrderer.JoinOrder order = joinOrder(statement, joinedSchema, estimates);

        // Build the tree. layout lists the FROM positions whose columns make up
        // the current row, in row order; it locates join keys and output columns.
        int first = order.tables().get(0);
        Operator root = scans.get(first).root();
        List<Integer> layout = new ArrayList<>(List.of(first));
        String shape = statement.aliases().get(first);
        long currentEst = estimates.get(first).rows();

        for (JoinOrderer.Step step : order.steps()) {
            int table = step.table();
            JoinOrderer.JoinEdge edge = step.edge();
            int planSide = edge.other(table);
            String planColumn = qualified(statement, planSide, edge.columnOf(planSide));
            String tableColumn = qualified(statement, table, edge.columnOf(table));
            long tableEst = estimates.get(table).rows();

            // The smaller estimated input builds; on a tie the join so far builds.
            // -j syntax: the join so far always builds, as in week 10.
            boolean planBuilds = mode == JoinMode.SYNTAX || currentEst <= tableEst;
            List<Integer> tableLayout = List.of(table);
            int planKey = rowIndexOf(planColumn, layout, joinedSchema, perTableSchema);
            int tableKey = joinedSchema.indexInTable(tableColumn);

            List<Integer> joinedLayout = new ArrayList<>(planBuilds ? layout : tableLayout);
            joinedLayout.addAll(planBuilds ? tableLayout : layout);
            String tables = String.join(";", joinedLayout.stream().map(statement.aliases()::get).toList());

            if (planBuilds) {
                root = new HashJoinOperator(root, planKey, planColumn, scans.get(table).root(), tableKey,
                        tableColumn, tables, step.estRows());
                shape = "J(" + shape + " " + statement.aliases().get(table) + ")";
            } else {
                root = new HashJoinOperator(scans.get(table).root(), tableKey, tableColumn, root, planKey,
                        planColumn, tables, step.estRows());
                shape = "J(" + statement.aliases().get(table) + " " + shape + ")";
            }
            layout = joinedLayout;
            currentEst = step.estRows();
        }

        if (statement.isAggregation()) {
            Aggregation aggregation = aggregate(statement, root, layout, joinedSchema, perTableSchema, estimates,
                    currentEst);
            root = aggregation.root();
            shape = "Agg(" + shape + ")";
            if (!isIdentity(aggregation.outputIndexes())) {
                root = new ProjectOperator(root, aggregation.outputIndexes());
            }
        } else {
            // Output columns: the SELECT list, or every column in FROM-list order
            // for SELECT *, located in the row the plan actually produces.
            List<Integer> columnIndexes = new ArrayList<>();
            if (statement.isSelectStar()) {
                for (int t = 0; t < tableCount; t++) {
                    int offset = offsetOf(t, layout, perTableSchema);
                    for (int c = 0; c < perTableSchema.get(t).size(); c++) {
                        columnIndexes.add(offset + c);
                    }
                }
            } else {
                for (String column : statement.columns()) {
                    columnIndexes.add(rowIndexOf(column, layout, joinedSchema, perTableSchema));
                }
            }
            if (!statement.isSelectStar() || !isIdentity(columnIndexes)) {
                root = new ProjectOperator(root, columnIndexes);
            }
        }

        List<String> joinOrder = order.tables().stream().map(statement.aliases()::get).toList();
        return new Plan(root, stats, joinOrder, mode, shape);
    }

    /**
     * The HashAggregateOperator over the joined rows, and where each SELECT
     * item sits in its output (the grouping columns, then the aggregates).
     * Its estimate is the number of groups: the product of the grouping
     * columns' NDVs, never more than the rows going in; 1 without GROUP BY.
     */
    private Aggregation aggregate(SelectStatement statement, Operator child, List<Integer> layout,
            JoinedSchema joinedSchema, List<List<ColumnSpec>> perTableSchema,
            List<JoinOrderer.TableEstimate> estimates, long rowsIn) {
        List<Integer> groupKeys = new ArrayList<>();
        double groups = 1;
        for (String column : statement.groupBy()) {
            groupKeys.add(rowIndexOf(column, layout, joinedSchema, perTableSchema));
            long ndv = estimates.get(joinedSchema.tableIndexOf(column)).ndvOf(JoinedSchema.bareName(column));
            groups *= ndv == Estimator.UNKNOWN_NDV ? rowsIn : ndv;
        }
        long estRows = statement.groupBy().isEmpty() ? 1 : Math.min(rowsIn, Estimator.rounded(groups));

        List<Integer> valueIndexes = new ArrayList<>();
        List<Supplier<Accumulator>> accumulators = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (Aggregate aggregate : statement.aggregates()) {
            ColumnType type = aggregate.isCountStar() ? ColumnType.LONG
                    : joinedSchema.columnSpec(aggregate.column()).type();
            valueIndexes.add(aggregate.isCountStar() ? -1
                    : rowIndexOf(aggregate.column(), layout, joinedSchema, perTableSchema));
            accumulators.add(() -> Accumulator.of(aggregate.function(), type));
            labels.add(aggregate.label());
        }
        String label = "groupBy=" + (statement.groupBy().isEmpty() ? "none" : String.join(";", statement.groupBy()))
                + " aggregates=" + String.join(";", labels);
        Operator root = new HashAggregateOperator(child, groupKeys, valueIndexes, accumulators, label, estRows);

        List<Integer> outputIndexes = new ArrayList<>();
        int aggregateIndex = 0;
        for (Object item : statement.selectItems()) {
            if (item instanceof Aggregate) {
                outputIndexes.add(statement.groupBy().size() + aggregateIndex++);
            } else {
                String column = (String) item;
                for (int g = 0; g < statement.groupBy().size(); g++) {
                    if (Binder.sameColumn(column, statement.groupBy().get(g), joinedSchema)) {
                        outputIndexes.add(g);
                        break;
                    }
                }
            }
        }
        return new Aggregation(root, outputIndexes);
    }

    private record Aggregation(Operator root, List<Integer> outputIndexes) {
    }

    /** The join order for the mode; single-table SELECTs have none to choose. */
    private JoinOrderer.JoinOrder joinOrder(SelectStatement statement, JoinedSchema joinedSchema,
            List<JoinOrderer.TableEstimate> estimates) {
        if (statement.tables().size() == 1) {
            return new JoinOrderer.JoinOrder(List.of(0), List.of());
        }
        if (mode == JoinMode.SYNTAX) {
            // Throws unless the join predicates fold left-to-right unambiguously.
            List<JoinPredicate> foldSteps = joinedSchema.resolveFoldOrder(statement.joins());
            List<Integer> fromOrder = new ArrayList<>();
            for (int i = 0; i < statement.tables().size(); i++) {
                fromOrder.add(i);
            }
            List<JoinOrderer.JoinEdge> stepEdges = foldSteps.stream().map(p -> edge(p, joinedSchema)).toList();
            return JoinOrderer.inOrder(estimates, fromOrder, stepEdges);
        }
        List<JoinOrderer.JoinEdge> edges = statement.joins().stream().map(p -> edge(p, joinedSchema)).toList();
        return JoinOrderer.greedy(estimates, edges);
    }

    private static JoinOrderer.JoinEdge edge(JoinPredicate predicate, JoinedSchema joinedSchema) {
        return new JoinOrderer.JoinEdge(
                joinedSchema.tableIndexOf(predicate.leftColumn()), JoinedSchema.bareName(predicate.leftColumn()),
                joinedSchema.tableIndexOf(predicate.rightColumn()), JoinedSchema.bareName(predicate.rightColumn()),
                predicate.leftColumn() + "=" + predicate.rightColumn());
    }

    /** column qualified with its table's alias, so it resolves even in a self-join. */
    private static String qualified(SelectStatement statement, int table, String bareColumn) {
        return statement.aliases().get(table) + "." + bareColumn;
    }

    /** columnName's index in a row whose tables appear in layout order. */
    private static int rowIndexOf(String columnName, List<Integer> layout, JoinedSchema joinedSchema,
            List<List<ColumnSpec>> perTableSchema) {
        int table = joinedSchema.tableIndexOf(columnName);
        return offsetOf(table, layout, perTableSchema) + joinedSchema.indexInTable(columnName);
    }

    private static int offsetOf(int table, List<Integer> layout, List<List<ColumnSpec>> perTableSchema) {
        int offset = 0;
        for (int t : layout) {
            if (t == table) {
                return offset;
            }
            offset += perTableSchema.get(t).size();
        }
        throw new IllegalStateException("table " + table + " is not part of the row");
    }

    private static boolean isIdentity(List<Integer> columnIndexes) {
        for (int i = 0; i < columnIndexes.size(); i++) {
            if (columnIndexes.get(i) != i) {
                return false;
            }
        }
        return true;
    }

    private ScanResult scanTable(StorageEngine.TableSnapshot snapshot, String alias, List<Predicate> filters) {
        String tableName = snapshot.tableName();

        Operator root;
        ScanStats stats;
        List<Integer> survivingPartitions;
        if (filters.isEmpty()) {
            int partitionsTotal = engine.partitionCount(snapshot);
            List<Integer> allPartitions = new ArrayList<>(partitionsTotal);
            for (int p = 0; p < partitionsTotal; p++) {
                allPartitions.add(p);
                LOGGER.debug("table={}  partition={}  decision={} reason={}", tableName, p, "READ", "noPredicate");
            }
            root = new ScanOperator(engine, snapshot, allPartitions, null);
            stats = new ScanStats(partitionsTotal, partitionsTotal, 0);
            survivingPartitions = allPartitions;
        } else {
            int partitionsTotal = engine.partitionCount(snapshot);

            // columnStats per filter gives the finished list of min/max per
            // partition, e.g. for distance: [[12,31],[88,95],[140,187],[210,299]].
            List<List<Object[]>> columnStatsPerFilter = new ArrayList<>(filters.size());
            for (Predicate predicate : filters) {
                columnStatsPerFilter.add(engine.columnStats(snapshot, predicate.columnName()));
            }

            survivingPartitions = new ArrayList<>();
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

        return new ScanResult(root, stats, estimate(snapshot, alias, survivingPartitions, filters));
    }

    /**
     * The table's estimated size after pruning and filters, and the NDV of
     * each of its columns (docs/optimizer-design.md, section 4). NDVs come
     * from the surviving partitions only; an equality filter sets its
     * column's NDV to 1, and every NDV is clamped to the estimated rows.
     */
    private JoinOrderer.TableEstimate estimate(StorageEngine.TableSnapshot snapshot, String alias,
            List<Integer> survivingPartitions, List<Predicate> filters) {
        List<Integer> rowCounts = engine.partitionRowCounts(snapshot);
        List<Integer> survivingRowCounts = survivingPartitions.stream().map(rowCounts::get).toList();
        long rows = Estimator.baseTable(survivingRowCounts);

        Map<String, Long> ndv = new LinkedHashMap<>();
        for (ColumnSpec column : engine.schema(snapshot)) {
            List<Long> partitionNdvs = engine.columnNdvs(snapshot, column.name());
            List<Long> surviving = new ArrayList<>();
            for (int p : survivingPartitions) {
                surviving.add(partitionNdvs.get(p));
            }
            long columnNdv = Estimator.tableNdv(surviving);
            if (columnNdv == Estimator.UNKNOWN_NDV) {
                LOGGER.debug("table={} column={} ndv=unknown", alias, column.name());
            }
            ndv.put(column.name(), columnNdv);
        }

        // Independence: the filters' selectivities multiply, rounded once.
        double selectivity = 1.0;
        for (Predicate predicate : filters) {
            selectivity *= Estimator.selectivity(ndv.get(predicate.columnName()), predicate.comparison());
        }
        for (Predicate predicate : filters) {
            if (predicate.comparison() == Comparison.EQUALS) {
                ndv.put(predicate.columnName(), 1L);
            }
        }
        long estRows = Estimator.applySelectivity(rows, selectivity);
        ndv.replaceAll((column, value) -> Estimator.clampNdv(value, estRows));

        LOGGER.debug("table={} rows={} filters={} estRows={}", alias, rows, filters.size(), estRows);
        return new JoinOrderer.TableEstimate(alias, estRows, ndv);
    }

    private record ScanResult(Operator root, ScanStats stats, JoinOrderer.TableEstimate estimate) {
    }
}

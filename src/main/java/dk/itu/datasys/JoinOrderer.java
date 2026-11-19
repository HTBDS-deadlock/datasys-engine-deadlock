package dk.itu.datasys;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Chooses a join order from estimates alone; no data is read. Tables are
 * identified by their FROM-list position. Two strategies:
 *
 * - greedy (-j optimized): start with the join predicate whose estimated
 *   output is smallest, then repeatedly add the connected table whose join
 *   output is smallest (Greedy Operator Ordering, Fegaras 1998, left-deep).
 *   Only tables connected by a predicate are candidates, so a cross product is
 *   never chosen. Ties go to the table earlier in the FROM list.
 * - inOrder (-j syntax): the given order (the FROM list), only estimated.
 *
 * See docs/optimizer-design.md, section 5.
 */
public final class JoinOrderer {
    private static final Logger LOGGER = LoggerFactory.getLogger("JoinOrder");

    /** One table as the planner sees it after pruning and filters. ndv is keyed by bare column name. */
    public record TableEstimate(String name, long rows, Map<String, Long> ndv) {
        long ndvOf(String bareColumn) {
            return ndv.getOrDefault(bareColumn, Estimator.UNKNOWN_NDV);
        }
    }

    /** A join predicate between two FROM-list positions. Columns are bare names; label is for the log. */
    public record JoinEdge(int leftTable, String leftColumn, int rightTable, String rightColumn, String label) {
        boolean touches(int table) {
            return leftTable == table || rightTable == table;
        }

        int other(int table) {
            return leftTable == table ? rightTable : leftTable;
        }

        String columnOf(int table) {
            return leftTable == table ? leftColumn : rightColumn;
        }
    }

    /** Joins table into the plan so far through edge; estRows is the estimated output of that join. */
    public record Step(int table, JoinEdge edge, long estRows) {
    }

    /** tables: the join order (all FROM positions). steps.get(i) adds tables.get(i + 1). */
    public record JoinOrder(List<Integer> tables, List<Step> steps) {
    }

    private JoinOrderer() {
    }

    /** -j optimized: greedy, smallest estimated intermediate result first. */
    public static JoinOrder greedy(List<TableEstimate> tables, List<JoinEdge> edges) {
        for (JoinEdge edge : edges) {
            if (edge.leftTable() == edge.rightTable()) {
                throw new IllegalArgumentException(
                        "Join predicate does not connect two tables in the FROM list: " + edge.label());
            }
        }
        if (tables.size() == 1) {
            return new JoinOrder(List.of(0), List.of());
        }

        // Step 1: every join predicate, scored as a pair. The pair is written
        // with its earlier FROM table first, which also breaks ties.
        JoinEdge bestEdge = null;
        long bestEst = Long.MAX_VALUE;
        int bestFirst = -1;
        int bestSecond = -1;
        for (JoinEdge edge : edges) {
            int first = Math.min(edge.leftTable(), edge.rightTable());
            int second = Math.max(edge.leftTable(), edge.rightTable());
            long est = Estimator.join(tables.get(first).rows(), tables.get(second).rows(),
                    tables.get(first).ndvOf(edge.columnOf(first)), tables.get(second).ndvOf(edge.columnOf(second)));
            log(List.of(first), tables, second, edge, est);
            boolean better = est < bestEst
                    || (est == bestEst && (first < bestFirst || (first == bestFirst && second < bestSecond)));
            if (better) {
                bestEdge = edge;
                bestEst = est;
                bestFirst = first;
                bestSecond = second;
            }
        }
        if (bestEdge == null) {
            throw new IllegalArgumentException("Table " + tables.get(1).name()
                    + " has no join predicate connecting it to the already-joined tables");
        }

        List<Integer> order = new ArrayList<>(List.of(bestFirst, bestSecond));
        List<Step> steps = new ArrayList<>(List.of(new Step(bestSecond, bestEdge, bestEst)));
        Set<JoinEdge> used = new HashSet<>(List.of(bestEdge));
        long current = bestEst;

        // Steps 2..n-1: among the tables connected to the plan, the smallest join output.
        while (order.size() < tables.size()) {
            Step best = null;
            for (int t = 0; t < tables.size(); t++) {
                if (order.contains(t)) {
                    continue;
                }
                JoinEdge connecting = connectingEdge(t, order, edges, tables);
                if (connecting == null) {
                    continue;
                }
                int planSide = connecting.other(t);
                long est = Estimator.join(current, tables.get(t).rows(),
                        tables.get(planSide).ndvOf(connecting.columnOf(planSide)),
                        tables.get(t).ndvOf(connecting.columnOf(t)));
                log(order, tables, t, connecting, est);
                if (best == null || est < best.estRows()) {
                    best = new Step(t, connecting, est);
                }
            }
            if (best == null) {
                throw new IllegalArgumentException("Table " + tables.get(firstMissing(order, tables.size())).name()
                        + " has no join predicate connecting it to the already-joined tables");
            }
            order.add(best.table());
            steps.add(best);
            used.add(best.edge());
            current = best.estRows();
        }

        for (JoinEdge edge : edges) {
            if (!used.contains(edge)) {
                throw new IllegalArgumentException("Table " + tables.get(Math.max(edge.leftTable(), edge.rightTable())).name()
                        + " has more than one connecting predicate to the already-joined tables; only one is supported");
            }
        }
        return new JoinOrder(List.copyOf(order), List.copyOf(steps));
    }

    /**
     * -j syntax: the order is given (the FROM list), each step with its
     * connecting predicate. Only the estimates are computed, so the log can
     * show estimate next to actual in this mode too.
     */
    public static JoinOrder inOrder(List<TableEstimate> tables, List<Integer> order, List<JoinEdge> stepEdges) {
        List<Step> steps = new ArrayList<>();
        long current = tables.get(order.get(0)).rows();
        for (int i = 1; i < order.size(); i++) {
            int t = order.get(i);
            JoinEdge edge = stepEdges.get(i - 1);
            int planSide = edge.other(t);
            current = Estimator.join(current, tables.get(t).rows(),
                    tables.get(planSide).ndvOf(edge.columnOf(planSide)), tables.get(t).ndvOf(edge.columnOf(t)));
            steps.add(new Step(t, edge, current));
        }
        return new JoinOrder(List.copyOf(order), List.copyOf(steps));
    }

    /** The one predicate connecting table to the plan, null if none; more than one is unsupported. */
    private static JoinEdge connectingEdge(int table, List<Integer> plan, List<JoinEdge> edges,
            List<TableEstimate> tables) {
        JoinEdge connecting = null;
        for (JoinEdge edge : edges) {
            if (edge.touches(table) && plan.contains(edge.other(table))) {
                if (connecting != null) {
                    throw new IllegalArgumentException("Table " + tables.get(table).name()
                            + " has more than one connecting predicate to the already-joined tables; only one is supported");
                }
                connecting = edge;
            }
        }
        return connecting;
    }

    private static int firstMissing(List<Integer> order, int tableCount) {
        for (int t = 0; t < tableCount; t++) {
            if (!order.contains(t)) {
                return t;
            }
        }
        throw new IllegalStateException("every table is already joined");
    }

    private static void log(List<Integer> plan, List<TableEstimate> tables, int table, JoinEdge edge, long est) {
        LOGGER.debug("plan={} table={} join={} estRows={}", names(plan, tables), tables.get(table).name(),
                edge.label(), est);
    }

    static String names(List<Integer> positions, List<TableEstimate> tables) {
        List<String> names = new ArrayList<>();
        for (int position : positions) {
            names.add(tables.get(position).name());
        }
        return String.join(";", names);
    }
}

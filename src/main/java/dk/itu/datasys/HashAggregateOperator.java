package dk.itu.datasys;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Hash aggregation (lecture 11, slides 43-45): one accumulator per aggregate
 * per group, in a hash map keyed by the grouping columns. Blocking like the
 * join's build side: open() drains the child; next() then emits one row per
 * group, the group key's values followed by each aggregate's finish().
 *
 * Without grouping columns the whole input is one group, and that group is
 * emitted even when the input is empty (count 0, the others null), as in SQL.
 */
public final class HashAggregateOperator implements Operator {
    private static final Logger LOGGER = LoggerFactory.getLogger(HashAggregateOperator.class);

    private final Operator child;
    private final List<Integer> groupKeys;
    private final List<Integer> valueIndexes;
    private final List<Supplier<Accumulator>> accumulators;
    private final String label;
    private final long estRows;

    private Iterator<Map.Entry<List<Object>, Accumulator[]>> groupIterator = Collections.emptyIterator();
    private int rowsIn;
    private int rowsOut;

    /**
     * groupKeys: positions of the grouping columns in the child's rows.
     * valueIndexes: per aggregate, the position of its argument, or -1 for
     * count(*). accumulators: per aggregate, a factory for its accumulator.
     * label and estRows (the planner's estimate of the group count) are logged.
     */
    public HashAggregateOperator(Operator child, List<Integer> groupKeys, List<Integer> valueIndexes,
            List<Supplier<Accumulator>> accumulators, String label, long estRows) {
        this.child = child;
        this.groupKeys = List.copyOf(groupKeys);
        this.valueIndexes = List.copyOf(valueIndexes);
        this.accumulators = List.copyOf(accumulators);
        this.label = label;
        this.estRows = estRows;
    }

    @Override
    public void open() {
        Map<List<Object>, Accumulator[]> groups = new LinkedHashMap<>();
        rowsIn = 0;
        rowsOut = 0;

        child.open();
        Object[] row;
        while ((row = child.next()) != null) {
            rowsIn++;
            List<Object> key = new ArrayList<>(groupKeys.size());
            for (int groupKey : groupKeys) {
                key.add(row[groupKey]);
            }
            Accumulator[] state = groups.computeIfAbsent(key, ignored -> newAccumulators());
            for (int i = 0; i < state.length; i++) {
                int valueIndex = valueIndexes.get(i);
                state[i].update(valueIndex < 0 ? null : row[valueIndex]);
            }
        }
        child.close();

        if (groupKeys.isEmpty() && groups.isEmpty()) {
            groups.put(List.of(), newAccumulators());
        }
        groupIterator = groups.entrySet().iterator();
    }

    @Override
    public Object[] next() {
        if (!groupIterator.hasNext()) {
            return null;
        }
        Map.Entry<List<Object>, Accumulator[]> group = groupIterator.next();
        Object[] out = new Object[groupKeys.size() + accumulators.size()];
        for (int i = 0; i < groupKeys.size(); i++) {
            out[i] = group.getKey().get(i);
        }
        for (int i = 0; i < accumulators.size(); i++) {
            out[groupKeys.size() + i] = group.getValue()[i].finish();
        }
        rowsOut++;
        return out;
    }

    @Override
    public void close() {
        // the child was already closed at the end of open(); one line per
        // executed aggregation: the estimated group count next to the actual
        LOGGER.debug("{} estRows={} rowsIn={} rowsOut={}", label, estRows, rowsIn, rowsOut);
    }

    private Accumulator[] newAccumulators() {
        Accumulator[] state = new Accumulator[accumulators.size()];
        for (int i = 0; i < state.length; i++) {
            state[i] = accumulators.get(i).get();
        }
        return state;
    }
}

package dk.itu.datasys;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Naive inner equi-join: materializes the build side into a hash map keyed by
 * its join column, then streams the probe side, emitting buildColumns +
 * probeColumns for every match. Probe rows with no match are dropped -- this
 * is an inner join only.
 */
public final class HashJoinOperator implements Operator {
    private static final Logger LOGGER = LoggerFactory.getLogger(HashJoinOperator.class);

    private final Operator build;
    private final List<ColumnSpec> buildSchema;
    private final String buildColumn;
    private final Operator probe;
    private final List<ColumnSpec> probeSchema;
    private final String probeColumn;

    private int buildColumnIndex;
    private int probeColumnIndex;
    private Map<Object, List<Object[]>> buildIndex;
    private Iterator<Object[]> currentMatches = Collections.emptyIterator();
    private Object[] currentProbeRow;
    private int buildRowCount;
    private int probeRowCount;
    private int rowsOut;

    public HashJoinOperator(Operator build, List<ColumnSpec> buildSchema, String buildColumn,
            Operator probe, List<ColumnSpec> probeSchema, String probeColumn) {
        this.build = build;
        this.buildSchema = buildSchema;
        this.buildColumn = buildColumn;
        this.probe = probe;
        this.probeSchema = probeSchema;
        this.probeColumn = probeColumn;
    }

    @Override
    public void open() {
        buildColumnIndex = resolveColumnIndex(buildSchema, buildColumn);
        probeColumnIndex = resolveColumnIndex(probeSchema, probeColumn);

        build.open();
        buildIndex = new HashMap<>();
        buildRowCount = 0;
        Object[] row;
        while ((row = build.next()) != null) {
            buildRowCount++;
            buildIndex.computeIfAbsent(row[buildColumnIndex], key -> new ArrayList<>()).add(row);
        }
        build.close();

        probe.open();
        currentMatches = Collections.emptyIterator();
        currentProbeRow = null;
        probeRowCount = 0;
        rowsOut = 0;
    }

    @Override
    public Object[] next() {
        while (true) {
            if (currentMatches.hasNext()) {
                Object[] buildRow = currentMatches.next();
                rowsOut++;
                return combine(buildRow, currentProbeRow);
            }
            currentProbeRow = probe.next();
            if (currentProbeRow == null) {
                return null;
            }
            probeRowCount++;
            List<Object[]> matches = buildIndex.get(currentProbeRow[probeColumnIndex]);
            currentMatches = matches == null ? Collections.emptyIterator() : matches.iterator();
        }
    }

    @Override
    public void close() {
        probe.close();
        LOGGER.debug("buildColumn={} probeColumn={} buildRows={} probeRows={} rowsOut={}",
                buildColumn, probeColumn, buildRowCount, probeRowCount, rowsOut);
    }

    private static Object[] combine(Object[] buildRow, Object[] probeRow) {
        Object[] combined = new Object[buildRow.length + probeRow.length];
        System.arraycopy(buildRow, 0, combined, 0, buildRow.length);
        System.arraycopy(probeRow, 0, combined, buildRow.length, probeRow.length);
        return combined;
    }

    private static int resolveColumnIndex(List<ColumnSpec> schema, String columnName) {
        for (int i = 0; i < schema.size(); i++) {
            if (schema.get(i).name().equals(columnName)) {
                return i;
            }
        }
        throw new IllegalArgumentException("Unknown column: " + columnName);
    }
}

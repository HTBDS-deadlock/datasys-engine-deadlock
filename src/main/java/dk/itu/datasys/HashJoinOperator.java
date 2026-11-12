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

    /** Join keys looked up by name in each side's schema (names must be unique per side). */
    public HashJoinOperator(Operator build, List<ColumnSpec> buildSchema, String buildColumn,
            Operator probe, List<ColumnSpec> probeSchema, String probeColumn) {
        this.build = build;
        this.buildSchema = buildSchema;
        this.buildColumn = buildColumn;
        this.probe = probe;
        this.probeSchema = probeSchema;
        this.probeColumn = probeColumn;
    }

    /**
     * Join keys given as positions in each side's rows. Needed when a side
     * holds the same column name twice, as in a self-join. The column labels
     * only appear in the log.
     */
    public HashJoinOperator(Operator build, int buildKey, String buildColumn,
            Operator probe, int probeKey, String probeColumn) {
        this(build, null, buildColumn, probe, null, probeColumn);
        this.buildColumnIndex = buildKey;
        this.probeColumnIndex = probeKey;
    }

    public HashJoinOperator(Operator build, Operator probe, int buildKey, int probeKey) {
        this(build, buildKey, "#" + buildKey, probe, probeKey, "#" + probeKey);
    }

    @Override
    public void open() {
        if (buildSchema != null) {
            buildColumnIndex = resolveColumnIndex(buildSchema, buildColumn);
            probeColumnIndex = resolveColumnIndex(probeSchema, probeColumn);
        }

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
        LOGGER.debug("buildColumn={} probeColumn={} rowsBuild={} rowsProbe={} rowsOut={}",
                buildColumn, probeColumn, buildRowCount, probeRowCount, rowsOut);
    }

    /** The build child; package-private so tests can inspect a plan's shape. */
    Operator build() {
        return build;
    }

    /** The probe child; package-private so tests can inspect a plan's shape. */
    Operator probe() {
        return probe;
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

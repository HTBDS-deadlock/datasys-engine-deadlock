package dk.itu.datasys;

import java.util.List;

/**
 * Cardinality estimation as pure functions over catalog numbers: no data file
 * is opened and nothing is sampled, so every formula is unit-testable on
 * statistics written by hand. The formulas and their sources are in
 * docs/optimizer-design.md, section 4.
 */
public final class Estimator {

    /** An NDV the catalog does not have (a partition copied before v0.11). Never 0. */
    public static final long UNKNOWN_NDV = -1;

    /** Selinger et al. 1979: the "magic constant" for an inequality. */
    static final double INEQUALITY_SELECTIVITY = 1.0 / 3;

    /** Selinger et al. 1979: the default for col = value without statistics. */
    static final double UNKNOWN_EQUALITY_SELECTIVITY = 1.0 / 10;

    private Estimator() {
    }

    /**
     * Merge rule: the table's NDV is the maximum of the partition NDVs (a lower
     * bound on the true NDV). Partitions without an NDV (null) are skipped; the
     * maximum over the rest is still a lower bound. Returns UNKNOWN_NDV when no
     * partition has one.
     */
    public static long tableNdv(List<Long> partitionNdvs) {
        long max = UNKNOWN_NDV;
        for (Long ndv : partitionNdvs) {
            if (ndv != null && ndv > max) {
                max = ndv;
            }
        }
        return max;
    }

    /** Base table: the sum of the row counts of the partitions that survive pruning. Exact. */
    public static long baseTable(List<Integer> survivingPartitionRowCounts) {
        long rows = 0;
        for (int rowCount : survivingPartitionRowCounts) {
            rows += rowCount;
        }
        return rows;
    }

    /** The fraction of rows a single filter keeps: 1 / ndv for equality, 1/3 for a range. */
    public static double selectivity(long ndv, Comparison comparison) {
        if (comparison != Comparison.EQUALS) {
            return INEQUALITY_SELECTIVITY;
        }
        if (ndv == UNKNOWN_NDV) {
            return UNKNOWN_EQUALITY_SELECTIVITY;
        }
        return 1.0 / Math.max(ndv, 1);
    }

    /** One filter over rows: rows / ndv for equality, rows / 3 for a range. */
    public static long filter(long rows, long ndv, Comparison comparison) {
        return applySelectivity(rows, selectivity(ndv, comparison));
    }

    /**
     * rows times a selectivity. Several filters on one table multiply their
     * selectivities first (independence assumption) and round once.
     */
    public static long applySelectivity(long rows, double selectivity) {
        return rounded(rows * selectivity);
    }

    /**
     * Equi-join: |R| * |S| / max(ndv(R.a), ndv(S.b)) (lecture 11, slide 13).
     * Unknown NDV: with one side known, that side divides alone; with both
     * unknown, the smaller input is treated as a key, so the result is the
     * larger input (a foreign-key join).
     */
    public static long join(long leftRows, long rightRows, long leftNdv, long rightNdv) {
        long divisor;
        if (leftNdv == UNKNOWN_NDV && rightNdv == UNKNOWN_NDV) {
            divisor = Math.min(leftRows, rightRows);
        } else {
            divisor = Math.max(leftNdv, rightNdv);
        }
        return rounded((double) leftRows * rightRows / Math.max(divisor, 1));
    }

    /**
     * A column's NDV after its table has been filtered to estRows rows: never
     * more than the rows that are left. Unknown stays unknown.
     */
    public static long clampNdv(long ndv, long estRows) {
        if (ndv == UNKNOWN_NDV) {
            return UNKNOWN_NDV;
        }
        return Math.min(ndv, estRows);
    }

    /**
     * Rounds half up, but never to 0 unless the input was empty: a zero
     * estimate would make a plan look free (lecture 11, slide 17).
     */
    static long rounded(double value) {
        if (value <= 0) {
            return 0;
        }
        return Math.max(1, Math.round(value));
    }
}

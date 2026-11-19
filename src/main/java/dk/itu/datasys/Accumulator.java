package dk.itu.datasys;

/**
 * The accumulator contract of hash aggregation (lecture 11, slide 43): init
 * (the constructor), update once per input row, finish once per group. One
 * accumulator per aggregate per group.
 */
public interface Accumulator {

    void update(Object value);

    /** The aggregate's value; null when it saw no rows (min, max, sum over empty input). */
    Object finish();

    /** A fresh accumulator for function over a column of type (type is ignored for count). */
    static Accumulator of(AggregateFunction function, ColumnType type) {
        return switch (function) {
            case COUNT -> new Count();
            case SUM -> type == ColumnType.DOUBLE ? new DoubleSum() : new LongSum();
            case MIN -> new Extreme(-1);
            case MAX -> new Extreme(1);
        };
    }

    /** count(*) and count(col): the engine has no NULLs, so both count rows. Init 0, update +1. */
    final class Count implements Accumulator {
        private long count;

        @Override
        public void update(Object value) {
            count++;
        }

        @Override
        public Object finish() {
            return count;
        }
    }

    /** sum over LONG: init none, update + a. */
    final class LongSum implements Accumulator {
        private Long sum;

        @Override
        public void update(Object value) {
            sum = (sum == null ? 0L : sum) + (Long) value;
        }

        @Override
        public Object finish() {
            return sum;
        }
    }

    /** sum over DOUBLE: init none, update + a. */
    final class DoubleSum implements Accumulator {
        private Double sum;

        @Override
        public void update(Object value) {
            sum = (sum == null ? 0.0 : sum) + (Double) value;
        }

        @Override
        public Object finish() {
            return sum;
        }
    }

    /**
     * min (direction -1) and max (direction 1): compare, keep. LONG, DOUBLE
     * and STRING are all Comparable, so one class serves every type (slide 45).
     */
    final class Extreme implements Accumulator {
        private final int direction;
        private Comparable<Object> kept; // init: no value yet

        Extreme(int direction) {
            this.direction = direction;
        }

        @Override
        @SuppressWarnings("unchecked")
        public void update(Object value) {
            if (kept == null || Integer.signum(((Comparable<Object>) value).compareTo(kept)) == direction) {
                kept = (Comparable<Object>) value;
            }
        }

        @Override
        public Object finish() {
            return kept;
        }
    }
}

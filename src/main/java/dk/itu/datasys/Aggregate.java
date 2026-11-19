package dk.itu.datasys;

/**
 * One aggregate in a SELECT list, e.g. {@code max(p_price)} or
 * {@code count(*)}. column is null for {@code count(*)}. position is the
 * item's 0-based place in the SELECT list, which mixes plain columns and
 * aggregates: in {@code SELECT c_city, max(p_price), count(*)} the
 * aggregates sit at positions 1 and 2.
 */
public record Aggregate(AggregateFunction function, String column, int position) {

    public boolean isCountStar() {
        return column == null;
    }

    /** As written in SQL: {@code max(p_price)}, {@code count(*)}. */
    public String label() {
        return function.sqlName() + "(" + (column == null ? "*" : column) + ")";
    }
}

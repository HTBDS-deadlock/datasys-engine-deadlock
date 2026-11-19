package dk.itu.datasys;

/** The aggregate functions of the GROUP BY extension, in DuckDB syntax. */
public enum AggregateFunction {
    MIN, MAX, COUNT, SUM;

    /** The function named in SQL (case-insensitive), or an exception naming the supported ones. */
    public static AggregateFunction parse(String name) {
        return switch (name.toLowerCase()) {
            case "min" -> MIN;
            case "max" -> MAX;
            case "count" -> COUNT;
            case "sum" -> SUM;
            default -> throw new IllegalArgumentException(
                    "Unknown aggregate function: " + name + " (supported: min, max, count, sum)");
        };
    }

    /** The name as written in SQL and in the log. */
    public String sqlName() {
        return name().toLowerCase();
    }
}

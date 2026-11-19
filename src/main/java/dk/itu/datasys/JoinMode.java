package dk.itu.datasys;

/**
 * How the planner orders joins: OPTIMIZED (the default, -j optimized) uses
 * the statistics; SYNTAX (-j syntax) reproduces week 10's plan exactly: the
 * FROM list is the join order and the join so far always builds.
 */
public enum JoinMode {
    OPTIMIZED, SYNTAX;

    /** Parses the -j argument: "optimized" or "syntax". */
    public static JoinMode parse(String value) {
        return switch (value) {
            case "optimized" -> OPTIMIZED;
            case "syntax" -> SYNTAX;
            default -> throw new IllegalArgumentException(
                    "Unknown join mode: " + value + " (expected optimized or syntax)");
        };
    }

    /** The name used on the command line and in the log. */
    public String label() {
        return name().toLowerCase();
    }
}

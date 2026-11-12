package dk.itu.datasys;

import java.util.ArrayList;
import java.util.List;

/**
 * The column layout of a left-deep join's tables, in FROM-list order: table
 * 0's columns occupy the row prefix, then table 1's, and so on. Used by both
 * Binder and Planner to resolve a bare column name to its owning table, its
 * index in the concatenated row, and the left-deep fold order the join
 * predicates must imply.
 */
public final class JoinedSchema {
    private final List<String> tableNames;
    private final List<List<ColumnSpec>> schemas;

    public JoinedSchema(List<String> tableNames, List<List<ColumnSpec>> schemas) {
        this.tableNames = tableNames;
        this.schemas = schemas;
    }

    /** The FROM-list position (0-based) of the table that owns columnName. */
    public int tableIndexOf(String columnName) {
        int found = -1;
        for (int t = 0; t < schemas.size(); t++) {
            if (hasColumn(schemas.get(t), columnName)) {
                if (found != -1) {
                    throw new IllegalArgumentException("Column " + columnName + " is ambiguous between tables "
                            + tableNames.get(found) + " and " + tableNames.get(t));
                }
                found = t;
            }
        }
        if (found == -1) {
            throw new IllegalArgumentException("Unknown column: " + columnName);
        }
        return found;
    }

    /** columnName's index in the concatenated row (table 0's columns, then table 1's, ...). */
    public int rowIndexOf(String columnName) {
        int table = tableIndexOf(columnName);
        int offset = 0;
        for (int t = 0; t < table; t++) {
            offset += schemas.get(t).size();
        }
        return offset + columnIndexWithinTable(schemas.get(table), columnName);
    }

    public ColumnSpec columnSpec(String columnName) {
        List<ColumnSpec> schema = schemas.get(tableIndexOf(columnName));
        for (ColumnSpec column : schema) {
            if (column.name().equals(columnName)) {
                return column;
            }
        }
        throw new IllegalArgumentException("Unknown column: " + columnName);
    }

    /**
     * The FROM list is joined left-deep: table 0 is the base, and each table
     * after it must connect to the already-joined set via exactly one join
     * predicate. Returns that connecting predicate for each table after the
     * first, in FROM order. Throws if a table has zero or more than one
     * connecting predicate, or if a predicate never connects a new table to
     * the already-joined set.
     */
    public List<JoinPredicate> resolveFoldOrder(List<JoinPredicate> joins) {
        List<JoinPredicate> remaining = new ArrayList<>(joins);
        List<JoinPredicate> foldSteps = new ArrayList<>();

        for (int i = 1; i < tableNames.size(); i++) {
            JoinPredicate connecting = null;
            for (JoinPredicate predicate : joins) {
                int left = tableIndexOf(predicate.leftColumn());
                int right = tableIndexOf(predicate.rightColumn());
                boolean connectsNewTable = (left == i && right < i) || (right == i && left < i);
                if (connectsNewTable) {
                    if (connecting != null) {
                        throw new IllegalArgumentException("Table " + tableNames.get(i)
                                + " has more than one connecting predicate to the already-joined tables; only one is supported");
                    }
                    connecting = predicate;
                }
            }
            if (connecting == null) {
                throw new IllegalArgumentException(
                        "Table " + tableNames.get(i) + " has no join predicate connecting it to the already-joined tables");
            }
            foldSteps.add(connecting);
            remaining.remove(connecting);
        }

        if (!remaining.isEmpty()) {
            JoinPredicate unused = remaining.get(0);
            throw new IllegalArgumentException(
                    "Join predicate does not connect two tables in the FROM list: "
                            + unused.leftColumn() + " = " + unused.rightColumn());
        }

        return foldSteps;
    }

    private static boolean hasColumn(List<ColumnSpec> schema, String columnName) {
        for (ColumnSpec column : schema) {
            if (column.name().equals(columnName)) {
                return true;
            }
        }
        return false;
    }

    private static int columnIndexWithinTable(List<ColumnSpec> schema, String columnName) {
        for (int i = 0; i < schema.size(); i++) {
            if (schema.get(i).name().equals(columnName)) {
                return i;
            }
        }
        throw new IllegalArgumentException("Unknown column: " + columnName);
    }
}

package dk.itu.datasys;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

// Binder is a verifier that checks if our SQL statements are actually possible accordingly to our
public final class Binder {
    private final StorageEngine engine;

    public Binder(StorageEngine engine) {
        this.engine = engine;
    }

    /**
     * Validates s against the catalog; throws IllegalArgumentException on the first
     * violation.
     */
    public void bind(Statement s) {
        bind(s, snapshotsFor(s));
    }

    /** Convenience overload for callers that already pinned a single table's snapshot. */
    public void bind(Statement s, Optional<StorageEngine.TableSnapshot> snapshot) {
        Map<String, StorageEngine.TableSnapshot> snapshots = new LinkedHashMap<>();
        snapshot.ifPresent(snap -> snapshots.put(snap.tableName(), snap));
        bind(s, snapshots);
    }

    /** snapshots maps each table the statement reads to the snapshot pinned for it. */
    public void bind(Statement s, Map<String, StorageEngine.TableSnapshot> snapshots) {
        switch (s) {
            case CreateTableStatement stmt -> bindCreateTable(stmt);
            case CopyStatement stmt -> bindCopy(stmt, snapshots);
            case SelectStatement stmt -> bindSelect(stmt, snapshots);
            case DeleteStatement stmt -> bindDelete(stmt, snapshots);
        }
    }

    private Map<String, StorageEngine.TableSnapshot> snapshotsFor(Statement statement) {
        List<String> tableNames = switch (statement) {
            case CreateTableStatement stmt -> List.of();
            case CopyStatement stmt -> List.of(stmt.tableName());
            case SelectStatement stmt -> stmt.tableNames();
            case DeleteStatement stmt -> List.of(stmt.tableName());
        };
        Map<String, StorageEngine.TableSnapshot> snapshots = new LinkedHashMap<>();
        for (String tableName : tableNames) {
            engine.snapshotIfPresent(tableName).ifPresent(snapshot -> snapshots.put(tableName, snapshot));
        }
        return snapshots;
    }

    private void bindCreateTable(CreateTableStatement stmt) {
        if (stmt.columns().isEmpty()) {
            throw new IllegalArgumentException("CREATE TABLE requires at least one column");
        }
        Set<String> names = new HashSet<>();
        for (ColumnSpec column : stmt.columns()) {
            if (!names.add(column.name())) {
                throw new IllegalArgumentException("Duplicate column name: " + column.name());
            }
        }
    }

    private void bindCopy(CopyStatement stmt, Map<String, StorageEngine.TableSnapshot> snapshots) {
        schema(stmt.tableName(), snapshots);
    }

    private void bindSelect(SelectStatement stmt, Map<String, StorageEngine.TableSnapshot> snapshots) {
        if (stmt.tables().size() == 1 && !stmt.joins().isEmpty()) {
            throw new UnsupportedOperationException("A single-table SELECT cannot have a join predicate");
        }

        // One JoinedSchema for any number of tables: it resolves bare and
        // alias-qualified column names the same way for 1 or N tables.
        List<List<ColumnSpec>> perTableSchema = stmt.tableNames().stream()
                .map(tableName -> schema(tableName, snapshots))
                .toList();
        JoinedSchema joinedSchema = new JoinedSchema(stmt.aliases(), perTableSchema);

        for (JoinPredicate join : stmt.joins()) {
            bindJoin(join, joinedSchema);
        }

        if (stmt.tables().size() > 1) {
            // Throws unless the join predicates fold left-to-right unambiguously.
            joinedSchema.resolveFoldOrder(stmt.joins());
        }

        for (Predicate predicate : stmt.filters()) {
            ColumnSpec column = joinedSchema.columnSpec(predicate.columnName());
            StorageEngine.validateConstantType(column.type(), predicate.constant());
        }

        for (String column : stmt.columns()) {
            joinedSchema.tableIndexOf(column); // throws if unknown or ambiguous
        }
    }

    /**
     * Both sides must exist, sit in two different tables, and have the same
     * type. The hash join compares keys with equals(), so LONG 3 and DOUBLE 3.0
     * would never match; rejecting is clearer than silently returning no rows.
     */
    private void bindJoin(JoinPredicate join, JoinedSchema joinedSchema) {
        int leftTable = joinedSchema.tableIndexOf(join.leftColumn());
        int rightTable = joinedSchema.tableIndexOf(join.rightColumn());
        if (leftTable == rightTable) {
            throw new IllegalArgumentException("Join predicate " + join.leftColumn() + " = " + join.rightColumn()
                    + " compares two columns of the same table; a join must connect two different tables");
        }
        ColumnType leftType = joinedSchema.columnSpec(join.leftColumn()).type();
        ColumnType rightType = joinedSchema.columnSpec(join.rightColumn()).type();
        if (leftType != rightType) {
            throw new IllegalArgumentException("Join predicate " + join.leftColumn() + " = " + join.rightColumn()
                    + " compares " + leftType + " with " + rightType + "; both sides must have the same type");
        }
    }

    private void bindDelete(DeleteStatement stmt, Map<String, StorageEngine.TableSnapshot> snapshots) {
        List<ColumnSpec> schema = schema(stmt.tableName(), snapshots);

        stmt.where().ifPresent(predicate -> {
            ColumnSpec column = findColumn(schema, predicate.columnName());
            StorageEngine.validateConstantType(column.type(), predicate.constant());
        });
    }

    private List<ColumnSpec> schema(String tableName, Map<String, StorageEngine.TableSnapshot> snapshots) {
        StorageEngine.TableSnapshot snapshot = snapshots.get(tableName);
        return snapshot != null ? engine.schema(snapshot) : engine.schema(tableName);
    }

    private ColumnSpec findColumn(List<ColumnSpec> schema, String columnName) {
        return schema.stream()
                .filter(c -> c.name().equals(columnName))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown column: " + columnName));
    }
}

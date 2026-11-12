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
        if (stmt.tableNames().size() == 1 && stmt.joins().isEmpty()) {
            List<ColumnSpec> schema = schema(stmt.tableNames().get(0), snapshots);
            for (Predicate predicate : stmt.filters()) {
                ColumnSpec column = findColumn(schema, predicate.columnName());
                StorageEngine.validateConstantType(column.type(), predicate.constant());
            }
            return;
        }
        if (stmt.tableNames().size() == 1) {
            throw new UnsupportedOperationException("A single-table SELECT cannot have a join predicate");
        }

        List<List<ColumnSpec>> perTableSchema = stmt.tableNames().stream()
                .map(tableName -> schema(tableName, snapshots))
                .toList();
        JoinedSchema joinedSchema = new JoinedSchema(stmt.tableNames(), perTableSchema);

        // Throws unless the join predicates fold left-to-right unambiguously.
        joinedSchema.resolveFoldOrder(stmt.joins());

        for (Predicate predicate : stmt.filters()) {
            ColumnSpec column = joinedSchema.columnSpec(predicate.columnName());
            StorageEngine.validateConstantType(column.type(), predicate.constant());
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

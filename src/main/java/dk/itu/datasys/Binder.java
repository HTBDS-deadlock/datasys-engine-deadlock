package dk.itu.datasys;

import java.util.HashSet;
import java.util.List;
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
        bind(s, snapshotFor(s));
    }

    public void bind(Statement s, Optional<StorageEngine.TableSnapshot> snapshot) {
        switch (s) {
            // As match in functional
            case CreateTableStatement stmt -> bindCreateTable(stmt);
            case CopyStatement stmt -> bindCopy(stmt, snapshot);
            case SelectStatement stmt -> bindSelect(stmt, snapshot);
            case DeleteStatement stmt -> bindDelete(stmt, snapshot);
        }
    }

    private Optional<StorageEngine.TableSnapshot> snapshotFor(Statement statement) {
        return switch (statement) {
            case CreateTableStatement stmt -> Optional.empty();
            case CopyStatement stmt -> engine.snapshotIfPresent(stmt.tableName());
            case SelectStatement stmt -> engine.snapshotIfPresent(stmt.tableName());
            case DeleteStatement stmt -> engine.snapshotIfPresent(stmt.tableName());
        };
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

    private void bindCopy(CopyStatement stmt, Optional<StorageEngine.TableSnapshot> snapshot) {
        schema(stmt.tableName(), snapshot);
    }

    private void bindSelect(SelectStatement stmt, Optional<StorageEngine.TableSnapshot> snapshot) {
        List<ColumnSpec> schema = schema(stmt.tableName(), snapshot);

        stmt.columns().ifPresent(columns -> {
            for (String columnName : columns) {
                findColumn(schema, columnName);
            }
        });

        stmt.filters().ifPresent(predicate -> {
            ColumnSpec column = findColumn(schema, predicate.columnName());
            StorageEngine.validateConstantType(column.type(), predicate.constant());
        });
    }

    private void bindDelete(DeleteStatement stmt, Optional<StorageEngine.TableSnapshot> snapshot) {
        List<ColumnSpec> schema = schema(stmt.tableName(), snapshot);

        stmt.where().ifPresent(predicate -> {
            ColumnSpec column = findColumn(schema, predicate.columnName());
            StorageEngine.validateConstantType(column.type(), predicate.constant());
        });
    }

    private List<ColumnSpec> schema(String tableName,
            Optional<StorageEngine.TableSnapshot> snapshot) {
        return snapshot.map(engine::schema).orElseGet(() -> engine.schema(tableName));
    }

    private ColumnSpec findColumn(List<ColumnSpec> schema, String columnName) {
        return schema.stream()
                .filter(c -> c.name().equals(columnName))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown column: " + columnName));
    }
}

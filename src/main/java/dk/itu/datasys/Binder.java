package dk.itu.datasys;

import java.util.HashSet;
import java.util.List;
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
        switch (s) {
            // As match in functional
            case CreateTableStatement stmt -> bindCreateTable(stmt);
            case CopyStatement stmt -> bindCopy(stmt);
            case SelectStatement stmt -> bindSelect(stmt);
        }
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

    private void bindCopy(CopyStatement stmt) {
        engine.schema(stmt.tableName());
    }

    private void bindSelect(SelectStatement stmt) {
        List<ColumnSpec> schema = engine.schema(stmt.tableName());
        Predicate filters = stmt.filters();
        if (filters == null) {
            return;
        }

        ColumnSpec column = schema.stream()
                .filter(c -> c.name().equals(filters.columnName()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown column: " + filters.columnName()));
        StorageEngine.validateConstantType(column.type(), filters.constant());
    }
}

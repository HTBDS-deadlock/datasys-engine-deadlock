package dk.itu.datasys;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class StorageEngine {

    private ObjectNode catalog;
    /**
     * All persistent state (catalog + data files) lives under this directory.
     */
    private Path dataDirectory;

    public StorageEngine(Path dataDirectory) {
        this.dataDirectory = dataDirectory;
    }

    public void createTable(String tableName, List<ColumnSpec> columns) {

        if (catalog.has(tableName)) {
            throw new IllegalArgumentException("Table already exists");
        }

        if (columns.isEmpty()) {
            throw new IllegalArgumentException("Column list cannot be empty");
        }

        Set<String> names = new HashSet<>();

        for (ColumnSpec column : columns) {
            if (!names.add(column.name())) {
                throw new IllegalArgumentException("Duplicate column name");
            }
        }

        catalog.put(tableName, columns);
        saveCatalog();
    }

    public void copyFile(String tableName, String csvFilePath) {
        /* ... */ }

    // public List<Object[]> select(String tableName, String columnName, Comparison comparison, Object constant) {
    //     /* ... */
    // }
}

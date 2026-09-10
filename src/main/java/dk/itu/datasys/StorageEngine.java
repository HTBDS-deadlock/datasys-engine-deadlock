package dk.itu.datasys;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Own-binary-format storage core: a JSON catalog (schema, data file,
 * partitions,
 * per-column min/max) plus one PAX-laid-out data file per table. See
 * docs/storage-design.md for the rationale behind each choice below.
 */
public final class StorageEngine {

    private static final Logger LOGGER = LoggerFactory.getLogger(StorageEngine.class);

    private static final byte[] MAGIC = { 'P', 'A', 'X', '1' };
    private static final int FORMAT_VERSION = 1;
    private static final int PAX_GROUP_SIZE = 8;
    private static final int DEFAULT_MAX_ROWS_PER_PARTITION = 65536;

    private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private final Path dataDirectory;
    private final Path catalogPath;
    private final int maxRowsPerPartition;
    private final Map<String, TableSchema> tables = new LinkedHashMap<>();
    private ScanStats lastScanStats;

    public StorageEngine(Path dataDirectory) {
        this(dataDirectory, DEFAULT_MAX_ROWS_PER_PARTITION);
    }

    public StorageEngine(Path dataDirectory, int maxRowsPerPartition) {
        this.dataDirectory = dataDirectory;
        this.catalogPath = dataDirectory.resolve("catalog.json");
        this.maxRowsPerPartition = maxRowsPerPartition;
        ensureSession();
        try {
            Files.createDirectories(dataDirectory);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        loadCatalog();
    }

    private static void ensureSession() {
        if (MDC.get("sessionId") == null) {
            MDC.put("sessionId", UUID.randomUUID().toString());
        }
        MDC.put("statementNumber", "0");
    }

    // ------------------------------------------------------------------
    // createTable
    // ------------------------------------------------------------------

    public void createTable(String tableName, List<ColumnSpec> columns) {
        ensureSession();
        if (tables.containsKey(tableName)) {
            throw new IllegalArgumentException("Table already exists: " + tableName);
        }
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("Column list cannot be empty");
        }
        Set<String> names = new HashSet<>();
        for (ColumnSpec column : columns) {
            if (!names.add(column.name())) {
                throw new IllegalArgumentException("Duplicate column name: " + column.name());
            }
        }

        TableSchema schema = new TableSchema();
        schema.columns = List.copyOf(columns);
        schema.dataFile = null;
        schema.partitions = new ArrayList<>();
        tables.put(tableName, schema);
        saveCatalog();

        LOGGER.debug("table={} action=createTable columns={}", tableName, columns.size());
    }

    // ------------------------------------------------------------------
    // copyFile
    // ------------------------------------------------------------------

    public void copyFile(String tableName, String csvFilePath) {
        ensureSession();
        TableSchema schema = tables.get(tableName);
        if (schema == null) {
            throw new IllegalArgumentException("Unknown table: " + tableName);
        }
        if (schema.dataFile != null) {
            throw new UnsupportedOperationException(
                    "Table already has data, appending is not supported yet: " + tableName);
        }

        long start = System.currentTimeMillis();
        String dataFileName = tableName + ".bin";
        Path dataFilePath = dataDirectory.resolve(dataFileName);

        List<PartitionInfo> partitions = new ArrayList<>();
        int totalRows = 0;

        try (RandomAccessFile raf = new RandomAccessFile(dataFilePath.toFile(), "rw");
                BufferedReader reader = Files.newBufferedReader(Path.of(csvFilePath), StandardCharsets.UTF_8)) {

            raf.setLength(0);
            raf.write(MAGIC);
            raf.writeInt(FORMAT_VERSION);

            List<Object[]> batch = new ArrayList<>(maxRowsPerPartition);
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                Object[] row = parseLine(line, schema.columns, csvFilePath, lineNumber);
                batch.add(row);
                totalRows++;
                if (batch.size() == maxRowsPerPartition) {
                    partitions.add(writePartition(raf, schema.columns, batch, tableName, partitions.size()));
                    batch.clear();
                }
            }
            if (!batch.isEmpty()) {
                partitions.add(writePartition(raf, schema.columns, batch, tableName, partitions.size()));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        schema.dataFile = dataFileName;
        schema.partitions = partitions;
        saveCatalog();

        long durationMs = System.currentTimeMillis() - start;
        LOGGER.debug("table={} file={} rows={} partitions={} durationMs={}",
                tableName, Path.of(csvFilePath).getFileName(), totalRows, partitions.size(), durationMs);
    }

    private PartitionInfo writePartition(RandomAccessFile raf, List<ColumnSpec> columns,
            List<Object[]> rows, String tableName, int partitionIndex) throws IOException {
        long byteOffset = raf.getFilePointer();
        int rowCount = rows.size();

        Map<String, Object[]> stats = new LinkedHashMap<>();
        for (int c = 0; c < columns.size(); c++) {
            List<Object> values = new ArrayList<>(rowCount);
            for (Object[] row : rows) {
                values.add(row[c]);
            }
            Object[] columnMinMax = minMax(values);
            stats.put(columns.get(c).name(), columnMinMax);
            LOGGER.debug("table={} partition={} column={} min={} max={}",
                    tableName, partitionIndex, columns.get(c).name(), columnMinMax[0], columnMinMax[1]);
        }

        int offset = 0;
        while (offset < rowCount) {
            int groupSize = Math.min(PAX_GROUP_SIZE, rowCount - offset);
            raf.writeInt(groupSize);
            for (int c = 0; c < columns.size(); c++) {
                ColumnType type = columns.get(c).type();
                for (int r = offset; r < offset + groupSize; r++) {
                    raf.write(encode(type, rows.get(r)[c]));
                }
            }
            offset += groupSize;
        }

        PartitionInfo info = new PartitionInfo();
        info.byteOffset = byteOffset;
        info.rowCount = rowCount;
        info.stats = stats;
        return info;
    }

    // ------------------------------------------------------------------
    // select
    // ------------------------------------------------------------------

    public List<Object[]> select(String tableName, String columnName, Comparison comparison, Object constant) {
        ensureSession();
        TableSchema schema = tables.get(tableName);
        if (schema == null) {
            throw new IllegalArgumentException("Unknown table: " + tableName);
        }

        int columnIndex = -1;
        ColumnSpec columnSpec = null;
        for (int i = 0; i < schema.columns.size(); i++) {
            if (schema.columns.get(i).name().equals(columnName)) {
                columnIndex = i;
                columnSpec = schema.columns.get(i);
                break;
            }
        }
        if (columnSpec == null) {
            throw new IllegalArgumentException("Unknown column: " + columnName);
        }
        validateConstantType(columnSpec.type(), constant);

        long start = System.currentTimeMillis();
        List<Object[]> results = new ArrayList<>();
        int partitionsTotal = schema.partitions.size();
        int partitionsRead = 0;
        int partitionsPruned = 0;

        if (schema.dataFile != null) {
            try (RandomAccessFile raf = new RandomAccessFile(dataDirectory.resolve(schema.dataFile).toFile(), "r")) {
                for (int p = 0; p < schema.partitions.size(); p++) {
                    PartitionInfo partition = schema.partitions.get(p);
                    Object[] columnMinMax = partition.stats.get(columnName);
                    boolean prune = canPrune(comparison, constant, columnMinMax[0], columnMinMax[1]);
                    LOGGER.debug("table={} column={} comparison={} const={} partition={} min={} max={} decision={}",
                            tableName, columnName, comparison, constant, p, columnMinMax[0], columnMinMax[1],
                            prune ? "PRUNED" : "READ");
                    if (prune) {
                        partitionsPruned++;
                        continue;
                    }
                    partitionsRead++;
                    for (Object[] row : readPartition(raf, partition, schema.columns)) {
                        if (matches(row[columnIndex], comparison, constant)) {
                            results.add(row);
                        }
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        lastScanStats = new ScanStats(partitionsTotal, partitionsRead, partitionsPruned);
        long durationMs = System.currentTimeMillis() - start;
        LOGGER.debug(
                "table={} column={} comparison={} const={} partitionsRead={} partitionsPruned={} rowsOut={} durationMs={}",
                tableName, columnName, comparison, constant, partitionsRead, partitionsPruned, results.size(),
                durationMs);

        return results;
    }

    /** Retrievable after a select, per the exercise's ScanStats requirement. */
    ScanStats lastScanStats() {
        return lastScanStats;
    }

    private List<Object[]> readPartition(RandomAccessFile raf, PartitionInfo partition, List<ColumnSpec> columns)
            throws IOException {
        raf.seek(partition.byteOffset);
        List<Object[]> rows = new ArrayList<>(partition.rowCount);
        int rowsRead = 0;
        while (rowsRead < partition.rowCount) {
            int groupSize = raf.readInt();
            Object[][] columnValues = new Object[columns.size()][groupSize];
            for (int c = 0; c < columns.size(); c++) {
                ColumnType type = columns.get(c).type();
                for (int r = 0; r < groupSize; r++) {
                    columnValues[c][r] = readValue(raf, type);
                }
            }
            for (int r = 0; r < groupSize; r++) {
                Object[] row = new Object[columns.size()];
                for (int c = 0; c < columns.size(); c++) {
                    row[c] = columnValues[c][r];
                }
                rows.add(row);
            }
            rowsRead += groupSize;
        }
        return rows;
    }

    private static Object readValue(RandomAccessFile raf, ColumnType type) throws IOException {
        return switch (type) {
            case LONG -> raf.readLong();
            case DOUBLE -> raf.readDouble();
            case STRING -> {
                int length = raf.readInt();
                byte[] bytes = new byte[length];
                raf.readFully(bytes);
                yield new String(bytes, StandardCharsets.UTF_8);
            }
        };
    }

    static void validateConstantType(ColumnType type, Object constant) {
        if (constant == null) {
            throw new IllegalArgumentException("constant must not be null");
        }
        boolean ok = switch (type) {
            case STRING -> constant.getClass() == String.class;
            case LONG -> constant.getClass() == Long.class;
            case DOUBLE -> constant.getClass() == Double.class;
        };
        if (!ok) {
            throw new IllegalArgumentException("Constant type " + constant.getClass().getSimpleName()
                    + " does not match column type " + type);
        }
    }

    // ------------------------------------------------------------------
    // schema lookups
    // ------------------------------------------------------------------

    /**
     * The table's schema, in column order. Throws IllegalArgumentException if
     * unknown.
     */
    public List<ColumnSpec> schema(String tableName) {
        TableSchema schema = tables.get(tableName);
        if (schema == null) {
            throw new IllegalArgumentException("Unknown table: " + tableName);
        }
        return List.copyOf(schema.columns);
    }

    // ------------------------------------------------------------------
    // Encoding, min/max, and pruning -- package-private so unit tests can
    // exercise them directly (see Note on visibility in the exercise).
    // ------------------------------------------------------------------

    static byte[] encode(ColumnType type, Object value) {
        return switch (type) {
            case LONG -> ByteBuffer.allocate(Long.BYTES).putLong((Long) value).array();
            case DOUBLE -> ByteBuffer.allocate(Double.BYTES).putDouble((Double) value).array();
            case STRING -> {
                byte[] stringBytes = ((String) value).getBytes(StandardCharsets.UTF_8);
                yield ByteBuffer.allocate(Integer.BYTES + stringBytes.length)
                        .putInt(stringBytes.length)
                        .put(stringBytes)
                        .array();
            }
        };
    }

    static Object decode(ColumnType type, byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        return switch (type) {
            case LONG -> buffer.getLong();
            case DOUBLE -> buffer.getDouble();
            case STRING -> {
                int length = buffer.getInt();
                byte[] stringBytes = new byte[length];
                buffer.get(stringBytes);
                yield new String(stringBytes, StandardCharsets.UTF_8);
            }
        };
    }

    static Object[] minMax(List<Object> values) {
        if (values.isEmpty()) {
            throw new IllegalArgumentException("Cannot compute min/max of an empty list");
        }
        Object min = values.get(0);
        Object max = values.get(0);
        for (Object value : values) {
            if (compareValues(value, min) < 0) {
                min = value;
            }
            if (compareValues(value, max) > 0) {
                max = value;
            }
        }
        return new Object[] { min, max };
    }

    static int compareValues(Object a, Object b) {
        if (a instanceof Long la && b instanceof Long lb) {
            return Long.compare(la, lb);
        }
        if (a instanceof Double da && b instanceof Double db) {
            return Double.compare(da, db);
        }
        if (a instanceof String sa && b instanceof String sb) {
            return sa.compareTo(sb);
        }
        throw new IllegalArgumentException("Incomparable values: " + a + ", " + b);
    }

    static boolean canPrune(Comparison comparison, Object constant, Object min, Object max) {
        return switch (comparison) {
            case EQUALS -> compareValues(constant, min) < 0 || compareValues(constant, max) > 0;
            case GREATER_THAN -> compareValues(max, constant) <= 0;
            case LESS_THAN -> compareValues(min, constant) >= 0;
        };
    }

    static boolean matches(Object value, Comparison comparison, Object constant) {
        int cmp = compareValues(value, constant);
        return switch (comparison) {
            case EQUALS -> cmp == 0;
            case GREATER_THAN -> cmp > 0;
            case LESS_THAN -> cmp < 0;
        };
    }

    static Object[] parseLine(String line, List<ColumnSpec> columns, String fileName, int lineNumber) {
        String[] fields = line.split(",", -1);
        if (fields.length != columns.size()) {
            throw new IllegalArgumentException("Wrong field count in file=" + fileName + " line=" + lineNumber
                    + ": expected " + columns.size() + " got " + fields.length);
        }
        Object[] row = new Object[columns.size()];
        for (int i = 0; i < columns.size(); i++) {
            ColumnType type = columns.get(i).type();
            String raw = fields[i];
            try {
                row[i] = switch (type) {
                    case STRING -> raw;
                    case LONG -> Long.parseLong(raw);
                    case DOUBLE -> Double.parseDouble(raw);
                };
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Malformed value in file=" + fileName + " line=" + lineNumber
                        + " column=" + columns.get(i).name() + " value=" + raw, e);
            }
        }
        return row;
    }

    // ------------------------------------------------------------------
    // Catalog persistence (Jackson-backed JSON, see docs/storage-design.md)
    // ------------------------------------------------------------------

    private void loadCatalog() {
        tables.clear();
        if (!Files.exists(catalogPath)) {
            return;
        }
        try {
            CatalogFile catalogFile = MAPPER.readValue(catalogPath.toFile(), CatalogFile.class);
            for (Map.Entry<String, TableEntry> entry : catalogFile.tables.entrySet()) {
                tables.put(entry.getKey(), toSchema(entry.getValue()));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void saveCatalog() {
        CatalogFile catalogFile = new CatalogFile();
        for (Map.Entry<String, TableSchema> entry : tables.entrySet()) {
            catalogFile.tables.put(entry.getKey(), toEntry(entry.getValue()));
        }
        try {
            MAPPER.writeValue(catalogPath.toFile(), catalogFile);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static TableSchema toSchema(TableEntry entry) {
        TableSchema schema = new TableSchema();
        schema.columns = new ArrayList<>();
        for (ColumnEntry columnEntry : entry.columns) {
            schema.columns.add(new ColumnSpec(columnEntry.name, ColumnType.valueOf(columnEntry.type)));
        }
        schema.dataFile = entry.dataFile;
        schema.partitions = new ArrayList<>();
        for (PartitionEntry partitionEntry : entry.partitions) {
            PartitionInfo info = new PartitionInfo();
            info.byteOffset = partitionEntry.byteOffset;
            info.rowCount = partitionEntry.rowCount;
            info.stats = new LinkedHashMap<>();
            for (Map.Entry<String, StatsEntry> statsEntry : partitionEntry.stats.entrySet()) {
                ColumnType type = columnType(schema.columns, statsEntry.getKey());
                Object min = parseTyped(type, statsEntry.getValue().min);
                Object max = parseTyped(type, statsEntry.getValue().max);
                info.stats.put(statsEntry.getKey(), new Object[] { min, max });
            }
            schema.partitions.add(info);
        }
        return schema;
    }

    private static TableEntry toEntry(TableSchema schema) {
        TableEntry entry = new TableEntry();
        entry.columns = new ArrayList<>();
        for (ColumnSpec column : schema.columns) {
            ColumnEntry columnEntry = new ColumnEntry();
            columnEntry.name = column.name();
            columnEntry.type = column.type().name();
            entry.columns.add(columnEntry);
        }
        entry.dataFile = schema.dataFile;
        entry.partitions = new ArrayList<>();
        for (PartitionInfo info : schema.partitions) {
            PartitionEntry partitionEntry = new PartitionEntry();
            partitionEntry.byteOffset = info.byteOffset;
            partitionEntry.rowCount = info.rowCount;
            partitionEntry.stats = new LinkedHashMap<>();
            for (Map.Entry<String, Object[]> statsEntry : info.stats.entrySet()) {
                StatsEntry stats = new StatsEntry();
                stats.min = String.valueOf(statsEntry.getValue()[0]);
                stats.max = String.valueOf(statsEntry.getValue()[1]);
                partitionEntry.stats.put(statsEntry.getKey(), stats);
            }
            entry.partitions.add(partitionEntry);
        }
        return entry;
    }

    private static Object parseTyped(ColumnType type, String raw) {
        return switch (type) {
            case STRING -> raw;
            case LONG -> Long.parseLong(raw);
            case DOUBLE -> Double.parseDouble(raw);
        };
    }

    private static ColumnType columnType(List<ColumnSpec> columns, String name) {
        for (ColumnSpec column : columns) {
            if (column.name().equals(name)) {
                return column.type();
            }
        }
        throw new IllegalStateException("Unknown column in catalog: " + name);
    }

    // ------------------------------------------------------------------
    // In-memory model
    // ------------------------------------------------------------------

    private static final class TableSchema {
        List<ColumnSpec> columns;
        String dataFile;
        List<PartitionInfo> partitions;
    }

    private static final class PartitionInfo {
        long byteOffset;
        int rowCount;
        Map<String, Object[]> stats;
    }

    // ------------------------------------------------------------------
    // JSON catalog model (public fields: auto-detected by Jackson, no
    // annotations needed)
    // ------------------------------------------------------------------

    private static final class CatalogFile {
        public Map<String, TableEntry> tables = new LinkedHashMap<>();
    }

    private static final class TableEntry {
        public List<ColumnEntry> columns = new ArrayList<>();
        public String dataFile;
        public List<PartitionEntry> partitions = new ArrayList<>();
    }

    private static final class ColumnEntry {
        public String name;
        public String type;
    }

    private static final class PartitionEntry {
        public long byteOffset;
        public int rowCount;
        public Map<String, StatsEntry> stats = new LinkedHashMap<>();
    }

    private static final class StatsEntry {
        public String min;
        public String max;
    }
}

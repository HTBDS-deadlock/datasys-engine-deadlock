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
    //our logger, which we can use to log messages to the console and to a file
    private static final Logger LOGGER = LoggerFactory.getLogger(StorageEngine.class);
    private static final byte[] MAGIC = { 'P', 'A', 'X', '1' };
    private static final int FORMAT_VERSION = 1;
    //the number of rows in a PAX group, which is a tradeoff between read performance
    // and memory usage
    private static final int PAX_GROUP_SIZE = 8;
    //the default maximum number of rows per partition, which is a tradeoff between read
    // performance and memory usage
    private static final int DEFAULT_MAX_ROWS_PER_PARTITION = 65536;

    //objectmapper used to read and write the catalog json file,
    private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    //data directory where the catalog and data files are stored,
    // the path to the catalog file,
    // the maximum number of rows per partition,
    // a map of table names to their schemas,
    // and the last scan stats, which is updated after a select operation and reflects the last select operations scan stats
    private final Path dataDirectory;
    private final Path catalogPath;
    private final int maxRowsPerPartition;
    private final Map<String, TableSchema> tables = new LinkedHashMap<>();
    private ScanStats lastScanStats;

    //StorageEngine constructor, which takes a data directory and an optional maximum number
    // of rows per partition, and initializes the storage engine by creating the data directory
    // if it does not exist and loading the catalog from the catalog file if it exists

    /**
     * StorageEngine constructor that calls the other constructor with the default maximum number
     * of rows per partition. Used when the user does not specify a maximum number of rows
     * @param dataDirectory the directory where the catalog and data files are stored
     */
    public StorageEngine(Path dataDirectory) {
        this(dataDirectory, DEFAULT_MAX_ROWS_PER_PARTITION);
    }

    /**
     * StorageEngine constructor that initializes the storage engine with the specified
     * data directory and maximum number of rows per partition.
     *
     * @param dataDirectory the directory where the catalog and data files are stored
     * @param maxRowsPerPartition the maximum number of rows per partition
     * @throws UncheckedIOException if an I/O error occurs while loading the catalog
     */
    public StorageEngine(Path dataDirectory, int maxRowsPerPartition) {
        //Setting the data directory, catalog path, and maximum number of rows per partition
        this.dataDirectory = dataDirectory;
        this.catalogPath = dataDirectory.resolve("catalog.json");
        this.maxRowsPerPartition = maxRowsPerPartition;
        //Ensuring that a session is active, creating the data directory if it does not exist,
        //and loading the catalog from the catalog file if it exists
        ensureSession();
        try {
            Files.createDirectories(dataDirectory);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        loadCatalog();
    }

    /**
     * ensureSession() makes sure that a session ID exists for logging. If there isnt one it
     * creates a random ID. It also resets the statement number to 0.
     */
    private static void ensureSession() {
        if (MDC.get("sessionId") == null) {
            MDC.put("sessionId", UUID.randomUUID().toString());
        }
        MDC.put("statementNumber", "0");
    }

    // ------------------------------------------------------------------
    // createTable
    // ------------------------------------------------------------------
    /**
     * Creates a new table with the specified name and columns. The table is added
     * to the catalog and the catalog is saved to the catalog file.
     *
     * @param tableName
     * @param columns
     * @throws IllegalArgumentException if the table already exists, the column list is empty, or column names repeat.
     */

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

        //Adds the new table to the catalog and persists the catalog to the catalog file.
        //The table schema is created with the specified columns, no data file, and an empty list of partitions.
        TableSchema schema = new TableSchema();
        schema.columns = List.copyOf(columns);
        schema.dataFile = null;
        schema.partitions = new ArrayList<>();
        tables.put(tableName, schema);
        saveCatalog();

        //logs the creation of the table with the table name and the number of columns in the table
        LOGGER.debug("table={} action=createTable columns={}", tableName, columns.size());
    }

    // ------------------------------------------------------------------
    // copyFile
    // ------------------------------------------------------------------

    /**
     * Copies the contents of a CSV file into the specified table. The CSV file is read line by line,
     * and each line is parsed into a row of data according to the table's schema.
     * The rows are then written to a binary data file in PAX layout, with partitions created based
     * on the maximum number of rows per partition. The catalog is updated with the new data file and
     * partition information, and the catalog is saved to the catalog file.
     * @param tableName
     * @param csvFilePath
     * @throws IllegalArgumentException if the line whose field count does not match the number of columns in the table (happens in parseLine)
     * @throws IllegalArgumentException if the table does not exist
     * @throws UnsupportedOperationException if the table already has data
     *
     */

    public void copyFile(String tableName, String csvFilePath) {
        ensureSession();
        //gets the schema of the specified table, and throws an exception if the table does not exist or already has data
        TableSchema schema = tables.get(tableName);
        if (schema == null) {
            throw new IllegalArgumentException("Unknown table: " + tableName);
        }
        if (schema.dataFile != null) {
            throw new UnsupportedOperationException(
                    "Table already has data, appending is not supported yet: " + tableName);
        }

        //start a timer to measure the duration of the copy operation
        //constructs the path to the binary data file for the table, which is named after the table with a .bin extension
        long start = System.currentTimeMillis();
        String dataFileName = tableName + ".bin";
        Path dataFilePath = dataDirectory.resolve(dataFileName);

        // initializes an empty list of partitions and a counter for the total number of rows
        List<PartitionInfo> partitions = new ArrayList<>();
        int totalRows = 0;

        //opens the binary data file for writing and the CSV file for reading, and writes the
        // magic number and format version to the binary data file
        try (RandomAccessFile raf = new RandomAccessFile(dataFilePath.toFile(), "rw");
                BufferedReader reader = Files.newBufferedReader(Path.of(csvFilePath), StandardCharsets.UTF_8)) {

            raf.setLength(0);
            raf.write(MAGIC);
            raf.writeInt(FORMAT_VERSION);

            //reads the CSV file line by line, parsing each line with the function parseLine into a row of data according
            //to the tables schema, and adding the row to a batch. When the batch reaches
            //the maximum number of rows per partition, the batch is written to the binary data file
            //as a new partition with the function writePartition, and the batch is cleared.
            // After all lines have been read, any remaining rows in the batch are written as a final partition.
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

        //updates the table schema with the new data file and partition information,
        // and saves the catalog to the catalog file
        schema.dataFile = dataFileName;
        schema.partitions = partitions;
        saveCatalog();

        //logs the completion of the copy operation with the table name, CSV file name,
        // total number of rows copied, number of partitions created,
        // and the duration of the operation in milliseconds
        long durationMs = System.currentTimeMillis() - start;
        LOGGER.debug("table={} file={} rows={} partitions={} durationMs={}",
                tableName, Path.of(csvFilePath).getFileName(), totalRows, partitions.size(), durationMs);
    }

    /**
     * Writes a partition of rows to the binary data file in PAX layout.
     * @param raf
     * @param columns
     * @param rows
     * @param tableName
     * @param partitionIndex
     * @return partition information including byte offset, row count, and per-column min/max statistics
     */
    private PartitionInfo writePartition(RandomAccessFile raf, List<ColumnSpec> columns,
            List<Object[]> rows, String tableName, int partitionIndex) throws IOException {
        //setting the byte offset to the current file pointer in the random access file,
        // and getting the row count from the size of the rows list
        long byteOffset = raf.getFilePointer();
        int rowCount = rows.size();

        //computing the min and max values for each column in the partition and storing them in a map
        Map<String, Object[]> stats = new LinkedHashMap<>();
        for (int c = 0; c < columns.size(); c++) {
            List<Object> values = new ArrayList<>(rowCount);
            for (Object[] row : rows) {
                values.add(row[c]);
            }
            Object[] columnMinMax = minMax(values);
            stats.put(columns.get(c).name(), columnMinMax);

            //logging the min and max values for each column in the partition
            LOGGER.debug("table={} partition={} column={} min={} max={}",
                    tableName, partitionIndex, columns.get(c).name(), columnMinMax[0], columnMinMax[1]);
        }
        //setting the offset to 0 and writing the rows to the binary data file in PAX layout,
        // with each group of rows preceded by its size
        int offset = 0;
        while (offset < rowCount) {
            int groupSize = Math.min(PAX_GROUP_SIZE, rowCount - offset);
            //Store the group size in the binary data file
            raf.writeInt(groupSize);

            //write each column's values for the current group of rows to the binary data file
            for (int c = 0; c < columns.size(); c++) {
                //get the column type
                ColumnType type = columns.get(c).type();
                for (int r = offset; r < offset + groupSize; r++) {
                    //converting the value to bytes using the encode method and writing it to the random access file
                    raf.write(encode(type, rows.get(r)[c]));
                }
            }
            //increment the offset by the group size to move to the next group of rows
            offset += groupSize;
        }

        //creating a new PartitionInfo object and setting its byte offset, row count, and per-column min/max statistics
        PartitionInfo info = new PartitionInfo();
        info.byteOffset = byteOffset;
        info.rowCount = rowCount;
        info.stats = stats;
        return info;
    }

    // ------------------------------------------------------------------
    // select
    // ------------------------------------------------------------------
    /**
     * Selects rows from the specified table where the values in the specified column satisfy the
     * given comparison with the provided constant.
     * The method reads the table's data file in PAX layout, prunes partitions based on the
     * min/max statistics for the specified column, and returns a list of matching rows.
     * @param tableName
     * @param columnName
     * @param comparison
     * @param constant
     * @return every matching row as an Object[] in schema column order, rows are returned in the order they appear in the data file
     * @throws IllegalArgumentException if the table or column does not exist,
             * or if the constant's type does not match the column's type with the validateConstantType method
     */
    public List<Object[]> select(String tableName, String columnName, Comparison comparison, Object constant) {
        ensureSession();
        //gets the schema of the specified table, and throws an exception if the table does not exist
        TableSchema schema = tables.get(tableName);
        if (schema == null) {
            throw new IllegalArgumentException("Unknown table: " + tableName);
        }
        //sets index to -1  and the columnSpec to null because we havent found them yet
        int columnIndex = -1;
        ColumnSpec columnSpec = null;
        //iterates through the columns in the schema to find the index and spec of the specified column
        for (int i = 0; i < schema.columns.size(); i++) {
            if (schema.columns.get(i).name().equals(columnName)) {
                columnIndex = i;
                columnSpec = schema.columns.get(i);
                break;
            }
        }
        //throws an exception if the specified column does not exist in the table
        if (columnSpec == null) {
            throw new IllegalArgumentException("Unknown column: " + columnName);
        }
        //validates that the type of the constant matches the type of the specified column
        validateConstantType(columnSpec.type(), constant);

        //starts a timer to measure the duration of the select operation,
        // initializes an empty list for the results,
        // and initializes counters for the total number of partitions,
        //the number of partitions read,
        //and the number of partitions pruned
        long start = System.currentTimeMillis();
        List<Object[]> results = new ArrayList<>();
        int partitionsTotal = schema.partitions.size();
        int partitionsRead = 0;
        int partitionsPruned = 0;

        //if the table has a data file, opens the data file for reading and iterates through the partitions
        //mode is set to "r" for read-only access, and the data file is resolved using the data directory and the data file name from the schema
        if (schema.dataFile != null) {
            try (RandomAccessFile raf = new RandomAccessFile(dataDirectory.resolve(schema.dataFile).toFile(), "r")) {
                //first validates the file header to ensure that the data file is in the expected format and version using the validateFileHeader method
                validateFileHeader(raf);
                // Iterates through the partitions in the schema, retrieves the min and max values
                // for the specified column from the partition's statistics,
                // and checks if the partition can be pruned based on the comparison and constant.
                for (int p = 0; p < schema.partitions.size(); p++) {
                    //retrieves the partition information for the current partition index
                    PartitionInfo partition = schema.partitions.get(p);
                    //retrieves the min and max values for the specified column from the partition's statistics
                    Object[] columnMinMax = partition.stats.get(columnName);
                    //checks if the partition can be pruned based on the comparison and constant using the canPrune method
                    //which compares the constant with the min and max values of the column in the partition
                    boolean prune = canPrune(comparison, constant, columnMinMax[0], columnMinMax[1]);
                    //logs the decision to prune or read the partition, along with relevant information such as table name, column name, comparison, constant, partition index, min and max values
                    LOGGER.debug("table={} column={} comparison={} const={} partition={} min={} max={} decision={}",
                            tableName, columnName, comparison, constant, p, columnMinMax[0], columnMinMax[1],
                            prune ? "PRUNED" : "READ");
                    //checks if the partition is pruned, increments the partitionsPruned counter and continues to the next partition if true
                    if (prune) {
                        partitionsPruned++;
                        continue;
                    }
                    //if the partition is not pruned, increments the partitionsRead counter and reads the partition using the readPartition method
                    //which returns a list of rows in the partition. It then iterates through the rows and
                    //checks if each row matches the comparison with the constant using the matches method.
                    //If a row matches the condition, it is added to the results list.
                    partitionsRead++;
                    for (Object[] row : readPartition(raf, partition, schema.columns)) {
                        if (matches(row[columnIndex], comparison, constant)) {
                            results.add(row);
                        }
                    }
                }
            // catches any IOException that occurs during the reading of the data file or partitions and wraps
            // it in an UncheckedIOException to propagate it as a runtime exception
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        //updates the lastScanStats with the total number of partitions, partitions read, and partitions pruned
        //lastScanStats is an instance of the ScanStats class, which is used to store statistics about the
        //last select operation performed on the storage engine
        lastScanStats = new ScanStats(partitionsTotal, partitionsRead, partitionsPruned);
        long durationMs = System.currentTimeMillis() - start;
        LOGGER.debug(
                "table={} column={} comparison={} const={} partitionsRead={} partitionsPruned={} rowsOut={} durationMs={}",
                tableName, columnName, comparison, constant, partitionsRead, partitionsPruned, results.size(),
                durationMs);

        return results;
    }

    //returns the last scan stats, which is updated after a select operation and
    // reflects the last select operations scan stats as
    ScanStats lastScanStats() {
        return lastScanStats;
    }

    /**
     * Reads a partition of rows from the binary data file in PAX layout.
     * The method seeks to the byte offset of the partition,
     * reads the rows in groups of PAX_GROUP_SIZE, and returns a list of rows as Object[] arrays
     * in schema column order.
     * @param raf
     * @param partition
     * @param columns
     * @return
     * @throws IOException
     */
    private List<Object[]> readPartition(RandomAccessFile raf, PartitionInfo partition, List<ColumnSpec> columns)
            throws IOException {
        //seeks to the byte offset of the partition in the random access file
        raf.seek(partition.byteOffset);
        //initializes an empty list to store the rows read from the partition, and a counter for the number of rows read
        List<Object[]> rows = new ArrayList<>(partition.rowCount);
        int rowsRead = 0;
        //reads the rows in groups of PAX_GROUP_SIZE, where each group is preceded by its size in the binary data file
        while (rowsRead < partition.rowCount) {
            //reads the group size from the binary data file, which indicates how many rows are in the current group
            int groupSize = raf.readInt();
            //initializes a 2D array to store the values of each column for the current group of rows
            Object[][] columnValues = new Object[columns.size()][groupSize];
            //reads the values for each column in the current group of rows from the binary data file using the readValue method,
            // which decodes the values based on the column type, and stores them in the columnValues array
            for (int c = 0; c < columns.size(); c++) {
                ColumnType type = columns.get(c).type();
                for (int r = 0; r < groupSize; r++) {
                    columnValues[c][r] = readValue(raf, type);
                }
            }
            //iterates through the rows in the current group, constructs an Object[] array for each row using the values from the columnValues array,
            //and adds the row to the rows list
            for (int r = 0; r < groupSize; r++) {
                Object[] row = new Object[columns.size()];
                for (int c = 0; c < columns.size(); c++) {
                    row[c] = columnValues[c][r];
                }
                rows.add(row);
            }
            //increments the rowsRead counter by the group size to move to the next group of rows
            rowsRead += groupSize;
        }
        //returns the list of rows read from the partition, where each row is represented as an Object[] array in schema column order
        return rows;
    }

    /**
     * Reads a value from the binary data file based on the specified column type.
     * @param raf
     * @param type
     * @return the value read from the binary data file, decoded based on the column type
     * @throws IOException
     */
    private static Object readValue(RandomAccessFile raf, ColumnType type) throws IOException {
        //return switches on the column type to read the appropriate value from raf
        return switch (type) {
            case LONG -> raf.readLong();
            case DOUBLE -> raf.readDouble();
            case STRING -> {
                int length = raf.readInt();
                byte[] bytes = new byte[length];
                raf.readFully(bytes);
                //we used UTF-8 encoding when writing the string, so we use it here to decode the bytes back into a string
                yield new String(bytes, StandardCharsets.UTF_8);
            }
        };
    }

    static void validateConstantType(ColumnType type, Object constant) {
        if (constant == null) {
            throw new IllegalArgumentException("constant must not be null");
        }
        //checks if the type of the constant matches the expected type for the specified column type using a switch expression
        boolean ok = switch (type) {
            case STRING -> constant.getClass() == String.class;
            case LONG -> constant.getClass() == Long.class;
            case DOUBLE -> constant.getClass() == Double.class;
        };
        //throws an IllegalArgumentException if the constant's type does not match the column type,
        // including the constant's class name and the expected column type in the error message
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
    /**
     * Encodes a value of the specified column type into a byte array for storage in the binary data file.
     * Used when writing data to the binary file to convert the value into a format suitable for storage.
     * @param type
     * @param value
     * @return the encoded byte array representing the value
     */
    static byte[] encode(ColumnType type, Object value) {
        //return switches on the column type to encode the value into a byte array using ByteBuffer for LONG and DOUBLE types,
        // and for STRING type, it encodes the string into UTF-8 bytes and prepends the length of the string as an integer to the byte array
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

    /**
     * Decodes a byte array from the binary data file into a value of the specified column type.
     * Used when reading data from the binary file to convert the stored bytes back into their original types.
     * @param type
     * @param bytes
     * @return the decoded value of the specified column type
     */
    static Object decode(ColumnType type, byte[] bytes) {
        //buffer wraps the byte array into a ByteBuffer for easier reading of primitive types
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        //return switches on the column type to decode the value from the byte array using ByteBuffer for LONG and DOUBLE types,
        // and for STRING type, it reads the length of the string as an integer and then reads the corresponding number of
        // bytes to construct the string using UTF-8 encoding
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

    /**
     * Computes the minimum and maximum values from a list of comparable objects.
     * @param values
     * @return an array containing the minimum value at index 0 and the maximum value at index 1
     * @throws IllegalArgumentException if the list is empty
     */
    static Object[] minMax(List<Object> values) {
        if (values.isEmpty()) {
            throw new IllegalArgumentException("Cannot compute min/max of an empty list");
        }
        Object min = values.get(0);
        Object max = values.get(0);
        //goes through each value in the list and compares it with the current min and max values
        // using the compareValues method
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

    /**
     * Compares two values of the same type (Long, Double, or String) and returns a negative integer,
     * zero, or a positive integer as the first value is less than, equal to, or greater than the second value.
     * Used in minMax, canPrune, and matches methods to compare values of different types.
     * @param a
     * @param b
     * @return a negative integer, zero, or a positive integer as the first value is less than, equal to, or greater than the second value
     * @throws IllegalArgumentException if the values are of different types or not comparable
     */
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

    /**
     * Determines whether a partition can be pruned based on the comparison, constant, and the min/max values of the column in the partition.
     * Used in the select method to decide whether to skip reading a partition based on the min/max statistics.
     * @param comparison
     * @param constant
     * @param min
     * @param max
     * @return true if the partition can be pruned, false otherwise
     */
    static boolean canPrune(Comparison comparison, Object constant, Object min, Object max) {
        return switch (comparison) {
            case EQUALS -> compareValues(constant, min) < 0 || compareValues(constant, max) > 0;
            case GREATER_THAN -> compareValues(max, constant) <= 0;
            case LESS_THAN -> compareValues(min, constant) >= 0;
        };
    }

    /**
     * Determines whether a value matches the specified comparison with the constant.
     * Used in the select method to filter rows based on the comparison and constant.
     * @param value
     * @param comparison
     * @param constant
     * @return true if the value matches the comparison with the constant, false otherwise
     */
    static boolean matches(Object value, Comparison comparison, Object constant) {
        int cmp = compareValues(value, constant);
        return switch (comparison) {
            case EQUALS -> cmp == 0;
            case GREATER_THAN -> cmp > 0;
            case LESS_THAN -> cmp < 0;
        };
    }

    /**
     * Parses a line from a CSV file into an array of objects based on the specified column specifications.
     * Used in the copyFile method to convert each line of the CSV file into a row of data according to the table's schema.
     * @param line
     * @param columns
     * @param fileName
     * @param lineNumber
     * @return an array of objects representing the parsed values of the line, in schema column order
     * @throws IllegalArgumentException if the number of fields in the line does not match the number of columns in the table,
             * or if a value cannot be parsed into the expected type
     */
    static Object[] parseLine(String line, List<ColumnSpec> columns, String fileName, int lineNumber) {
        //splits the line into fields using a comma as the delimiter, allowing for empty fields
        String[] fields = line.split(",", -1);
        //checks if the number of fields matches the number of columns in the table, throwing an exception if they do not match
        if (fields.length != columns.size()) {
            throw new IllegalArgumentException("Wrong field count in file=" + fileName + " line=" + lineNumber
                    + ": expected " + columns.size() + " got " + fields.length);
        }
        //initializes an array to hold the parsed values of the line, with the same length as the number of columns
        Object[] row = new Object[columns.size()];
        //iterates through the columns and fields, parsing each field into the appropriate type based on the column specification
        for (int i = 0; i < columns.size(); i++) {
            ColumnType type = columns.get(i).type();
            String raw = fields[i];
            try {
                //uses a switch expression to parse the raw field value into the appropriate type based on the column type
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
        //returns the parsed row as an array of objects, where each object corresponds to a column in the table's schema
        return row;
    }

    // ------------------------------------------------------------------
    // Catalog persistence (Jackson-backed JSON, see docs/storage-design.md)
    // ------------------------------------------------------------------
    /**
     * Loads the catalog from the catalog file (catalog.json) into memory. If the catalog file does not exist, it initializes an empty catalog.
     * The catalog is represented as a map of table names to their corresponding table schemas.
     * Used during the initialization of the StorageEngine to ensure that the catalog is available for operations like fx creating tables and copying files.
     * @throws UncheckedIOException if an I/O error occurs while reading the catalog file
     */
    private void loadCatalog() {
        //clears the in-memory catalog of tables to ensure a fresh start before loading from the catalog file
        tables.clear();
        //checks if the catalog file exists, and if it does not exist, the method returns without loading any tables
        if (!Files.exists(catalogPath)) {
            return;
        }
        //uses ObjectMapper to read the catalog file and deserialize it into a CatalogFile object,
        // then iterates through the entries in the CatalogFile to convert each TableEntry into a
        // TableSchema and store it in the in-memory catalog
        try {
            CatalogFile catalogFile = MAPPER.readValue(catalogPath.toFile(), CatalogFile.class);
            for (Map.Entry<String, TableEntry> entry : catalogFile.tables.entrySet()) {
                tables.put(entry.getKey(), toSchema(entry.getValue()));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Saves the in-memory catalog of tables to the catalog file (catalog.json) in json format.
     * The catalog is represented as a map of table names to their corresponding table schemas,
     * which are converted to TableEntry objects for serialization.
     * Used after operations that modify the catalog, such as creating tables or copying files, to
     * persist the changes to disk.
     * @throws UncheckedIOException if an I/O error occurs while writing to the catalog file
     */
    private void saveCatalog() {
        //creates a new CatalogFile object
        CatalogFile catalogFile = new CatalogFile();
        //iterates through the in-memory catalog of tables, converting each TableSchema into a
        // TableEntry and adding it to the CatalogFile's tables map
        for (Map.Entry<String, TableSchema> entry : tables.entrySet()) {
            catalogFile.tables.put(entry.getKey(), toEntry(entry.getValue()));
        }
        //uses ObjectMapper to serialize the CatalogFile object to JSON and write it to the catalog file on disk
        try {
            MAPPER.writeValue(catalogPath.toFile(), catalogFile);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
    /**
     * Converts a TableEntry object (used for json serialization) into a TableSchema object (used in-memory).
     * Used when loading the catalog from the catalog file to reconstruct the in-memory representation of the tables.
     * @param entry
     * @return the corresponding TableSchema object with columns, data file, and partition information
     */
    private static TableSchema toSchema(TableEntry entry) {
        //creates a new TableSchema object and initializes its columns and partitions lists
        TableSchema schema = new TableSchema();
        schema.columns = new ArrayList<>();
        //iterates through the columns in the TableEntry, creating a ColumnSpec for each column and adding it to the TableSchema's columns list
        for (ColumnEntry columnEntry : entry.columns) {
            schema.columns.add(new ColumnSpec(columnEntry.name, ColumnType.valueOf(columnEntry.type)));
        }
        //sets the data file name in the TableSchema to the data file name from the TableEntry
        schema.dataFile = entry.dataFile;
        //initializes the partitions list in the TableSchema to an empty list
        schema.partitions = new ArrayList<>();

        //iterates through the partitions in the TableEntry, creating a PartitionInfo for each
        //partition and adding it to the TableSchema's partitions list
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

    /**
     * Converts a TableSchema object (used in-memory) into a TableEntry object (used for json serialization).
     * Used when saving the catalog to the catalog file to serialize the in-memory representation of the tables.
     * @param schema
     * @return the corresponding TableEntry object with columns, data file, and partition information
     */
    private static TableEntry toEntry(TableSchema schema) {
        TableEntry entry = new TableEntry();
        entry.columns = new ArrayList<>();
        //iterates through the columns in the TableSchema, creating a ColumnEntry
        // for each column and adding it to the TableEntry's columns list
        for (ColumnSpec column : schema.columns) {
            ColumnEntry columnEntry = new ColumnEntry();
            columnEntry.name = column.name();
            columnEntry.type = column.type().name();
            entry.columns.add(columnEntry);
        }
        entry.dataFile = schema.dataFile;
        entry.partitions = new ArrayList<>();
        //iterates through the partitions in the TableSchema, creating a PartitionEntry for each partition
        // and adding it to the TableEntry's partitions list, including the byte offset, row count, and per-column min/max statistics
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
        //returns the constructed TableEntry object, which can be serialized to JSON for catalog persistence
        return entry;
    }

    /**
     * Parses a raw string value into an object of the specified column type.
     * Used in the method toSchema (where the catalog is loaded from the catalog file,
     * to convert the min/max statistics from strings back into their original types for in-memory representation.
     * @param type
     * @param raw
     * @return the parsed object of the specified column type
     */
    private static Object parseTyped(ColumnType type, String raw) {
        return switch (type) {
            case STRING -> raw;
            case LONG -> Long.parseLong(raw);
            case DOUBLE -> Double.parseDouble(raw);
        };
    }

    /**
     * Finds the column type for a given column name in the list of column specifications.
     * Used in the method toSchema (where the catalog is loaded from the catalog file,
     * to determine the type of a column based on its name when reconstructing the in-memory representation of the table schema.
     * @param columns
     * @param name
     * @return the ColumnType of the specified column name
     * @throws IllegalStateException if the column name is not found in the list of column specifications, indicating an inconsistency in the catalog data
     */
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
    /**
     * Represents the schema of a table, including its columns, data file name, and partition information.
     * Used in-memory to manage the structure and metadata of tables within the storage engine.
     * TableSchema
     */
    private static final class TableSchema {
        List<ColumnSpec> columns;
        String dataFile;
        List<PartitionInfo> partitions;
    }

    /**
     * Represents information about a partition of rows in a table, including its byte offset in the data file,
     * the number of rows in the partition, and per-column min/max statistics.
     * Used in-memory to manage the metadata of partitions within the storage engine.
     */
    private static final class PartitionInfo {
        long byteOffset;
        int rowCount;
        Map<String, Object[]> stats;
    }

    // ------------------------------------------------------------------
    // JSON catalog model (public fields: auto-detected by Jackson, no
    // annotations needed)
    // ------------------------------------------------------------------
    /**
     * Represents the structure of the catalog file (catalog.json) for JSON serialization and deserialization.
     * Contains a map of table names to their corresponding TableEntry objects, which hold the schema and partition information for each table.
     * Used for persisting the catalog to disk and loading it
     *
     */
    private static final class CatalogFile {
        public Map<String, TableEntry> tables = new LinkedHashMap<>();
    }
    /**
     * Represents the schema of a table for JSON serialization and deserialization.
     * Contains a list of ColumnEntry objects for the table's columns, the name of the data file, and a list of PartitionEntry objects for the table's partitions.
     * Used for persisting the table schema to disk and loading it from the catalog file.
     *
     */
    private static final class TableEntry {
        public List<ColumnEntry> columns = new ArrayList<>();
        public String dataFile;
        public List<PartitionEntry> partitions = new ArrayList<>();
    }
    /**
     * Represents a column in a table for JSON serialization and deserialization.
     * Contains the name and type of the column.
     */
    private static final class ColumnEntry {
        public String name;
        public String type;
    }

    /**
     * Represents a partition of rows in a table for JSON serialization and deserialization.
     * Contains the byte offset of the partition in the data file, the number of rows in
     * the partition, and a map of per-column min/max statistics.
     * Used for persisting the partition metadata to disk and loading it from the catalog file.
     */
    private static final class PartitionEntry {
        public long byteOffset;
        public int rowCount;
        public Map<String, StatsEntry> stats = new LinkedHashMap<>();
    }

    /**
     * Represents the min and max values of a column in a partition for JSON serialization and deserialization.
     * Contains the minimum and maximum values as strings, which are converted to their original types when loading the catalog into memory.
     * Used for persisting the per-column statistics to disk and loading them from the catalog file.
     */
    private static final class StatsEntry {
        public String min;
        public String max;
    }
}

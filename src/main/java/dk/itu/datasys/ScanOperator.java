package dk.itu.datasys;

import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ScanOperator implements Operator {
    private static final Logger LOGGER = LoggerFactory.getLogger(ScanOperator.class);

    private final StorageEngine engine;
    private final StorageEngine.TableSnapshot snapshot;
    private final String tableName;
    private final List<Integer> partitionNumbers;
    // the columns the query uses; null means all columns are read
    private final Set<String> neededColumns;
    private int nextPartition;
    private Iterator<Object[]> rows = Collections.emptyIterator();
    private int rowsOut;

    public ScanOperator(StorageEngine engine, String tableName, List<Integer> partitionNumbers) {
        this(engine, tableName, partitionNumbers, null);
    }

    public ScanOperator(StorageEngine engine, String tableName, List<Integer> partitionNumbers,
            Set<String> neededColumns) {
        this.engine = engine;
        this.snapshot = partitionNumbers.isEmpty() ? null : engine.snapshot(tableName);
        this.tableName = tableName;
        this.partitionNumbers = partitionNumbers;
        this.neededColumns = neededColumns;
    }

    public ScanOperator(StorageEngine engine, StorageEngine.TableSnapshot snapshot,
            List<Integer> partitionNumbers, Set<String> neededColumns) {
        this.engine = engine;
        this.snapshot = snapshot;
        this.tableName = snapshot.tableName();
        this.partitionNumbers = partitionNumbers;
        this.neededColumns = neededColumns;
    }

    public void open() {
        nextPartition = 0;
        rows = Collections.emptyIterator();
        rowsOut = 0;
    }

    public Object[] next() {
        while (!rows.hasNext()) {
            if (nextPartition == partitionNumbers.size())
                return null;
            rows = engine.readPartition(snapshot, partitionNumbers.get(nextPartition++), neededColumns).iterator();
        }

        rowsOut++;
        return rows.next();
    }

    public void close() {
        rows = Collections.emptyIterator();
        LOGGER.debug("table={} partitions={} rowsOut={}", tableName, partitionNumbers.size(), rowsOut);
    }
}
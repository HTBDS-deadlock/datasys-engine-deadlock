package dk.itu.datasys;

import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ScanOperator implements Operator {
    private static final Logger LOGGER = LoggerFactory.getLogger(ScanOperator.class);

    private final StorageEngine engine;
    private final String tableName;
    private final List<Integer> partitionNumbers;
    private int nextPartition;
    private Iterator<Object[]> rows = Collections.emptyIterator();
    private int rowsOut;

    public ScanOperator(StorageEngine engine, String tableName, List<Integer> partitionNumbers) {
        this.engine = engine;
        this.tableName = tableName;
        this.partitionNumbers = partitionNumbers;
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
            rows = engine.readPartition(tableName, partitionNumbers.get(nextPartition++)).iterator();
        }

        rowsOut++;
        return rows.next();
    }

    public void close() {
        rows = Collections.emptyIterator();
        LOGGER.debug("table={} partitions={} rowsOut={}", tableName, partitionNumbers.size(), rowsOut);
    }
}
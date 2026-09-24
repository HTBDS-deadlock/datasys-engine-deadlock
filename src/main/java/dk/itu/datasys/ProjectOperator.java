package dk.itu.datasys;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Narrows and reorders each row from child to just the requested columns. */
public final class ProjectOperator implements Operator {
    private static final Logger LOGGER = LoggerFactory.getLogger(ProjectOperator.class);

    private final Operator child;
    private final List<Integer> columnIndexes;
    private int rowsOut;

    public ProjectOperator(Operator child, List<ColumnSpec> schema, List<String> columns) {
        this.child = child;
        this.columnIndexes = columns.stream()
                .map(name -> resolveColumnIndex(schema, name))
                .toList();
    }

    @Override
    public void open() {
        child.open();
        rowsOut = 0;
    }

    @Override
    public Object[] next() {
        Object[] row = child.next();
        if (row == null) {
            return null;
        }
        Object[] projected = new Object[columnIndexes.size()];
        for (int i = 0; i < columnIndexes.size(); i++) {
            projected[i] = row[columnIndexes.get(i)];
        }
        rowsOut++;
        return projected;
    }

    @Override
    public void close() {
        child.close();
        LOGGER.debug("columns={} rowsOut={}", columnIndexes.size(), rowsOut);
    }

    private static int resolveColumnIndex(List<ColumnSpec> schema, String name) {
        for (int i = 0; i < schema.size(); i++) {
            if (schema.get(i).name().equals(name)) {
                return i;
            }
        }
        throw new IllegalArgumentException("Unknown column: " + name);
    }
}

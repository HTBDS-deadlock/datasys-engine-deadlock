package dk.itu.datasys;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class FilterOperator implements Operator {
    private static final Logger LOGGER = LoggerFactory.getLogger(FilterOperator.class);

    private final Operator child;
    private final List<ColumnSpec> schema;
    private final Predicate predicate;
    private int columnIndex;
    private int rowsIn;
    private int rowsOut;

    public FilterOperator(Operator child, List<ColumnSpec> schema, Predicate predicate) {
        this.child = child;
        this.schema = schema;
        this.predicate = predicate;
    }

    public void open() {
        child.open();
        columnIndex = resolveColumnIndex();
        rowsIn = 0;
        rowsOut = 0;
    }

    public Object[] next() {
        Object[] row;
        while ((row = child.next()) != null) {
            rowsIn++;
            if (StorageEngine.matches(row[columnIndex], predicate.comparison(), predicate.constant())) {
                rowsOut++;
                return row;
            }
        }
        return null;
    }

    public void close() {
        child.close();
        LOGGER.debug("column={} comparison={} const={} rowsIn={} rowsOut={}",
                predicate.columnName(), predicate.comparison(), predicate.constant(), rowsIn, rowsOut);
    }

    private int resolveColumnIndex() {
        for (int i = 0; i < schema.size(); i++) {
            if (schema.get(i).name().equals(predicate.columnName())) {
                return i;
            }
        }
        throw new IllegalArgumentException("Unknown column: " + predicate.columnName());
    }
}

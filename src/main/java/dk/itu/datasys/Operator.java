package dk.itu.datasys;

import java.util.ArrayList;
import java.util.List;

public interface Operator {
    void open();

    Object[] next(); // one row in schema column order, or null when exhausted

    void close();

    /** Opens operator, pulls every row from it, then closes it. */
    static List<Object[]> drain(Operator operator) {
        operator.open();
        List<Object[]> rows = new ArrayList<>();
        Object[] row;
        while ((row = operator.next()) != null) {
            rows.add(row);
        }
        operator.close();
        return rows;
    }
}

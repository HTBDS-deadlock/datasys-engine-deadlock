package dk.itu.datasys;

import java.nio.file.Path;
import java.util.List;

public class main {

    
    public static void main(String[] args) {
        List<ColumnSpec> columns = List.of(
                new ColumnSpec("city", ColumnType.STRING),
                new ColumnSpec("distance", ColumnType.LONG),
                new ColumnSpec("price", ColumnType.DOUBLE)
        );
        StorageEngine engine = new StorageEngine(Path.of("data"));

        engine.createTable("trips", columns);
    }

}

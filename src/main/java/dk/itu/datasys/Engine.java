package dk.itu.datasys;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

public final class Engine {
    //our logger, which we can use to log messages to the console and to a file
    private static final Logger LOGGER = LoggerFactory.getLogger(Engine.class);

    //the main method which is the entry point of the program and right now runs
    // the golden demo as of exercise 2.5
    public static void main(String[] args) {
        MDC.put("statementNumber", "0");
        MDC.put("sessionId", UUID.randomUUID().toString());
        LOGGER.debug("engine started");
        try {
            runGoldenDemo();
        } finally {
            LOGGER.debug("engine stopped");
        }
    }

    /** Builds the golden trips table in the data directory and runs the three example queries. */
    private static void runGoldenDemo() {
            //store it in data directory, which is the default data directory for the engine
            Path dataDirectory = Path.of("data");
            StorageEngine engine = new StorageEngine(dataDirectory);

            List<ColumnSpec> columns = List.of(
                    new ColumnSpec("city", ColumnType.STRING),
                    new ColumnSpec("distance", ColumnType.LONG),
                    new ColumnSpec("price", ColumnType.DOUBLE));
            engine.createTable("trips", columns);
            engine.copyFile("trips", Path.of("src", "test", "resources", "trips.csv").toString());

            printResults(engine, "distance > 100", "trips", "distance", Comparison.GREATER_THAN, 100L);
            printResults(engine, "city = Copenhagen", "trips", "city", Comparison.EQUALS, "Copenhagen");
            printResults(engine, "price < 50.0", "trips", "price", Comparison.LESS_THAN, 50.0);
    }

    private static void printResults(StorageEngine engine, String label, String table, String column,
            Comparison comparison, Object constant) {
        System.out.println(label + ":");
        for (Object[] row : engine.select(table, column, comparison, constant)) {
            StringBuilder line = new StringBuilder("  ");
            for (int i = 0; i < row.length; i++) {
                if (i > 0) {
                    line.append(" | ");
                }
                line.append(row[i]);
            }
            System.out.println(line);
        }
    }

    String teamName() {
        return "Team Deadlock";
    }
}

package dk.itu.datasys;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

public final class Engine {
    // our logger, which we can use to log messages to the console and to a file
    private static final Logger LOGGER = LoggerFactory.getLogger(Engine.class);

    // the main method which is the entry point of the program; the SQL front
    // door. No arguments: usage. One argument: that argument is the SQL text to
    // run. "-f <path>" (two arguments): run the whole script at that path. Each
    // SELECT's rows go to stdout as headerless CSV; nothing else touches stdout.
    public static void main(String[] args) {
        MDC.put("statementNumber", "0");
        MDC.put("sessionId", UUID.randomUUID().toString());
        LOGGER.debug("engine started");

        // run("SELECT * FROM trips WHERE city = 'Odense';");

        try {
            String sqlText;
            if (args.length == 2 && args[0].equals("-c")) { // Single SQL input
                // May omit its trailing ';' (the exercise's own quoting examples
                // do), unlike a script file, where every statement already ends
                // with one.
                sqlText = args[1].strip();
                if (!sqlText.endsWith(";")) {
                    sqlText += ";";
                }
            } else if (args.length == 2 && args[0].equals("-f")) {
                // SQL input with a file as argument 2.
                sqlText = readScript(args[1]);
            } else {
                printUsage(); // No arguments. Print team name etc.
                return;
            }
        } catch (RuntimeException e) {
            // keeps stdout clean: the failure's message goes to stderr, never
            // a raw stack trace mixed into the CSV output
            System.err.println(e.getMessage());
        } finally {
            LOGGER.debug("engine stopped");
        }
    }

    private static void run(String sqlText) {
        StorageEngine engine = new StorageEngine(Path.of("data"));
        Executor executor = new Executor(engine);

        for (ExecutionResult result : executor.run(sqlText)) {
            result.rows().ifPresent(Engine::printCsv);
        }
    }

    private static String readScript(String path) {
        try {
            return Files.readString(Path.of(path));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void printCsv(List<Object[]> rows) {
        for (Object[] row : rows) {
            System.out.println(Arrays.stream(row)
                    .map(String::valueOf)
                    .collect(Collectors.joining(",")));
        }
    }

    private static void printUsage() {
        System.out.println(new Engine().teamName());
        System.out.println("Usage:");
        System.out.println("  ./engine -c \"<sql statement>\"");
        System.out.println("  ./engine -f <path-to-script.sql>");

        /*
         * 
         * 
         * # Package (rebuilds engine)
         * mvn package
         * 
         * # create and populate trips table
         * $ ./engine -c
         * "CREATE TABLE trips (city STRING, distance LONG, price DOUBLE);
         * COPY trips FROM 'src/test/resources/trips.csv';"
         * 
         * # Test select sql
         * ./engine -c "SELECT * from trips"
         * 
         * 
         */

    }

    String teamName() {
        return "Team Deadlock";
    }

}

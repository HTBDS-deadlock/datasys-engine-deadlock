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

        try {
            String sqlText;
            if (args.length == 1) { // Single SQL input
                // May omit its trailing ';' (the exercise's own quoting examples
                // do), unlike a script file, where every statement already ends
                // with one.
                sqlText = args[0].strip();
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

            run(sqlText);
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
        System.out.println("  mvn -q compile exec:java -Dexec.args=\"'<sql statement>'\"");
        System.out.println("  mvn -q compile exec:java -Dexec.args=\"-f <path-to-script.sql>\"");
    }

    String teamName() {
        return "Team Deadlock";
    }

}

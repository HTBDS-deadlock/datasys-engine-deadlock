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
    // our logger, which we can use to log messages to the console and to a file
    private static final Logger LOGGER = LoggerFactory.getLogger(Engine.class);

    // the main method which is the entry point of the program; the SQL front door:
    // takes either a single SQL statement or a path to a script file as its one
    // argument, runs it through the executor, and prints each statement's
    // pretty-printed form followed by its rows (if it was a SELECT).
    public static void main(String[] args) {
        MDC.put("statementNumber", "0");
        MDC.put("sessionId", UUID.randomUUID().toString());
        LOGGER.debug("engine started");

        try {
            if (args.length != 1) {
                printUsage();
                return;
            }

            String sqlText = readSqlArgument(args[0]);
            StorageEngine engine = new StorageEngine(Path.of("data"));
            Executor executor = new Executor(engine);
            SqlPrinter printer = new SqlPrinter();

            for (ExecutionResult result : executor.run(sqlText)) {
                System.out.println(printer.print(result.statement()));
                result.rows().ifPresent(Engine::printRows);
            }
        } finally {
            LOGGER.debug("engine stopped");
        }
    }

    // reads the SQL text to run: the argument itself if it isn't an existing
    // file, or that file's contents if it is (so the same argument slot covers
    // both a single inline statement/script and a path to a script file)
    private static String readSqlArgument(String arg) {
        Path path = Path.of(arg);
        if (Files.isRegularFile(path)) {
            try {
                return Files.readString(path);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return arg;
    }

    private static void printRows(List<Object[]> rows) {
        for (Object[] row : rows) {
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

    private static void printUsage() {
        System.out.println("Usage: mvn compile exec:java -Dexec.args=\"<sql-statement-or-path-to-script-file>\"");
    }

    String teamName() {
        return "Team Deadlock";
    }

}

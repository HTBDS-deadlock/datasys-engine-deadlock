package dk.itu.datasys;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * A small local web front end: serves web/index.html and runs the SQL posted
 * to /query through the same Executor the command line uses.
 *
 * Each request opens and closes its own StorageEngine, exactly like one
 * ./engine invocation, so every request's changes reach disk before the
 * response is sent. The server handles one request at a time and listens on
 * the loopback address only.
 */
public final class WebServer {
    private static final Logger LOGGER = LoggerFactory.getLogger(WebServer.class);

    private static final Path WEB_DIRECTORY = Path.of("web");
    private static final Path DATA_DIRECTORY = Path.of("data");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final SqlPrinter printer = new SqlPrinter();

    public static void start(int port) throws IOException {
        WebServer webServer = new WebServer();
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        server.createContext("/", webServer::handleIndex);
        server.createContext("/query", webServer::handleQuery);
        server.start(); // default executor: a single thread, so requests never overlap
        LOGGER.debug("web server started port={}", port);
        System.err.println("Deadlock web UI running at http://localhost:" + port + "  (Ctrl+C to stop)");
    }

    private void handleIndex(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestURI().getPath().equals("/")) {
            send(exchange, 404, "text/plain", "Not found");
            return;
        }
        Path index = WEB_DIRECTORY.resolve("index.html");
        if (!Files.exists(index)) {
            send(exchange, 500, "text/plain",
                    "web/index.html not found; start the server from the project root");
            return;
        }
        send(exchange, 200, "text/html; charset=utf-8", Files.readString(index));
    }

    private void handleQuery(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equals("POST")) {
            send(exchange, 405, "text/plain", "POST SQL text to /query");
            return;
        }
        String sqlText = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8).strip();
        if (!sqlText.isEmpty() && !sqlText.endsWith(";")) {
            sqlText += ";"; // like ./engine -c, the last statement may omit its ';'
        }

        long start = System.currentTimeMillis();
        List<Map<String, Object>> results = new ArrayList<>();
        Map<String, Object> error = null;

        MDC.put("statementNumber", "0");
        try (StorageEngine engine = new StorageEngine(DATA_DIRECTORY)) {
            Executor executor = new Executor(engine);
            executor.run(sqlText, result -> results.add(describe(result, engine)));
        } catch (SqlParseException e) {
            error = new LinkedHashMap<>();
            error.put("message", e.getMessage());
            error.put("line", e.line());
            error.put("column", e.column());
        } catch (RuntimeException e) {
            LOGGER.error("web query failed reason={}", e.getMessage());
            error = new LinkedHashMap<>();
            error.put("message", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("results", results);
        response.put("error", error);
        response.put("durationMs", System.currentTimeMillis() - start);
        send(exchange, 200, "application/json; charset=utf-8", JSON.writeValueAsString(response));
    }

    /** One statement's result as JSON-ready data: its SQL, and its rows with column names for a SELECT. */
    private Map<String, Object> describe(ExecutionResult result, StorageEngine engine) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sql", printer.print(result.statement()));
        if (result.statement() instanceof SelectStatement select && result.rows().isPresent()) {
            List<Object[]> rows = result.rows().get();
            out.put("columns", columnNames(select, engine));
            // Cells as text, formatted exactly like the CLI's CSV (2999.0 stays 2999.0);
            // numeric says which columns to right-align.
            out.put("numeric", rows.isEmpty() ? List.of()
                    : Arrays.stream(rows.get(0)).map(value -> value instanceof Number).toList());
            out.put("rows", rows.stream()
                    .map(row -> Arrays.stream(row).map(String::valueOf).toList())
                    .toList());
        } else {
            out.put("message", "OK");
        }
        return out;
    }

    /** The SELECT list as written, or for SELECT * every table's columns in FROM-list order. */
    private static List<String> columnNames(SelectStatement select, StorageEngine engine) {
        if (!select.columns().isEmpty()) {
            return select.columns();
        }
        List<String> names = new ArrayList<>();
        for (String tableName : select.tableNames()) {
            for (ColumnSpec column : engine.schema(tableName)) {
                names.add(column.name());
            }
        }
        return names;
    }

    private static void send(HttpExchange exchange, int status, String contentType, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}

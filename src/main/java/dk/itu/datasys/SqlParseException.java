package dk.itu.datasys;

public final class SqlParseException extends RuntimeException {
    private final int line;
    private final int column;

    public SqlParseException(String message, int line, int column) {
        super(message);
        this.line = line;
        this.column = column;
    }

    public int line() { return line; } // 1-based

    public int column() { return column; } // 0-based, ANTLR's convention
}

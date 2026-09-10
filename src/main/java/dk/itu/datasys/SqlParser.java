package dk.itu.datasys;

import java.util.List;

import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;

import dk.itu.datasys.sql.sqlLexer;
import dk.itu.datasys.sql.sqlParser.ScriptContext;

public final class SqlParser {
    private static final BaseErrorListener THROWING_ERROR_LISTENER = new BaseErrorListener() {
        @Override
        public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol, int line,
                int charPositionInLine, String msg, RecognitionException e) {
            throw new SqlParseException(msg, line, charPositionInLine);
        }
    };

    /** Parses a whole script of ';'-terminated statements. */
    public List<Statement> parse(String sqlText) {
        sqlLexer lexer = new sqlLexer(CharStreams.fromString(sqlText));
        lexer.removeErrorListeners();
        lexer.addErrorListener(THROWING_ERROR_LISTENER);

        dk.itu.datasys.sql.sqlParser parser = new dk.itu.datasys.sql.sqlParser(new CommonTokenStream(lexer));
        parser.removeErrorListeners();
        parser.addErrorListener(THROWING_ERROR_LISTENER);

        ScriptContext script = parser.script();
        SqlAstBuilder astBuilder = new SqlAstBuilder();
        return script.statement().stream()
                .map(statement -> statement.accept(astBuilder))
                .toList();
    }
}

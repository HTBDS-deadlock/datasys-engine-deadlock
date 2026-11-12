package dk.itu.datasys;

/**
 * One entry in a FROM list: the stored table and the name the query uses for
 * it. Without an explicit alias, alias equals name. Qualified column
 * references (alias.column) always use the alias.
 */
public record TableRef(String name, String alias) {
    public TableRef(String name) {
        this(name, name);
    }
}

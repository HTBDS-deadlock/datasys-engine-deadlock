package dk.itu.datasys;

//Gives us the type and name of a column in a table
public record ColumnSpec(String name, ColumnType type) {}

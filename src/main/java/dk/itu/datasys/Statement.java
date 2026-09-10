package dk.itu.datasys;

import java.util.List;
import java.util.Optional;

public sealed interface Statement
        permits CreateTableStatement, CopyStatement, SelectStatement {
};
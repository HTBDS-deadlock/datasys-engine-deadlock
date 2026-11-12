
grammar sql;

options { caseInsensitive = true; }   // ANTLR ≥ 4.10

script       : (statement ';')+ EOF ;
statement    : createTable | copy | select | delete ;

createTable  : CREATE TABLE IDENTIFIER '(' columnDef (',' columnDef)* ')' ;
columnDef    : IDENTIFIER columnType ;
columnType   : STRING | LONG | DOUBLE ;

copy         : COPY IDENTIFIER FROM STRING_LITERAL ;

select       : SELECT selectList FROM tableRef (',' tableRef)* (WHERE condition)? ;
selectList   : '*' | columnRef (',' columnRef)* ;
tableRef     : table=IDENTIFIER (AS? alias=IDENTIFIER)? ;
columnRef    : (qualifier=IDENTIFIER '.')? column=IDENTIFIER ;   // c_city or c1.c_city
condition    : predicate (AND predicate)* ;
predicate    : filter | join ;
filter       : columnRef comparison=('=' | '<' | '>') literal ;
join         : left=columnRef '=' right=columnRef ;

delete       : DELETE FROM IDENTIFIER (WHERE filter)? ;

literal      : STRING_LITERAL | LONG_LITERAL | DOUBLE_LITERAL ;


// Lexer. Keyword rules MUST precede IDENTIFIER, or IDENTIFIER swallows them.
CREATE : 'CREATE' ;   TABLE : 'TABLE' ;   COPY : 'COPY' ;   FROM : 'FROM' ;
SELECT : 'SELECT' ;   WHERE : 'WHERE' ;   DELETE : 'DELETE' ;   AND : 'AND' ;   AS : 'AS' ;
STRING : 'STRING' ;   LONG : 'LONG' ;   DOUBLE : 'DOUBLE' ;

IDENTIFIER      : [A-Z_] [A-Z_0-9]* ;        // caseInsensitive covers a–z
LONG_LITERAL    : '-'? [0-9]+ ;
DOUBLE_LITERAL  : '-'? [0-9]+ '.' [0-9]+ ;
STRING_LITERAL  : '\'' ~['\r\n]* '\'' ;
LINE_COMMENT    : '--' ~[\r\n]* -> skip ;
WS              : [ \t\r\n]+ -> skip ;
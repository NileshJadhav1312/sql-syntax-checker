package com.sqlorb;

public enum TokenType {
    // Keywords
    SELECT, FROM, WHERE, DISTINCT, AS, ORDER_BY, GROUP_BY, HAVING, LIMIT, OFFSET, FETCH, TOP,
    INSERT, INTO, VALUES, UPDATE, SET, DELETE,
    CREATE, ALTER, DROP, TRUNCATE, RENAME,
    DATABASE, TABLE, VIEW, INDEX, SEQUENCE, SCHEMA,
    PRIMARY_KEY, FOREIGN_KEY, REFERENCES, UNIQUE, NOT_NULL, CHECK, DEFAULT,
    JOIN, INNER_JOIN, LEFT_JOIN, RIGHT_JOIN, FULL_JOIN, CROSS_JOIN, ON, USING,
    UNION, UNION_ALL, INTERSECT, EXCEPT, IN, EXISTS, ANY, ALL,
    AND, OR, NOT, CASE, WHEN, THEN, ELSE, END,
    NULL, IS, TRUE, FALSE,
    ASC, DESC, BETWEEN, LIKE, ESCAPE,
    COUNT, SUM, AVG, MIN, MAX,
    COMMIT, ROLLBACK, SAVEPOINT, BEGIN, TRANSACTION,
    GRANT, REVOKE,

    // Data
    IDENTIFIER, // Table/Column names like "users", "age"
    NUMBER,     // 123
    STRING,     // 'John'
    // Arithmetic Operators
    PLUS, // +
    MINUS, // -
    STAR, // *
    SLASH, // /
    PERCENT, // %

    // Comparison Operators
    EQUALS, // =
    NOT_EQUALS, // !=
    NOT_EQUALS_SQL, // <>
    GT, // >
    LT, // <
    GE, // >=
    LE, // <=

    // Symbols
    COMMA, // ,
    SEMICOLON, // ;
    LPAREN, // (
    RPAREN, // )
    DOT,    // .

    // Pattern Matching (use PERCENT token)

    // End of File
    EOF
}
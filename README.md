**Overview**
- **File**: Parser.java — parses a simple SELECT query form and validates basic SQL syntax.

**Supported Query Types**
- **SELECT**: Basic SELECT queries only (required).
- **Columns**: `*` or a list of expressions (identifiers or numbers); supports simple arithmetic operators (`+ - * / %`) and `AS` aliases.
- **FROM**: Single table identifier required after `FROM`.
- **WHERE**: Single simple condition of the form `identifier operator value`.
  - Supported operators: `=`, `>`, `<`, `>=`, `<=`, `!=`, `<>`, arithmetic operators (`+ - * / %`), and keywords `IN`, `BETWEEN`, `LIKE`, `IS`, `EXISTS`, `ANY`, `ALL` (note: parser accepts these as operators but only consumes one following token as the "value").

**Valid Examples**
- **Simple select star**: `SELECT * FROM users;`
- **Select columns**: `SELECT id, name FROM customers;`
- **Arithmetic in select**: `SELECT price * quantity AS total FROM orders;`
- **Where with number**: `SELECT id FROM items WHERE id = 5;`
- **Where with identifier/string**: `SELECT name FROM people WHERE name = 'Alice';`

BNF / Syntax Rules (simplified)

<query> ::= SELECT <columns> FROM <table> [WHERE <boolean_expr>] [HAVING <boolean_expr>] [';']

<columns> ::= '*' | <select_item> (',' <select_item>)*

<select_item> ::= <expression> [AS <identifier>]

<boolean_expr> ::= [NOT] <boolean_term> ( (AND|OR) [NOT] <boolean_term> )*

<boolean_term> ::= <comparison> | '(' <boolean_expr> ')'

<comparison> ::= <expression> ( '=' | '!=' | '<>' | '>' | '<' | '>=' | '<=' ) <expression>
                 | <expression> IN '(' <expression> (',' <expression> )* ')'
                 | <expression> BETWEEN <expression> AND <expression>
                 | <expression> LIKE <string>
                 | <expression> IS [NOT] NULL

<expression> ::= <term> ( ('+'|'-'|'*'|'/'|'%') <expression> )?

<term> ::= <identifier> | <number> | <string> | '*' | <function_call> | '(' <expression> ')'

<function_call> ::= <identifier> '(' [ <expression> (',' <expression>)* | '*' ] ')'

Notes about functions/operators implemented:
- Aggregate functions: `COUNT()`, `SUM()`, `AVG()`, `MIN()`, `MAX()` — recognized as functions. `COUNT(*)` is allowed; `SUM(*)` is rejected.
- `MOD(a,b)` is supported as a function (standard SQL modulus).
- `IN` requires a non-empty parenthesized list; an empty list is a syntax error.
- `BETWEEN` requires the `AND` keyword between bounds.
- `LIKE` requires a string pattern on the right-hand side.
- `IS` must be followed by `NULL` or `NOT NULL`.
- Aggregate functions are disallowed in `WHERE` and allowed in `HAVING`.

Examples (valid)
- `SELECT COUNT(*) FROM orders;`
- `SELECT id, SUM(amount) AS total FROM sales HAVING SUM(amount) > 1000;`
- `SELECT name FROM employees WHERE dept IN ('IT','HR');`
- `SELECT * FROM products WHERE price BETWEEN 10 AND 50;`
- `SELECT MOD(quantity, 2) FROM inventory;`

Examples (invalid / errors)
- `SELECT * FROM t WHERE id IN ();`  -> "Syntax Error: IN list cannot be empty"
- `SELECT * FROM t WHERE salary BETWEEN 3000 6000;` -> "Syntax Error: BETWEEN requires AND keyword"
- `SELECT * FROM t WHERE name LIKE name;` -> "Syntax Error: LIKE requires a string pattern"
- `SELECT * FROM t WHERE SUM(amount) > 10;` -> "Invalid use of aggregate function in WHERE clause"
- `SELECT SUM(*) FROM t;` -> "Invalid use of '*' with function 'SUM'"


**Invalid / Error Examples (and typical errors produced)**
- **Missing FROM**: `SELECT id, name users;` → Syntax Error: expected `FROM`.
- **Missing comma between columns**: `SELECT id name FROM users;` → Error: Missing COMMA between column names.
- **Missing table name**: `SELECT * FROM ;` → Syntax Error: Expected identifier (table name).
- **Missing value after operator**: `SELECT * FROM t WHERE id =` → Runtime error: Missing value after operator (the parser now detects EOF/`;` after operator).
- **Unterminated string**: `SELECT * FROM t WHERE name = 'Alice` → Lexer error: Unterminated string literal.
- **Unsupported complex WHERE**: `SELECT * FROM t WHERE id IN (1,2,3);` → Parser limitation: `IN` is recognized but parser only accepts a single following token as the value, so lists/parentheses are not parsed and will lead to an error or incorrect behavior.

**Limitations & Notes**
- Only handles the single-query form: `SELECT columns FROM table [WHERE condition]`.
- Does not parse `JOIN`, `ORDER BY`, `GROUP BY`, `HAVING`, multi-table `FROM` lists, or full DML (`INSERT`, `UPDATE`, `DELETE`) even if the lexer tokenizes their keywords.
- `BETWEEN`, `IN`, `EXISTS`, `ANY`, `ALL` are accepted as operators but the parser expects only one token as the value — these are not fully implemented.
- The lexer reports lexical errors (e.g., unterminated strings) before parsing; those produce clearer messages (position + quote info).

**Where to look in code**
- Parser entry: `src/com/sqlorb/Parser.java`
- Lexer (tokenization rules & string handling): `src/com/sqlorb/Lexer.java`
- HTTP server (if using the HTTP API): `src/com/sqlorb/Server.java`




javac -d bin src/com/sqlorb/*.java

java -cp bin com.sqlorb.Main

java -cp bin com.sqlorb.Server
package com.sqlorb;

import java.util.List;

public class Parser
 {
    private final List<Token> tokens;
    private int current = 0;

    public Parser(List<Token> tokens) 
    {
        this.tokens = tokens;
    }

    // ---------------------------------------------------------
    // RULE 1: query -> SELECT columns FROM table [WHERE condition]
    // ---------------------------------------------------------
    public void parseQuery()
     {
        if (tokens.isEmpty()) return;

        match(TokenType.SELECT);
        parseColumns();
        match(TokenType.FROM);
        match(TokenType.IDENTIFIER); // Table name

        // WHERE clause is optional. We check if the next token is WHERE.
        if (peek().type == TokenType.WHERE) {
            match(TokenType.WHERE);
            parseCondition();
        }

        // Optional GROUP BY ... HAVING
        if (peek().type == TokenType.GROUP_BY) {
            parseGroupBy();
            if (peek().type == TokenType.HAVING) {
                match(TokenType.HAVING);
                // HAVING allows aggregates
                parseBooleanExpression();
            }
        }

        // Optional ORDER BY
        if (peek().type == TokenType.ORDER_BY) {
            parseOrderBy();
        }

        // Optional: Skip semicolon if present
        if (peek().type == TokenType.SEMICOLON) {
            match(TokenType.SEMICOLON);
        }

        // We should be at the end of the query now — if you add extra text, it's an error.
        if (peek().type != TokenType.EOF) {
            throw new RuntimeException("Error at position " + peek().position + ": Unexpected text '" + peek().value + "' after the query ended.");
        }
    }

    // Parse GROUP BY column[, column...]
    private void parseGroupBy() {
        match(TokenType.GROUP_BY);
        // optional explicit BY token if lexer returned it as IDENTIFIER
        if (peek().type == TokenType.IDENTIFIER && peek().value.equalsIgnoreCase("BY")) {
            advance();
        }
        // must have at least one identifier
        if (peek().type != TokenType.IDENTIFIER) {
            throw new RuntimeException("Syntax Error: Expected column name after GROUP BY");
        }
        // one or more identifiers
        match(TokenType.IDENTIFIER);
        while (peek().type == TokenType.COMMA) {
            match(TokenType.COMMA);
            match(TokenType.IDENTIFIER);
        }
    }

    // Parse simple ORDER BY identifier [ASC|DESC]
    private void parseOrderBy() {
        match(TokenType.ORDER_BY);
        if (peek().type == TokenType.IDENTIFIER && peek().value.equalsIgnoreCase("BY")) {
            advance();
        }
        // parse one column (simple support)
        if (peek().type != TokenType.IDENTIFIER) {
            throw new RuntimeException("Syntax Error: Expected column name after ORDER BY");
        }
        match(TokenType.IDENTIFIER);
        if (peek().type == TokenType.ASC || peek().type == TokenType.DESC) {
            advance();
        }
    }

    
    // columns ->[col1, col2]
    private void parseColumns() 
    {
    	
        // Option A: SELECT *
        if (peek().type == TokenType.STAR) 
        {
            match(TokenType.STAR);
            return;
        }
     // Option B: SELECT expr AS alias, ...
        parseSelectItem();

        // While we see a COMMA, keep reading more columns
        while (peek().type == TokenType.COMMA) 
        {
            match(TokenType.COMMA);
            parseSelectItem();
        }
        
        // COMMON ERROR CHECK:
        // If we see another Identifier immediately (e.g., "name age"), 
        // the user forgot a comma.
        if (peek().type == TokenType.IDENTIFIER && peek().value.toUpperCase().equals("FROM") == false) 
        {
             throw new RuntimeException("Error at position " + peek().position + 
                 ": Missing COMMA between column names '" + previous().value + "' and '" + peek().value + "'.");
        }
    }

    // Parse a select item which can be: identifier | identifier op identifier | identifier AS alias
    private void parseSelectItem() 
    {
        // Expect an expression (identifiers, numbers, function calls, or arithmetic)
        boolean hasAgg = parseExpression();

        // Optional AS alias
        if (peek().type == TokenType.AS) {
            advance();
            if (peek().type == TokenType.IDENTIFIER) {
                advance();
            } else {
                throw new RuntimeException("Error at position " + peek().position + ": Expected alias after AS.");
            }
        }

        // Note: we return/ignore aggregate presence here; aggregates are handled in HAVING not in SELECT-level checks
    }

    // --------------------------------------------------------------------------------------------------------------------------------
    // condition -> boolean-expression
    // Supports: comparisons, IN lists, BETWEEN a AND b, LIKE, IS [NOT] NULL, combined with AND/OR/NOT
    private void parseCondition()
    {
        boolean hasAggregate = parseBooleanExpression();

        // Aggregate functions are not allowed in WHERE (must use HAVING instead)
        if (hasAggregate) {
            throw new RuntimeException("Invalid use of aggregate function in WHERE clause");
        }
    }

    // Parse boolean expressions with AND / OR / NOT and parentheses.
    // Returns true if any subexpression contains an aggregate function.
    private boolean parseBooleanExpression() {
        boolean hasAgg = false;

        // Handle optional NOT
        if (peek().type == TokenType.NOT) {
            advance();
            hasAgg |= parseBooleanExpression();
            return hasAgg;
        }

        // Parenthesized expression
        if (peek().type == TokenType.LPAREN) {
            advance();
            hasAgg |= parseBooleanExpression();
            match(TokenType.RPAREN);
        } else {
            hasAgg |= parseComparison();
        }

        // Combine with AND/OR
        while (peek().type == TokenType.AND || peek().type == TokenType.OR) {
            advance();
            if (peek().type == TokenType.NOT) { advance(); }
            if (peek().type == TokenType.LPAREN) {
                advance();
                hasAgg |= parseBooleanExpression();
                match(TokenType.RPAREN);
            } else {
                hasAgg |= parseComparison();
            }
        }

        return hasAgg;
    }

    // Parse a comparison or predicate. Returns true if it contains an aggregate.
    private boolean parseComparison() {
        // Left expression
        boolean leftHasAgg = parseExpression();

        TokenType op = peek().type;

        // Handle IS [NOT] NULL
        if (op == TokenType.IS) {
            advance();
            boolean not = false;
            if (peek().type == TokenType.NOT) { not = true; advance(); }
            if (peek().type != TokenType.NULL) {
                throw new RuntimeException("Syntax Error: Expected NULL after IS" + (not ? " NOT" : ""));
            }
            advance();
            return leftHasAgg;
        }

        // IN (list)
        if (op == TokenType.IN) {
            advance();
            parseInList();
            return leftHasAgg;
        }

        // BETWEEN a AND b
        if (op == TokenType.BETWEEN) {
            advance();
            boolean lowAgg = parseExpression();
            if (peek().type != TokenType.AND) {
                throw new RuntimeException("Syntax Error: BETWEEN requires AND keyword");
            }
            advance();
            boolean highAgg = parseExpression();
            return leftHasAgg | lowAgg | highAgg;
        }

        // LIKE pattern
        if (op == TokenType.LIKE) {
            advance();
            if (peek().type != TokenType.STRING) {
                throw new RuntimeException("Syntax Error: LIKE requires a string pattern");
            }
            advance();
            return leftHasAgg;
        }

        // Comparison operators (=, !=, <, >, >=, <=)
        if (op == TokenType.EQUALS || op == TokenType.NOT_EQUALS || op == TokenType.NOT_EQUALS_SQL || op == TokenType.GT || op == TokenType.LT || op == TokenType.GE || op == TokenType.LE) {
            advance();
            boolean rightHasAgg = parseExpression();
            return leftHasAgg | rightHasAgg;
        }

        // If we reach here, it's an unexpected token for a comparison — allow simple existence checks only
        return leftHasAgg;
    }

    // Parse an IN list: expects '(' value (',' value)* ')'
    private void parseInList() {
        if (peek().type != TokenType.LPAREN) {
            throw new RuntimeException("Syntax Error: Expected '(' after IN");
        }
        advance();
        if (peek().type == TokenType.RPAREN) {
            throw new RuntimeException("Syntax Error: IN list cannot be empty");
        }
        // At least one value required
        parseExpression();
        while (peek().type == TokenType.COMMA) {
            advance();
            parseExpression();
        }
        match(TokenType.RPAREN);
    }

    // Parse an expression (identifiers, numbers, strings, function calls, parenthesis)
    // Returns true if the expression contains an aggregate function.
    private boolean parseExpression() {
        // Primary
        boolean hasAggregate = false;

        if (peek().type == TokenType.LPAREN) {
            advance();
            hasAggregate |= parseExpression();
            match(TokenType.RPAREN);
        } else if (peek().type == TokenType.NUMBER || peek().type == TokenType.STRING || peek().type == TokenType.IDENTIFIER || peek().type == TokenType.STAR || isFunctionName(peek())) {
            // Function call or identifier/number
            if (isFunctionName(peek()) || (peek().type == TokenType.IDENTIFIER && current + 1 < tokens.size() && tokens.get(current + 1).type == TokenType.LPAREN)) {
                hasAggregate |= parseFunctionCall();
            } else {
                // simple literal/identifier/star
                advance();
            }
        } else {
            throw new RuntimeException("Error at position " + peek().position + ": Unexpected token '" + peek().value + "' in expression.");
        }

        // Optional arithmetic operator and right-hand side
        if (peek().type == TokenType.PLUS || peek().type == TokenType.MINUS || peek().type == TokenType.STAR || peek().type == TokenType.SLASH || peek().type == TokenType.PERCENT) {
            advance();
            hasAggregate |= parseExpression();
        }

        return hasAggregate;
    }

    // Determine if token is a known function name (aggregate or MOD)
    private boolean isFunctionName(Token t) {
        return t.type == TokenType.COUNT || t.type == TokenType.SUM || t.type == TokenType.AVG || t.type == TokenType.MIN || t.type == TokenType.MAX || t.value.equalsIgnoreCase("MOD");
    }

    // Parse a function call like SUM(expr) or MOD(expr, expr)
    // Returns true if this is an aggregate function (COUNT, SUM, AVG, MIN, MAX)
    private boolean parseFunctionCall() {
        Token funcToken = peek();
        String funcName = funcToken.value.toUpperCase();
        boolean isAggregate = funcName.equals("COUNT") || funcName.equals("SUM") || funcName.equals("AVG") || funcName.equals("MIN") || funcName.equals("MAX");

        // consume function name
        advance();

        // Expect parentheses
        if (peek().type != TokenType.LPAREN) {
            throw new RuntimeException("Syntax Error: Aggregate/function '" + funcName + "' used without parentheses");
        }
        advance();

        // Special-case COUNT(*)
        if (peek().type == TokenType.STAR) {
            if (!funcName.equals("COUNT")) {
                throw new RuntimeException("Invalid use of '*' with function '" + funcName + "'");
            }
            advance();
            match(TokenType.RPAREN);
            return isAggregate;
        }

        // No arguments allowed for aggregates without parentheses — parse at least one expression
        if (peek().type == TokenType.RPAREN) {
            throw new RuntimeException("Syntax Error: Function '" + funcName + "' requires arguments");
        }

        // Parse first argument
        boolean argAgg = parseExpression();

        // Additional comma-separated args (for MOD, etc.)
        while (peek().type == TokenType.COMMA) {
            advance();
            argAgg |= parseExpression();
        }

        match(TokenType.RPAREN);

        return isAggregate || argAgg;
    }

    // ---------------------------------------------------------
    // HELPER METHODS
    // ---------------------------------------------------------
    
    // Checks if current token is what we expect. If yes, consume it. If no, ERROR.
    private void match(TokenType expected) 
    {
        if (peek().type == expected) 
        {
            advance();
        } 
        else 
        {
            generateDetailedError(expected);
        }
    }

    private void generateDetailedError(TokenType expected) 
    {
        Token currentToken = peek();
        String msg = "Syntax Error at position " + currentToken.position + ": ";
        
        if (expected == TokenType.FROM && currentToken.type == TokenType.WHERE)
         {
            msg += "Found 'WHERE' before 'FROM'. The FROM clause must come before WHERE.";
        } 
        else if (expected == TokenType.IDENTIFIER) 
        {
            msg += "Expected a Column or Table name, but found '" + currentToken.value + "'.";
        }
        else
         {
            msg += "Expected " + expected + " but found '" + currentToken.value + "'.";
        }
        
        throw new RuntimeException(msg);
    }

    private Token peek() 
    {
        return tokens.get(current);
    }
    
    private Token previous() 
    {
        return tokens.get(current - 1);
    }

    private void advance() 
    {
        if (current < tokens.size()) 
            current++;
    }
}
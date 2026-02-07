package com.sqlorb;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class Parser {
    private final List<Token> tokens;
    private int current = 0;

    // Data captured during parsing for GROUP BY / HAVING validation
    private List<SelectItemInfo> selectItems = new ArrayList<>();
    private List<GroupByItem> groupByItems = new ArrayList<>();
    private boolean hasGroupBy = false;

    public Parser(List<Token> tokens) {
        this.tokens = tokens;
    }

    // ---------------------------------------------------------
    // RULE 1: query -> SELECT columns FROM table [WHERE condition] [GROUP BY] [HAVING] [ORDER BY]
    // ---------------------------------------------------------
    public void parseQuery() {
        if (tokens.isEmpty()) return;

        selectItems.clear();
        groupByItems.clear();
        hasGroupBy = false;

        match(TokenType.SELECT);
        if (peek().type == TokenType.DISTINCT) advance(); // Optional DISTINCT
        parseColumns();
        match(TokenType.FROM);
        match(TokenType.IDENTIFIER); // Table name

        // WHERE clause is optional
        if (peek().type == TokenType.WHERE) {
            match(TokenType.WHERE);
            parseCondition();
        }

        // Optional GROUP BY ... HAVING
        if (peek().type == TokenType.GROUP_BY) {
            hasGroupBy = true;
            parseGroupBy();
            validateGroupByRule1(); // Rule 1: All non-aggregated SELECT columns must be in GROUP BY

            if (peek().type == TokenType.HAVING) {
                match(TokenType.HAVING);
                HavingInfo havingInfo = parseHavingClause();
                validateHavingRule3(havingInfo); // Rule 3: Non-GROUP BY columns in HAVING must be aggregated
            }
        }

        // Optional ORDER BY
        if (peek().type == TokenType.ORDER_BY) {
            parseOrderBy();
        }

        // Optional LIMIT
        if (peek().type == TokenType.LIMIT) {
            match(TokenType.LIMIT);
            if (peek().type != TokenType.NUMBER) {
                throw new RuntimeException("Syntax Error: LIMIT requires a numeric value");
            }
            advance();
        }

        // Optional semicolon
        if (peek().type == TokenType.SEMICOLON) {
            match(TokenType.SEMICOLON);
        }

        if (peek().type != TokenType.EOF) {
            throw new RuntimeException("Error at position " + peek().position + ": Unexpected text '" + peek().value + "' after the query ended.");
        }
    }

    // -------------------------------------------------------------------------
    // GROUP BY: Supports columns, expressions (e.g. YEAR(order_date)), ordinals (1, 2)
    // -------------------------------------------------------------------------
    private void parseGroupBy() {
        match(TokenType.GROUP_BY);
        if (peek().type == TokenType.IDENTIFIER && peek().value.equalsIgnoreCase("BY")) {
            advance();
        }
        // Must have at least one item (column, expression, ordinal, or function like LEFT/RIGHT)
        if (peek().type != TokenType.IDENTIFIER && peek().type != TokenType.NUMBER && peek().type != TokenType.LPAREN
                && peek().type != TokenType.LEFT_JOIN && peek().type != TokenType.RIGHT_JOIN && peek().type != TokenType.CASE) {
            throw new RuntimeException("Syntax Error: Expected column, expression, or ordinal after GROUP BY");
        }
        parseGroupByItem();
        while (peek().type == TokenType.COMMA) {
            match(TokenType.COMMA);
            parseGroupByItem();
        }
        // Optional WITH ROLLUP
        if (peek().type == TokenType.WITH) {
            advance();
            if (peek().type != TokenType.ROLLUP && (peek().type != TokenType.IDENTIFIER || !peek().value.equalsIgnoreCase("ROLLUP"))) {
                throw new RuntimeException("Syntax Error: WITH must be followed by ROLLUP");
            }
            advance();
        }
    }

    private void parseGroupByItem() {
        // Rule 7: GROUP BY ordinal (1, 2, 3...)
        if (peek().type == TokenType.NUMBER) {
            int ord = Integer.parseInt(peek().value);
            if (ord < 1) {
                throw new RuntimeException("Syntax Error: GROUP BY ordinal must be >= 1");
            }
            advance();
            groupByItems.add(new GroupByItem(ord, null));
            return;
        }
        // Rule 3: Expression (column, function, arithmetic)
        int start = current;
        parseExpression();
        int end = current;
        groupByItems.add(new GroupByItem(-1, buildSignature(start, end)));
    }

    // Rule 1: All selected non-aggregated columns must appear in GROUP BY
    private void validateGroupByRule1() {
        if (selectItems.isEmpty()) return; // SELECT * case
        for (int i = 0; i < selectItems.size(); i++) {
            SelectItemInfo item = selectItems.get(i);
            if (item.isAggregate) continue;
            boolean covered = false;
            for (GroupByItem gb : groupByItems) {
                if (gb.ordinal >= 1) {
                    if (gb.ordinal == i + 1) covered = true;
                } else if (normalizeSignature(item.signature).equals(normalizeSignature(gb.expression))) {
                    covered = true;
                } else if (item.alias != null && normalizeSignature(gb.expression).equals(item.alias.toLowerCase())) {
                    covered = true; // GROUP BY alias (e.g. GROUP BY cat when SELECT x AS cat)
                }
            }
            if (!covered) {
                throw new RuntimeException("GROUP BY rule violation: Column '" + item.signature + "' must appear in GROUP BY or be used in an aggregate function.");
            }
        }
    }

    // Rule 3 (HAVING): Columns not in GROUP BY cannot appear unaggregated in HAVING
    private void validateHavingRule3(HavingInfo info) {
        if (!hasGroupBy || info.columnRefs.isEmpty()) return;
        for (String col : info.columnRefs) {
            if (info.aggregateColumnRefs.contains(col)) continue;
            boolean inGroupBy = false;
            for (GroupByItem gb : groupByItems) {
                if (gb.ordinal >= 1) {
                    if (gb.ordinal <= selectItems.size()) {
                        SelectItemInfo si = selectItems.get(gb.ordinal - 1);
                        if (si.columnRefs.contains(col.toLowerCase())) inGroupBy = true;
                    }
                } else if (gb.expression != null && gb.expression.toLowerCase().contains(col.toLowerCase())) {
                    inGroupBy = true;
                }
            }
            if (!inGroupBy) {
                throw new RuntimeException("HAVING rule violation: Column '" + col + "' must appear in GROUP BY or be used in an aggregate function.");
            }
        }
    }

    private String normalizeSignature(String s) {
        if (s == null) return "";
        return s.replaceAll("\\s+", "").toLowerCase();
    }

    private String buildSignature(int start, int end) {
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < end && i < tokens.size(); i++) {
            if (i > start) sb.append(" ");
            sb.append(tokens.get(i).value);
        }
        return sb.toString();
    }

    // Parse ORDER BY: col [ASC|DESC] [NULLS FIRST|LAST] | ordinal | expression [, ...]
    private void parseOrderBy() {
        match(TokenType.ORDER_BY);
        if (peek().type == TokenType.IDENTIFIER && peek().value.equalsIgnoreCase("BY")) {
            advance();
        }
        parseOrderByItem();
        while (peek().type == TokenType.COMMA) {
            match(TokenType.COMMA);
            parseOrderByItem();
        }
    }

    private void parseOrderByItem() {
        // ORDER BY ordinal (1, 2, 3...)
        if (peek().type == TokenType.NUMBER) {
            int ord = Integer.parseInt(peek().value);
            if (ord < 1) throw new RuntimeException("Syntax Error: ORDER BY ordinal must be >= 1");
            advance();
        } else {
            // ORDER BY expression (column, function, arithmetic, CASE, etc.)
            parseExpression();
        }
        // Optional ASC | DESC
        if (peek().type == TokenType.ASC || peek().type == TokenType.DESC) advance();
        // Optional NULLS FIRST | NULLS LAST
        if (peek().type == TokenType.NULLS) {
            advance();
            if (peek().type == TokenType.FIRST || peek().type == TokenType.LAST) advance();
            else throw new RuntimeException("Syntax Error: NULLS must be followed by FIRST or LAST");
        }
        // Optional COLLATE "name"
        if (peek().type == TokenType.COLLATE) {
            advance();
            if (peek().type != TokenType.STRING && peek().type != TokenType.IDENTIFIER) {
                throw new RuntimeException("Syntax Error: COLLATE requires a collation name");
            }
            advance();
        }
    }

    // -------------------------------------------------------------------------
    // SELECT columns
    // -------------------------------------------------------------------------
    private void parseColumns() {
        if (peek().type == TokenType.STAR) {
            match(TokenType.STAR);
            return;
        }
        int position = 1;
        parseSelectItem(position++);
        while (peek().type == TokenType.COMMA) {
            match(TokenType.COMMA);
            parseSelectItem(position++);
        }
        if (peek().type == TokenType.IDENTIFIER && !peek().value.equalsIgnoreCase("FROM")) {
            throw new RuntimeException("Error at position " + peek().position +
                    ": Missing COMMA between column names '" + previous().value + "' and '" + peek().value + "'.");
        }
    }

    private void parseSelectItem(int position) {
        int start = current;
        boolean hasAgg = parseExpression();
        int end = current;
        String signature = buildSignature(start, end);
        Set<String> columnRefs = collectColumnRefsFromTokens(start, end);

        String alias = null;
        if (peek().type == TokenType.AS) {
            advance();
            if (peek().type == TokenType.IDENTIFIER) {
                alias = peek().value;
                advance();
            } else {
                throw new RuntimeException("Error at position " + peek().position + ": Expected alias after AS.");
            }
        }

        selectItems.add(new SelectItemInfo(signature, hasAgg, alias, position, columnRefs));
    }

    private Set<String> collectColumnRefsFromTokens(int start, int end) {
        Set<String> refs = new HashSet<>();
        int depth = 0;
        int aggregateStartDepth = -1;
        for (int i = start; i < end && i < tokens.size(); i++) {
            Token t = tokens.get(i);
            if (t.type == TokenType.LPAREN) {
                if (i > start && tokens.get(i - 1).type == TokenType.IDENTIFIER && isAggregateName(tokens.get(i - 1).value)) {
                    aggregateStartDepth = depth;
                }
                depth++;
            } else if (t.type == TokenType.RPAREN) {
                depth--;
                if (depth == aggregateStartDepth) aggregateStartDepth = -1;
            } else if (t.type == TokenType.IDENTIFIER && !isKeyword(t.value)) {
                if (aggregateStartDepth < 0 || depth <= aggregateStartDepth) {
                    refs.add(t.value.toLowerCase());
                }
            }
        }
        return refs;
    }

    private boolean isAggregateName(String s) {
        String u = s.toUpperCase();
        return u.equals("COUNT") || u.equals("SUM") || u.equals("AVG") || u.equals("MIN") || u.equals("MAX");
    }

    private boolean isKeyword(String s) {
        String u = s.toUpperCase();
        return u.equals("AS") || u.equals("AND") || u.equals("OR") || u.equals("NOT") || u.equals("IN") ||
                u.equals("BETWEEN") || u.equals("LIKE") || u.equals("IS") || u.equals("NULL") || u.equals("BY");
    }

    // -------------------------------------------------------------------------
    // HAVING: Parse and collect column refs (non-aggregated)
    // -------------------------------------------------------------------------
    private HavingInfo parseHavingClause() {
        HavingInfo info = new HavingInfo();
        parseBooleanExpressionForHaving(info);
        return info;
    }

    private void parseBooleanExpressionForHaving(HavingInfo info) {
        if (peek().type == TokenType.NOT) {
            advance();
            parseBooleanExpressionForHaving(info);
            return;
        }
        if (peek().type == TokenType.LPAREN) {
            advance();
            parseBooleanExpressionForHaving(info);
            match(TokenType.RPAREN);
        } else {
            parseComparisonForHaving(info);
        }
        while (peek().type == TokenType.AND || peek().type == TokenType.OR) {
            advance();
            if (peek().type == TokenType.NOT) advance();
            if (peek().type == TokenType.LPAREN) {
                advance();
                parseBooleanExpressionForHaving(info);
                match(TokenType.RPAREN);
            } else {
                parseComparisonForHaving(info);
            }
        }
    }

    private void parseComparisonForHaving(HavingInfo info) {
        ExprInfo left = parseExpressionWithInfo();
        TokenType op = peek().type;

        if (op == TokenType.IS) {
            advance();
            if (peek().type == TokenType.NOT) advance();
            match(TokenType.NULL);
            if (!left.hasAggregate) left.columnRefs.forEach(info.columnRefs::add);
            return;
        }
        if (op == TokenType.IN) {
            advance();
            parseInList();
            if (!left.hasAggregate) left.columnRefs.forEach(info.columnRefs::add);
            return;
        }
        if (op == TokenType.BETWEEN) {
            advance();
            ExprInfo low = parseExpressionWithInfo();
            if (peek().type != TokenType.AND) throw new RuntimeException("Syntax Error: BETWEEN requires AND keyword");
            advance();
            ExprInfo high = parseExpressionWithInfo();
            if (!left.hasAggregate) left.columnRefs.forEach(info.columnRefs::add);
            if (!low.hasAggregate) low.columnRefs.forEach(info.columnRefs::add);
            if (!high.hasAggregate) high.columnRefs.forEach(info.columnRefs::add);
            return;
        }
        if (op == TokenType.LIKE) {
            advance();
            if (peek().type != TokenType.STRING) throw new RuntimeException("Syntax Error: LIKE requires a string pattern");
            advance();
            if (!left.hasAggregate) left.columnRefs.forEach(info.columnRefs::add);
            return;
        }
        if (op == TokenType.EQUALS || op == TokenType.NOT_EQUALS || op == TokenType.NOT_EQUALS_SQL ||
                op == TokenType.GT || op == TokenType.LT || op == TokenType.GE || op == TokenType.LE) {
            advance();
            ExprInfo right = parseExpressionWithInfo();
            if (!left.hasAggregate) left.columnRefs.forEach(info.columnRefs::add);
            if (!right.hasAggregate) right.columnRefs.forEach(info.columnRefs::add);
            if (left.hasAggregate) left.columnRefs.forEach(info.aggregateColumnRefs::add);
            if (right.hasAggregate) right.columnRefs.forEach(info.aggregateColumnRefs::add);
            return;
        }
        if (!left.hasAggregate) left.columnRefs.forEach(info.columnRefs::add);
    }

    // Parse expression and return ExprInfo (hasAggregate, columnRefs)
    private ExprInfo parseExpressionWithInfo() {
        ExprInfo info = new ExprInfo();
        parseExpressionWithInfoRec(info);
        return info;
    }

    private void parseExpressionWithInfoRec(ExprInfo info) {
        if (peek().type == TokenType.LPAREN) {
            advance();
            parseExpressionWithInfoRec(info);
            match(TokenType.RPAREN);
        } else if (peek().type == TokenType.CASE) {
            parseCaseExpressionWithInfo(info);
        } else if (peek().type == TokenType.NUMBER || peek().type == TokenType.STRING || peek().type == TokenType.STAR ||
                peek().type == TokenType.IDENTIFIER || peek().type == TokenType.LEFT_JOIN || peek().type == TokenType.RIGHT_JOIN || isFunctionName(peek())) {
            if (isFunctionName(peek()) || isFunctionCall(peek())) {
                parseFunctionCallWithInfo(info);
            } else {
                if (peek().type == TokenType.IDENTIFIER && !isKeyword(peek().value)) {
                    info.columnRefs.add(peek().value.toLowerCase());
                }
                advance();
            }
        } else {
            throw new RuntimeException("Error at position " + peek().position + ": Unexpected token '" + peek().value + "' in expression.");
        }
        if (peek().type == TokenType.PLUS || peek().type == TokenType.MINUS || peek().type == TokenType.STAR || peek().type == TokenType.SLASH || peek().type == TokenType.PERCENT) {
            advance();
            parseExpressionWithInfoRec(info);
        }
        if (peek().type == TokenType.EQUALS || peek().type == TokenType.NOT_EQUALS || peek().type == TokenType.NOT_EQUALS_SQL ||
                peek().type == TokenType.GT || peek().type == TokenType.LT || peek().type == TokenType.GE || peek().type == TokenType.LE) {
            advance();
            parseExpressionWithInfoRec(info);
        }
    }

    /**
     * Parse a function call. If `info` is non-null, collects column reference info
     * for HAVING/aggregate analysis; otherwise parses without collecting info.
     * Returns true if the function call or its arguments contain an aggregate.
     */
    private boolean parseFunctionCallCommon(ExprInfo info) {
        Token funcToken = peek();
        String funcName = funcToken.value.toUpperCase();
        boolean isAggregate = funcName.equals("COUNT") || funcName.equals("SUM") || funcName.equals("AVG") || funcName.equals("MIN") || funcName.equals("MAX");
        advance();
        if (peek().type != TokenType.LPAREN) {
            throw new RuntimeException("Syntax Error: Function '" + funcName + "' used without parentheses");
        }
        advance();
        if (peek().type == TokenType.STAR) {
            if (!funcName.equals("COUNT")) throw new RuntimeException("Invalid use of '*' with function '" + funcName + "'");
            advance();
            match(TokenType.RPAREN);
            return isAggregate;
        }
        if (peek().type == TokenType.DISTINCT) {
            if (!isAggregate) throw new RuntimeException("DISTINCT can only be used with aggregate functions");
            advance();
        }
        if (peek().type == TokenType.RPAREN) {
            if (funcName.equals("RAND") || funcName.equals("RANDOM")) {
                advance();
                return false;
            }
            throw new RuntimeException("Syntax Error: Function '" + funcName + "' requires arguments");
        }
        boolean argHasAgg = false;
        if (info != null) {
            parseExpressionWithInfoRec(info);
            while (peek().type == TokenType.COMMA) {
                advance();
                parseExpressionWithInfoRec(info);
            }
            argHasAgg = !info.aggregateColumnRefs.isEmpty() || !info.columnRefs.isEmpty();
        } else {
            argHasAgg = parseExpression();
            while (peek().type == TokenType.COMMA) {
                advance();
                argHasAgg |= parseExpression();
            }
        }
        match(TokenType.RPAREN);
        if (isAggregate && info != null) {
            info.columnRefs.forEach(info.aggregateColumnRefs::add);
            info.columnRefs.clear();
        }
        return isAggregate || argHasAgg;
    }

    // -------------------------------------------------------------------------
    // WHERE condition (no aggregates allowed)
    // -------------------------------------------------------------------------
    private void parseCondition() {
        boolean hasAggregate = parseBooleanExpression();
        if (hasAggregate) {
            throw new RuntimeException("Invalid use of aggregate function in WHERE clause");
        }
    }

    private boolean parseBooleanExpression() {
        boolean hasAgg = false;
        if (peek().type == TokenType.NOT) {
            advance();
            hasAgg |= parseBooleanExpression();
            return hasAgg;
        }
        if (peek().type == TokenType.LPAREN) {
            advance();
            hasAgg |= parseBooleanExpression();
            match(TokenType.RPAREN);
        } else {
            hasAgg |= parseComparison();
        }
        while (peek().type == TokenType.AND || peek().type == TokenType.OR) {
            advance();
            if (peek().type == TokenType.NOT) advance();
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

    private boolean parseComparison() {
        boolean leftHasAgg = parseExpression();
        TokenType op = peek().type;

        if (op == TokenType.IS) {
            advance();
            if (peek().type == TokenType.NOT) advance();
            if (peek().type != TokenType.NULL) {
                throw new RuntimeException("Syntax Error: Expected NULL after IS" + (peek().type == TokenType.NOT ? " NOT" : ""));
            }
            advance();
            return leftHasAgg;
        }
        if (op == TokenType.IN) {
            advance();
            parseInList();
            return leftHasAgg;
        }
        if (op == TokenType.BETWEEN) {
            advance();
            boolean lowAgg = parseExpression();
            if (peek().type != TokenType.AND) throw new RuntimeException("Syntax Error: BETWEEN requires AND keyword");
            advance();
            boolean highAgg = parseExpression();
            return leftHasAgg | lowAgg | highAgg;
        }
        if (op == TokenType.LIKE) {
            advance();
            if (peek().type != TokenType.STRING) throw new RuntimeException("Syntax Error: LIKE requires a string pattern");
            advance();
            return leftHasAgg;
        }
        if (op == TokenType.EQUALS || op == TokenType.NOT_EQUALS || op == TokenType.NOT_EQUALS_SQL ||
                op == TokenType.GT || op == TokenType.LT || op == TokenType.GE || op == TokenType.LE) {
            advance();
            boolean rightHasAgg = parseExpression();
            return leftHasAgg | rightHasAgg;
        }
        return leftHasAgg;
    }

    private void parseInList() {
        if (peek().type != TokenType.LPAREN) throw new RuntimeException("Syntax Error: Expected '(' after IN");
        advance();
        if (peek().type == TokenType.RPAREN) throw new RuntimeException("Syntax Error: IN list cannot be empty");
        parseExpression();
        while (peek().type == TokenType.COMMA) {
            advance();
            parseExpression();
        }
        match(TokenType.RPAREN);
    }

    // -------------------------------------------------------------------------
    // Core parseExpression (returns hasAggregate only)
    // -------------------------------------------------------------------------
    private boolean parseExpression() {
        boolean hasAggregate = false;
        if (peek().type == TokenType.LPAREN) {
            advance();
            hasAggregate |= parseExpression();
            match(TokenType.RPAREN);
        } else if (peek().type == TokenType.CASE) {
            hasAggregate |= parseCaseExpression();
        } else if (peek().type == TokenType.NUMBER || peek().type == TokenType.STRING || peek().type == TokenType.IDENTIFIER || peek().type == TokenType.STAR || peek().type == TokenType.LEFT_JOIN || peek().type == TokenType.RIGHT_JOIN || isFunctionName(peek())) {
            if (isFunctionName(peek()) || isFunctionCall(peek())) {
                hasAggregate |= parseFunctionCall();
            } else {
                advance();
            }
        } else {
            throw new RuntimeException("Error at position " + peek().position + ": Unexpected token '" + peek().value + "' in expression.");
        }
        if (peek().type == TokenType.PLUS || peek().type == TokenType.MINUS || peek().type == TokenType.STAR || peek().type == TokenType.SLASH || peek().type == TokenType.PERCENT) {
            advance();
            hasAggregate |= parseExpression();
        }
        // Support comparison in expressions e.g. (price > 100) for GROUP BY
        if (peek().type == TokenType.EQUALS || peek().type == TokenType.NOT_EQUALS || peek().type == TokenType.NOT_EQUALS_SQL ||
                peek().type == TokenType.GT || peek().type == TokenType.LT || peek().type == TokenType.GE || peek().type == TokenType.LE) {
            advance();
            hasAggregate |= parseExpression();
        }
        return hasAggregate;
    }

    private boolean parseCaseExpression() {
        match(TokenType.CASE);
        match(TokenType.WHEN);
        parseExpression();
        match(TokenType.THEN);
        parseExpression();
        while (peek().type == TokenType.WHEN) {
            advance();
            parseExpression();
            match(TokenType.THEN);
            parseExpression();
        }
        if (peek().type == TokenType.ELSE) {
            advance();
            parseExpression();
        }
        match(TokenType.END);
        return false;
    }

    private void parseCaseExpressionWithInfo(ExprInfo info) {
        match(TokenType.CASE);
        match(TokenType.WHEN);
        parseExpressionWithInfoRec(info);
        match(TokenType.THEN);
        parseExpressionWithInfoRec(info);
        while (peek().type == TokenType.WHEN) {
            advance();
            parseExpressionWithInfoRec(info);
            match(TokenType.THEN);
            parseExpressionWithInfoRec(info);
        }
        if (peek().type == TokenType.ELSE) {
            advance();
            parseExpressionWithInfoRec(info);
        }
        match(TokenType.END);
    }

    private boolean isFunctionName(Token t) {
        return t.type == TokenType.COUNT || t.type == TokenType.SUM || t.type == TokenType.AVG || t.type == TokenType.MIN || t.type == TokenType.MAX || t.value.equalsIgnoreCase("MOD");
    }

    // Identifies tokens that start a function call (identifier+LPAREN, or LEFT/RIGHT when used as string functions)
    private boolean isFunctionCall(Token t) {
        if (current + 1 >= tokens.size()) return false;
        boolean hasLparen = tokens.get(current + 1).type == TokenType.LPAREN;
        return (t.type == TokenType.IDENTIFIER && hasLparen) ||
                ((t.type == TokenType.LEFT_JOIN || t.type == TokenType.RIGHT_JOIN) && hasLparen);
    }

    private boolean parseFunctionCall() {
        Token funcToken = peek();
        String funcName = funcToken.value.toUpperCase();
        boolean isAggregate = funcName.equals("COUNT") || funcName.equals("SUM") || funcName.equals("AVG") || funcName.equals("MIN") || funcName.equals("MAX");
        advance();
        if (peek().type != TokenType.LPAREN) {
            throw new RuntimeException("Syntax Error: Aggregate/function '" + funcName + "' used without parentheses");
        }
        advance();
        if (peek().type == TokenType.STAR) {
            if (!funcName.equals("COUNT")) throw new RuntimeException("Invalid use of '*' with function '" + funcName + "'");
            advance();
            match(TokenType.RPAREN);
            return isAggregate;
        }
        if (peek().type == TokenType.DISTINCT) {
            if (!isAggregate) throw new RuntimeException("DISTINCT can only be used with aggregate functions");
            advance();
        }
        if (peek().type == TokenType.RPAREN) {
            if (funcName.equals("RAND") || funcName.equals("RANDOM")) {
                advance();
                return false;
            }
            throw new RuntimeException("Syntax Error: Function '" + funcName + "' requires arguments");
        }
        boolean argAgg = parseExpression();
        while (peek().type == TokenType.COMMA) {
            advance();
            argAgg |= parseExpression();
        }
        match(TokenType.RPAREN);
        return isAggregate || argAgg;
    }

    // -------------------------------------------------------------------------
    // Helper methods
    // -------------------------------------------------------------------------
    private void match(TokenType expected) {
        if (peek().type == expected) {
            advance();
        } else {
            generateDetailedError(expected);
        }
    }

    private void generateDetailedError(TokenType expected) {
        Token currentToken = peek();
        String msg = "Syntax Error at position " + currentToken.position + ": ";
        if (expected == TokenType.FROM && currentToken.type == TokenType.WHERE) {
            msg += "Found 'WHERE' before 'FROM'. The FROM clause must come before WHERE.";
        } else if (expected == TokenType.IDENTIFIER) {
            msg += "Expected a Column or Table name, but found '" + currentToken.value + "'.";
        } else {
            msg += "Expected " + expected + " but found '" + currentToken.value + "'.";
        }
        throw new RuntimeException(msg);
    }

    private Token peek() {
        return tokens.get(current);
    }

    private Token previous() {
        return tokens.get(current - 1);
    }

    private void advance() {
        if (current < tokens.size()) current++;
    }

    // -------------------------------------------------------------------------
    // Inner classes for GROUP BY / HAVING validation
    // -------------------------------------------------------------------------
    private static class SelectItemInfo {
        final String signature;
        final boolean isAggregate;
        final String alias;
        final int position;
        final Set<String> columnRefs;

        SelectItemInfo(String signature, boolean isAggregate, String alias, int position, Set<String> columnRefs) {
            this.signature = signature;
            this.isAggregate = isAggregate;
            this.alias = alias;
            this.position = position;
            this.columnRefs = columnRefs != null ? columnRefs : new HashSet<>();
        }
    }

    private static class GroupByItem {
        final int ordinal;       // 1-based, or -1 if expression
        final String expression; // when ordinal == -1

        GroupByItem(int ordinal, String expression) {
            this.ordinal = ordinal;
            this.expression = expression;
        }
    }

    private static class HavingInfo {
        final Set<String> columnRefs = new HashSet<>();
        final Set<String> aggregateColumnRefs = new HashSet<>();
    }

    private static class ExprInfo {
        final Set<String> columnRefs = new HashSet<>();
        final Set<String> aggregateColumnRefs = new HashSet<>();
        boolean hasAggregate = false;
    }
}

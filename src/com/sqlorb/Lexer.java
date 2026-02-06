package com.sqlorb;

import java.util.ArrayList;
import java.util.List;

public class Lexer {
    private final String input;
    private int pos = 0;

    public Lexer(String input)
     {
        this.input = input;
    }

    public List<Token> tokenize() // method to add token to the list of tokens
    {
        List<Token> tokens = new ArrayList<>();// store the object of the token class in the list of tokens

        while (pos < input.length()) //main while loop to read the input string character by character until we reach the end of the string
        {
            char current = input.charAt(pos);

            //  Skip comments
            // SQL single-line comment: -- comment until EOL
            if (current == '-' && pos + 1 < input.length() && input.charAt(pos + 1) == '-') 
            {
                pos += 2; // skip '--'
                while (pos < input.length() && input.charAt(pos) != '\n' && input.charAt(pos) != '\r')
                    pos++;
                continue; // next loop will skip the newline as whitespace
            }

            // Other single-line comment style: # comment until EOL
            if (current == '#') 
            {
                pos++; // skip '#'
                while (pos < input.length() && input.charAt(pos) != '\n' && input.charAt(pos) != '\r')  // \r not bringing cursor to the current line start                 pos++;
                continue;
            }

            // Multi-line comment: /* comment */
            if (current == '/' && pos + 1 < input.length() && input.charAt(pos + 1) == '*') 
                {
                pos += 2; // skip '/*'
                while (pos + 1 < input.length()) 
                {
                    if (input.charAt(pos) == '*' && input.charAt(pos + 1) == '/') 
                    {
                        pos += 2; // consume '*/'
                        break;
                    }
                    pos++;
                }
                continue;
            }

            // --------------------------------------------------------------
           
           
           
            // Skip starting Whitespace
            if (Character.isWhitespace(current)) 
            {
                pos++;
                continue;
            }

          
            // Strings: single-quoted or double-quoted
            if (current == '\'' || current == '"') 
            {
                int start = pos;
                char quote = current;
                pos++; // skip opening quote
                StringBuilder sb = new StringBuilder();
                boolean closed = false;

                while (pos < input.length())
                 {
                    char c = input.charAt(pos);

                    if (c == quote)
                    {
                        // escaped quote: '' or ""
                     if (pos + 1 < input.length() && input.charAt(pos + 1) == quote)
                       {
                            sb.append(quote);
                            pos += 2;
                            continue;
                        }
                        pos++; // closing quote
                        closed = true;
                        break;
                    }

                    sb.append(c);
                    pos++;
                }

                if (!closed)
                 {
                    throw new RuntimeException(
                            "Error at position " + start + ": Unterminated column name/string starting with " + quote);
                 }

                tokens.add(new Token(TokenType.STRING, sb.toString(), start));  //string substring without quotes as token 
                continue;
            }
            // Quoted identifiers: double-quoted "identifier" or backtick-quoted
            // `identifier`
            if (current == '"' || current == '`') 
                {
                int start = pos;
                char quote = current;
                pos++; // skip opening quote
                StringBuilder sb = new StringBuilder();
                while (pos < input.length()) 
                    {
                    char c = input.charAt(pos);
                    if (c == quote) 
                        {
                        // allow doubled quote escaping (e.g., "")
                        if (pos + 1 < input.length() && input.charAt(pos + 1) == quote) 
                        {
                            sb.append(quote);
                            pos += 2;
                            continue;
                        } 
                        else 
                        {
                            pos++; // consume closing quote
                            break;
                        }
                    }
                    sb.append(c);
                    pos++;
                }
                // Treat quoted identifiers as IDENTIFIER tokens (keep original casing inside)
                tokens.add(new Token(TokenType.IDENTIFIER, sb.toString(), start));
                continue;
            }

            // 2. Multi-character operators handlig separately before single-character ones to avoid confusion (e.g., >= vs >)
            if (pos + 1 < input.length())
              {
                String twoChar = input.substring(pos, pos + 2);
                switch (twoChar) // if we found the operator then we will shift to the next position and add the
                                 // token to the list of tokens
                {
                    case ">=":
                        tokens.add(new Token(TokenType.GE, ">=", pos));
                        pos += 2;
                        continue;
                    case "<=":
                        tokens.add(new Token(TokenType.LE, "<=", pos));
                        pos += 2;
                        continue;
                    case "!=":
                        tokens.add(new Token(TokenType.NOT_EQUALS, "!=", pos));
                        pos += 2;
                        continue;
                    case "<>":
                        tokens.add(new Token(TokenType.NOT_EQUALS_SQL, "<>", pos));
                        pos += 2;
                        continue;
                }
            }

            // 3. Single-character operators and symbols (using direct enums)
            switch (current) {
                case ',':
                    tokens.add(new Token(TokenType.COMMA, ",", pos));
                    pos++;
                    continue;
                case '*':
                    tokens.add(new Token(TokenType.STAR, "*", pos));
                    pos++;
                    continue;
                case '+':
                    tokens.add(new Token(TokenType.PLUS, "+", pos));
                    pos++;
                    continue;
                case '-':
                    tokens.add(new Token(TokenType.MINUS, "-", pos));
                    pos++;
                    continue;
                case '/':
                    tokens.add(new Token(TokenType.SLASH, "/", pos));
                    pos++;
                    continue;
                case '%':
                    tokens.add(new Token(TokenType.PERCENT, "%", pos));
                    pos++;
                    continue;
                case '=':
                    tokens.add(new Token(TokenType.EQUALS, "=", pos));
                    pos++;
                    continue;
                case '>':
                    tokens.add(new Token(TokenType.GT, ">", pos));
                    pos++;
                    continue;
                case '<':
                    tokens.add(new Token(TokenType.LT, "<", pos));
                    pos++;
                    continue;
                case ';':
                    tokens.add(new Token(TokenType.SEMICOLON, ";", pos));
                    pos++;
                    continue;
                case '(':
                    tokens.add(new Token(TokenType.LPAREN, "(", pos));
                    pos++;
                    continue;
                case ')':
                    tokens.add(new Token(TokenType.RPAREN, ")", pos));
                    pos++;
                    continue;
                case '.':
                    tokens.add(new Token(TokenType.DOT, ".", pos));
                    pos++;
                    continue;
            }// end on main while loop

            // 4. Keywords or Identifiers
            if (Character.isLetter(current))
           {
                tokens.add(readIdentifier());
                continue;
            }

            // 5. Numbers
            if (Character.isDigit(current)) 
           {
                tokens.add(readNumber());
                continue;
            }

            // 6. Unknown character
            System.err.println("Unknown character: " + current);
            pos++;
        }

        tokens.add(new Token(TokenType.EOF, "", pos));
        return tokens;
    }

    private Token readIdentifier() 
    {
        int start = pos;
        // Keep reading while letters/digits/_       here we will read the whole word and then we will check if it is a keyword or an identifier if it is a keyword we will return the token of the keyword otherwise we will return the token of the identifier
        while (pos < input.length() && (Character.isLetterOrDigit(input.charAt(pos)) || input.charAt(pos) == '_')) 
        {
            pos++;
         } // this will read the whole word (e.g., "SELECT", "users", "age")

        String word = input.substring(start, pos);

        // Check if it is a reserved Keyword (single-word matching)
        switch (word.toUpperCase()) {
            case "SELECT":
                return new Token(TokenType.SELECT, word, start);
            case "FROM":
                return new Token(TokenType.FROM, word, start);
            case "WHERE":
                return new Token(TokenType.WHERE, word, start);
            case "DISTINCT":
                return new Token(TokenType.DISTINCT, word, start);
            case "AS":
                return new Token(TokenType.AS, word, start);
            case "ORDER":
                return new Token(TokenType.ORDER_BY, word, start); // parser can combine ORDER + BY
            case "GROUP":
                return new Token(TokenType.GROUP_BY, word, start); // parser can combine GROUP + BY
            case "HAVING":
                return new Token(TokenType.HAVING, word, start);
            case "LIMIT":
                return new Token(TokenType.LIMIT, word, start);
            case "OFFSET":
                return new Token(TokenType.OFFSET, word, start);
            case "FETCH":
                return new Token(TokenType.FETCH, word, start);
            case "TOP":
                return new Token(TokenType.TOP, word, start);
            case "INSERT":
                return new Token(TokenType.INSERT, word, start);
            case "INTO":
                return new Token(TokenType.INTO, word, start);
            case "VALUES":
                return new Token(TokenType.VALUES, word, start);
            case "UPDATE":
                return new Token(TokenType.UPDATE, word, start);
            case "SET":
                return new Token(TokenType.SET, word, start);
            case "DELETE":
                return new Token(TokenType.DELETE, word, start);
            case "CREATE":
                return new Token(TokenType.CREATE, word, start);
            case "ALTER":
                return new Token(TokenType.ALTER, word, start);
            case "DROP":
                return new Token(TokenType.DROP, word, start);
            case "TRUNCATE":
                return new Token(TokenType.TRUNCATE, word, start);
            case "RENAME":
                return new Token(TokenType.RENAME, word, start);
            case "DATABASE":
                return new Token(TokenType.DATABASE, word, start);
            case "TABLE":
                return new Token(TokenType.TABLE, word, start);
            case "VIEW":
                return new Token(TokenType.VIEW, word, start);
            case "INDEX":
                return new Token(TokenType.INDEX, word, start);
            case "SEQUENCE":
                return new Token(TokenType.SEQUENCE, word, start);
            case "SCHEMA":
                return new Token(TokenType.SCHEMA, word, start);
            case "PRIMARY":
                return new Token(TokenType.PRIMARY_KEY, word, start);
            case "FOREIGN":
                return new Token(TokenType.FOREIGN_KEY, word, start);
            case "REFERENCES":
                return new Token(TokenType.REFERENCES, word, start);
            case "UNIQUE":
                return new Token(TokenType.UNIQUE, word, start);
            case "NOT":
                return new Token(TokenType.NOT, word, start);
            case "CHECK":
                return new Token(TokenType.CHECK, word, start);
            case "DEFAULT":
                return new Token(TokenType.DEFAULT, word, start);
            case "JOIN":
                return new Token(TokenType.JOIN, word, start);
            case "INNER":
                return new Token(TokenType.INNER_JOIN, word, start);
            case "LEFT":
                return new Token(TokenType.LEFT_JOIN, word, start);
            case "RIGHT":
                return new Token(TokenType.RIGHT_JOIN, word, start);
            case "FULL":
                return new Token(TokenType.FULL_JOIN, word, start);
            case "CROSS":
                return new Token(TokenType.CROSS_JOIN, word, start);
            case "ON":
                return new Token(TokenType.ON, word, start);
            case "USING":
                return new Token(TokenType.USING, word, start);
            case "UNION":
                return new Token(TokenType.UNION, word, start);
            case "INTERSECT":
                return new Token(TokenType.INTERSECT, word, start);
            case "EXCEPT":
                return new Token(TokenType.EXCEPT, word, start);
            case "IN":
                return new Token(TokenType.IN, word, start);
            case "EXISTS":
                return new Token(TokenType.EXISTS, word, start);
            case "ANY":
                return new Token(TokenType.ANY, word, start);
            case "ALL":
                return new Token(TokenType.ALL, word, start);
            case "AND":
                return new Token(TokenType.AND, word, start);
            case "OR":
                return new Token(TokenType.OR, word, start);
            case "CASE":
                return new Token(TokenType.CASE, word, start);
            case "WHEN":
                return new Token(TokenType.WHEN, word, start);
            case "THEN":
                return new Token(TokenType.THEN, word, start);
            case "ELSE":
                return new Token(TokenType.ELSE, word, start);
            case "END":
                return new Token(TokenType.END, word, start);
            case "NULL":
                return new Token(TokenType.NULL, word, start);
            case "IS":
                return new Token(TokenType.IS, word, start);
            case "TRUE":
                return new Token(TokenType.TRUE, word, start);
            case "FALSE":
                return new Token(TokenType.FALSE, word, start);
            case "ASC":
                return new Token(TokenType.ASC, word, start);
            case "DESC":
                return new Token(TokenType.DESC, word, start);
            case "BETWEEN":
                return new Token(TokenType.BETWEEN, word, start);
            case "LIKE":
                return new Token(TokenType.LIKE, word, start);
            case "ESCAPE":
                return new Token(TokenType.ESCAPE, word, start);
            case "COUNT":
                return new Token(TokenType.COUNT, word, start);
            case "SUM":
                return new Token(TokenType.SUM, word, start);
            case "AVG":
                return new Token(TokenType.AVG, word, start);
            case "MIN":
                return new Token(TokenType.MIN, word, start);
            case "MAX":
                return new Token(TokenType.MAX, word, start);
            case "COMMIT":
                return new Token(TokenType.COMMIT, word, start);
            case "ROLLBACK":
                return new Token(TokenType.ROLLBACK, word, start);
            case "SAVEPOINT":
                return new Token(TokenType.SAVEPOINT, word, start);
            case "BEGIN":
                return new Token(TokenType.BEGIN, word, start);
            case "TRANSACTION":
                return new Token(TokenType.TRANSACTION, word, start);
            case "GRANT":
                return new Token(TokenType.GRANT, word, start);
            case "REVOKE":
                return new Token(TokenType.REVOKE, word, start);
            default:
                return new Token(TokenType.IDENTIFIER, word, start);
        }
    }

    private Token readNumber() 
    {
        int start = pos;
        // integer part
        while (pos < input.length() && Character.isDigit(input.charAt(pos))) 
        {
            pos++;
        }
        // optional fractional part
        if (pos < input.length() && input.charAt(pos) == '.') 
        {
            pos++;
            while (pos < input.length() && Character.isDigit(input.charAt(pos))) 
            {
                pos++;
            }
        }
        return new Token(TokenType.NUMBER, input.substring(start, pos), start);
    }
}
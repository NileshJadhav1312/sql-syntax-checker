package com.sqlorb;

public class Token
 {
    public final TokenType type;
    public final String value;
    public final int position; // To tell user "Error at index 5"

    public Token(TokenType type, String value, int position) 
    {
        this.type = type;
        this.value = value;
        this.position = position;
    }

    @Override
    public String toString() 
    {
        return String.format("Token{%s, '%s', position=%d}", type, value, position);
    }
}
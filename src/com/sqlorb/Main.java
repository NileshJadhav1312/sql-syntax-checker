package com.sqlorb;

import java.util.List;
import java.util.Scanner;

public class Main {
    public static void main(String[] args) 
    {
        // We will allow you to type queries in the Console to test freely
        Scanner scanner = new Scanner(System.in);
        
        System.out.println("SQL Syntax Checker Started...");
        System.out.println("Type a query (or 'exit' to quit):");

        while (true) 
        {
            System.out.print("\nSQL > ");
            String input = scanner.nextLine();
            
            if (input.equalsIgnoreCase("exit")) break;
            
            // Quick pre-check: if the raw input is empty or is just a comment, skip processing
            String rawTrim = input.trim();
            if (rawTrim.isEmpty() || rawTrim.startsWith("--") || rawTrim.startsWith("#") || rawTrim.startsWith("/*")) {
                continue;
            }

            try {
                // 1. Lexical Analysis
                Lexer lexer = new Lexer(input);
                List<Token> tokens = lexer.tokenize();

                // If the token stream contains only EOF (input was empty or only comments/whitespace), skip parsing
                if (tokens.size() == 1 && tokens.get(0).type == TokenType.EOF) {
                    // nothing to parse — treat as no-op
                    continue;
                }

                // 2. Syntax Analysis
                Parser parser = new Parser(tokens);
                parser.parseQuery();

                System.out.println(" Valid Syntax!");

            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
            }
        }
        scanner.close();
    }
}
package com.sqlorb;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
//import java.util.ArrayList;
import java.io.IOException;

public class TestRunner {
    public static void main(String[] args) throws IOException {
        if (args.length == 0) {
            System.err.println("Usage: java com.sqlorb.TestRunner <test-file>");
            return;
        }

        List<String> lines = Files.readAllLines(Paths.get(args[0]));
        int lineNo = 0;
        for (String line : lines) {
            lineNo++;
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("--") || trimmed.startsWith("#") || trimmed.startsWith("/*")) {
                continue;
            }

            System.out.println("Test [" + lineNo + "]: " + line);
            try {
                Lexer lexer = new Lexer(line);
                List<Token> tokens = lexer.tokenize();

                if (tokens.size() == 1 && tokens.get(0).type == TokenType.EOF) {
                    System.out.println("  Skipped (no tokens)");
                    continue;
                }

                Parser parser = new Parser(tokens);
                parser.parseQuery();
                System.out.println("  Valid Syntax!");
            } catch (Exception e) {
                System.out.println("  Error: " + e.getMessage());
            }
        }
    }
}

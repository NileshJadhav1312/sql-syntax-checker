package com.sqlorb;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;

public class TestSuite {
    static class TestCase {
        String sql;
        boolean expectValid;
        int index;
        TestCase(String s, boolean v, int i){ sql=s; expectValid=v; index=i; }
    }

    public static void main(String[] args) throws Exception {
        File f = new File("test/sql_test_cases.txt");
        if (!f.exists()) {
            System.err.println("Test file not found: " + f.getAbsolutePath());
            return;
        }

        List<TestCase> cases = new ArrayList<>();
        BufferedReader br = new BufferedReader(new FileReader(f));
        String line;
        boolean expectValid = true; // default until we see INVALID marker
        StringBuilder current = new StringBuilder();
        int idx = 1;
        while ((line = br.readLine()) != null) {
            String t = line.trim();
            if (t.isEmpty()) {
                if (current.length() > 0) {
                    // split on semicolons to support multiple statements in one block
                    String block = current.toString().trim();
                    String[] parts = block.split(";");
                    for (String p : parts) {
                        String s = p.trim();
                        if (!s.isEmpty()) cases.add(new TestCase(s, expectValid, idx++));
                    }
                    current.setLength(0);
                }
                continue;
            }
            // Section markers
            if (t.startsWith("#")) {
                if (t.toUpperCase().contains("VALID QUERIES")) {
                    expectValid = true; current.setLength(0); continue;
                }
                if (t.toUpperCase().contains("INVALID QUERIES")) {
                    expectValid = false; current.setLength(0); continue;
                }
                // skip comment lines beginning with '#'
                continue;
            }
            // skip header comments that start with SQL comment markers or single-hyphen notes
            if (t.startsWith("--") || t.startsWith("-")) {
                continue;
            }
            // skip expected messages lines
            if (t.toUpperCase().startsWith("# EXPECTED:")) continue;

            // accumulate the SQL (preserve spacing)
            if (current.length() > 0) current.append(' ');
            current.append(line);
        }
        br.close();
        if (current.length() > 0) {
            String block = current.toString().trim();
            String[] parts = block.split(";");
            for (String p : parts) {
                String s = p.trim();
                if (!s.isEmpty()) cases.add(new TestCase(s, expectValid, idx++));
            }
        }

        int passed = 0;
        List<String> failures = new ArrayList<>();
        System.out.println("Total cases: " + cases.size());

        for (TestCase tc : cases) {
            System.out.println("\n--- Case #" + tc.index + " (expect " + (tc.expectValid?"VALID":"INVALID") + ") ---");
            System.out.println(tc.sql);
            try {
                Lexer lexer = new Lexer(tc.sql);
                List<Token> tokens = lexer.tokenize();
                Parser parser = new Parser(tokens);
                parser.parseQuery();
                // parsed successfully
                if (tc.expectValid) {
                    System.out.println("PASS (parsed)");
                    passed++;
                } else {
                    System.out.println("FAIL — parsed but expected INVALID");
                    failures.add("Case #"+tc.index+": parsed but expected INVALID -> " + tc.sql);
                }
            } catch (Exception ex) {
                // parsing failed
                if (!tc.expectValid) {
                    System.out.println("PASS (rejected): " + ex.getMessage());
                    passed++;
                } else {
                    System.out.println("FAIL — expected VALID but got error: " + ex.getMessage());
                    failures.add("Case #"+tc.index+": expected VALID but got error: " + ex.getMessage() + " -> " + tc.sql);
                }
            }
        }

        System.out.println("\nSummary: Passed " + passed + " / " + cases.size());
        if (!failures.isEmpty()) {
            System.out.println("\nFailures:");
            for (String s : failures) System.out.println(s);
        }
    }
}

package com.sqlorb;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class Server {

    public static void main(String[] args) throws IOException
     {
        int port = 8000;
        // Create a server that listens on port 8000
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);

        // Create the "/validate" endpoint
        server.createContext("/validate", new ValidationHandler());

        server.setExecutor(null); // Default executor
        server.start();
        
        System.out.println("Server started!"); 
        System.out.println("   Waiting for requests at http://localhost:" + port + "/validate");
    }

    static class ValidationHandler implements HttpHandler 
    {
        @Override
        public void handle(HttpExchange exchange) throws IOException 
        {
            // 1. ADD CORS HEADERS (Crucial for your HTML to work)
            exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "POST, OPTIONS");
            exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");

            // Handle "Pre-flight" request (Browser checking if server is safe)
            if (exchange.getRequestMethod().equalsIgnoreCase("OPTIONS")) {
                exchange.sendResponseHeaders(204, -1);
                exchange.getResponseBody().close();  // Must close to properly terminate the exchange
                return;
            }

            // Only allow POST requests
            if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
                exchange.sendResponseHeaders(405, -1); // Method Not Allowed
                exchange.getResponseBody().close();  // Must close to properly terminate the exchange
                return;
            }

            // 2. READ THE SQL FROM THE REQUEST BODY
            InputStream is = exchange.getRequestBody();
            String sqlQuery = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            
            System.out.println("Received SQL: " + sqlQuery);

            String responseMessage;
            int statusCode;

            // 3. RUN THE PARSER LOGIC
            try {
                Lexer lexer = new Lexer(sqlQuery);
                List<Token> tokens = lexer.tokenize();

                // If the client sent only comments / whitespace, the lexer will return only EOF.
                // Main.java handled this case for interactive use; the HTTP handler must too.
                if (tokens.size() == 1 && tokens.get(0).type == TokenType.EOF) {
                    responseMessage = "{\"status\": \"error\", \"message\": \"No SQL provided (empty or comment-only body).\"}";
                    statusCode = 400;
                } else {
                    Parser parser = new Parser(tokens);
                    parser.parseQuery();

                    responseMessage = "{\"status\": \"success\", \"message\": \"✅ Valid Syntax!\"}";
                    statusCode = 200; // OK
                }
                
            } 
            catch (Exception e) {
                // Escape quotes in error message to avoid breaking JSON
                String cleanError = e.getMessage().replace("\"", "'");
                responseMessage = "{\"status\": \"error\", \"message\": \"" + cleanError + "\"}";
                statusCode = 400; // Bad Request
            }

            // 4. SEND RESPONSE BACK
            byte[] responseBytes = responseMessage.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(statusCode, responseBytes.length);
            
            OutputStream os = exchange.getResponseBody();
            os.write(responseBytes);
            os.close();
        }
    }
}
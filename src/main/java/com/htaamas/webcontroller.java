package com.htaamas;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.RuntimeMXBean;
import java.net.InetSocketAddress;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.Timer;
import java.util.TimerTask;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public class webcontroller extends JavaPlugin {
    private HttpServer httpServer;
    private WebSocketServer webSocketServer;
    private Set<WebSocket> connectedClients = new HashSet<>();
    private ConsoleAppender consoleAppender;

    @Override
    public void onEnable() {
        setupConsoleAppender();

        // Start HTTP server for web interface
        try {
            httpServer = HttpServer.create(new InetSocketAddress(8080), 0);
            httpServer.createContext("/", new WebInterfaceHandler());
            httpServer.createContext("/login", new LoginHandler());
            httpServer.setExecutor(Executors.newFixedThreadPool(10));
            httpServer.start();
            getLogger().info("HTTP server started on port 8080");

            // Start sending server information every 5 seconds
            Timer timer = new Timer();
            timer.schedule(new TimerTask() {
                @Override
                public void run() {
                    sendServerInfo();
                }
            }, 0, 5000); // 5 seconds

            // Start WebSocket server for live logs
            webSocketServer = new WebSocketServer(new InetSocketAddress(8081)) {
                @Override
                public void onOpen(WebSocket conn, ClientHandshake handshake) {
                    connectedClients.add(conn);
                    getLogger().info("New WebSocket connection");
                }

                @Override
                public void onClose(WebSocket conn, int code, String reason, boolean remote) {
                    connectedClients.remove(conn);
                    getLogger().info("WebSocket connection closed");
                }

                @Override
                public void onMessage(WebSocket conn, String message) {
                    // Execute command and send response
                    Bukkit.getScheduler().runTask(webcontroller.this, () -> {
                        executeCommand(message);
                    });
                }

                @Override
                public void onError(WebSocket conn, Exception ex) {
                    getLogger().warning("WebSocket error: " + ex.getMessage());
                }

                @Override
                public void onStart() {
                    getLogger().info("WebSocket server started on port 8081");
                }
            };
            webSocketServer.start();

        } catch (IOException e) {
            getLogger().severe("Failed to start web server: " + e.getMessage());
        }
    }

    private void setupConsoleAppender() {
        String logPattern = "[%d{HH:mm:ss} %level]: %msg%n"; // Format to match Minecraft's console output
        consoleAppender = new ConsoleAppender("WebConsoleAppender",
                PatternLayout.newBuilder().withPattern(logPattern).build());
        Logger rootLogger = (Logger) LogManager.getRootLogger();
        rootLogger.addAppender(consoleAppender);
    }

    private class ConsoleAppender extends AbstractAppender {
        protected ConsoleAppender(String name, PatternLayout layout) {
            super(name, null, layout, true, Property.EMPTY_ARRAY);
            start();
        }

        @Override
        public void append(LogEvent event) {
            String message = new String(getLayout().toByteArray(event));
            broadcastToClients(message);
        }
    }

    private void sendServerInfo() {
        // Get system stats
        OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        RuntimeMXBean runtimeBean = ManagementFactory.getRuntimeMXBean();

        // Memory usage
        long usedMemory = memoryBean.getHeapMemoryUsage().getUsed();
        long maxMemory = memoryBean.getHeapMemoryUsage().getMax();

        // Uptime (in milliseconds)
        long uptime = runtimeBean.getUptime();

        // CPU load (0.0 to 1.0)
        double cpuLoad = osBean.getSystemLoadAverage();

        // Handle negative load values
        if (cpuLoad < 0) {
            cpuLoad = 0; // or display "N/A"
        }

        String cpuLoadDisplay = String.format("%.2f%%", cpuLoad * 100);

        // Prepare JSON-like data string
        String serverInfo = String.format(
            "{\"memoryUsage\":\"%d/%d MB\",\"uptime\":\"%d minutes\",\"cpuLoad\":\"%s\"}",
            usedMemory / (1024 * 1024), maxMemory / (1024 * 1024),
            uptime / 60000, cpuLoadDisplay
        );

        // Broadcast to all clients
        broadcastToClients(serverInfo);
    }

    private void broadcastToClients(String message) {
        for (WebSocket client : connectedClients) {
            client.send(message);
        }
    }

    @Override
    public void onDisable() {
        if (httpServer != null) {
            httpServer.stop(0);
        }
        if (webSocketServer != null) {
            try {
                webSocketServer.stop();
            } catch (InterruptedException e) {
                getLogger().severe("Error stopping WebSocket server: " + e.getMessage());
            }
        }

        // Remove console appender
        if (consoleAppender != null) {
            Logger rootLogger = (Logger) LogManager.getRootLogger();
            rootLogger.removeAppender(consoleAppender);
        }
    }

    private void executeCommand(String command) {
        try {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
        } catch (Exception e) {
            getLogger().severe("Error executing command: " + e.getMessage());
        }
    }

    public class WebInterfaceHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("GET".equals(exchange.getRequestMethod())) {
                // Load HTML content from the external file
                String response = getHtmlContent();
    
                // Set the content type to text/html
                exchange.getResponseHeaders().set("Content-Type", "text/html");
                // Send the response headers
                exchange.sendResponseHeaders(200, response.getBytes().length);
    
                // Write the HTML content to the response body
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(response.getBytes());
                }
            }
        }
    
        // Helper method to load HTML content from a file inside the JAR
        private String getHtmlContent() {
            try (InputStream inputStream = getClass().getClassLoader().getResourceAsStream("index.html")) {
                if (inputStream == null) {
                    return "<html><body><h1>HTML file not found</h1></body></html>";
                }
                return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                e.printStackTrace();
                return "<html><body><h1>Error reading HTML file</h1></body></html>";
            }
        }
    }

    // LoginHandler should be a separate class
    public class LoginHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("POST".equals(exchange.getRequestMethod())) {
                // Handle login logic here, such as reading JSON input, validating credentials, etc.
                handleLogin(exchange);
            } else {
                // For GET request, serve the login HTML file
                serveLoginPage(exchange);
            }
        }
    
        private void serveLoginPage(HttpExchange exchange) throws IOException {
            // Load the login.html file from resources
            String response = getHtmlContent("login.html");
    
            // Set the content type to text/html
            exchange.getResponseHeaders().set("Content-Type", "text/html");
    
            // Send the response headers
            exchange.sendResponseHeaders(200, response.getBytes().length);
    
            // Write the HTML content to the response body
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes());
            }
        }
    
        private String getHtmlContent(String filename) {
            // Load the HTML content from the resources folder
            try (InputStream inputStream = getClass().getClassLoader().getResourceAsStream(filename)) {
                if (inputStream == null) {
                    return "<html><body><h1>HTML file not found</h1></body></html>";
                }
                return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                e.printStackTrace();
                return "<html><body><h1>Error reading HTML file</h1></body></html>";
            }
        }
    
        private void handleLogin(HttpExchange exchange) throws IOException {
            // Process login request (username and password)
            InputStreamReader isr = new InputStreamReader(exchange.getRequestBody(), "utf-8");
            BufferedReader br = new BufferedReader(isr);
            StringBuilder jsonInput = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                jsonInput.append(line);
            }
    
            // Parse JSON (you can use a library like Gson or org.json)
            String requestBody = jsonInput.toString();
            JSONObject json = new JSONObject(requestBody);
            String username = json.getString("username");
            String password = json.getString("password");
    
            // Validate credentials (hardcoded example, replace with real validation)
            if ("admin".equals(username) && "password123".equals(password)) {
                String response = "{\"token\":\"abc123\"}"; // Example token
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.getBytes().length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(response.getBytes());
                }
            } else {
                exchange.sendResponseHeaders(401, -1); // Unauthorized
            }
        }
    }
}
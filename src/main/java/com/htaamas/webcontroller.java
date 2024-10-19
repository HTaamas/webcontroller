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

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executors;

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
            httpServer.setExecutor(Executors.newFixedThreadPool(10));
            httpServer.start();
            getLogger().info("HTTP server started on port 8080");

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

    private void broadcastToClients(String message) {
        // Remove leading/trailing whitespace or newlines
        String cleanedMessage = message.replaceAll("\u001B\\[[;\\d]*m", "").trim();
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

    class WebInterfaceHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("GET".equals(exchange.getRequestMethod())) {
                String response = """
                <!DOCTYPE html>
                    <html lang="en">
                    <head>
                        <meta charset="UTF-8">
                        <meta name="viewport" content="width=device-width, initial-scale=1.0">
                        <title>Minecraft Server Console</title>
                        <script src="https://cdn.tailwindcss.com"></script>
                    <script src="https://cdn.jsdelivr.net/npm/ansi_up@4.0.4/ansi_up.min.js"></script>
                    </head>
                    <body class="bg-gray-900 text-white p-6">
                        <div class="max-w-4xl mx-auto">
                            <h1 class="text-3xl font-bold mb-4">Minecraft Server Console</h1>
                           
                            <div class="mb-4">
                                <input type="text" id="commandInput"
                                        class="w-full p-2 bg-gray-800 border border-gray-700 rounded text-white"
                                        placeholder="Enter command...">
                            </div>
                           
                            <button onclick="sendCommand()"
                                    class="bg-green-600 hover:bg-green-700 text-white font-bold py-2 px-4 rounded mb-4">
                                Execute Command
                            </button>
                           
                            <button onclick="sendDayCommand()"
                                    class="bg-yellow-600 hover:bg-yellow-700 text-white font-bold py-2 px-4 rounded mb-4">
                                Time Set Day
                            </button>
                
                            <button onclick="sendNightCommand()"
                                    class="bg-gray-700 hover:bg-gray-800 text-white font-bold py-2 px-4 rounded mb-4">
                                Time Night Day
                            </button>
                           
                            <button onclick="sendWClearCommand()"
                                    class="bg-blue-500 hover:bg-blue-600 text-white font-bold py-2 px-4 rounded mb-4">
                                Weather clear
                            </button>
                           
                            <button onclick="sendWRainCommand()"
                                    class="bg-blue-800 hover:bg-blue-900 text-white font-bold py-2 px-4 rounded mb-4">
                                Weather Rain
                            </button>
                
                            <div id="logContainer"
                                    class="bg-gray-800 p-4 rounded h-96 overflow-y-auto font-mono text-sm">
                            </div>
                        </div>
                
                        <script>
                            //console.log(window.location.origin.split("/")[2].split(":")[0]);
                            const socket = new WebSocket('ws://'+window.location.origin.split("/")[2].split(":")[0]+':8081');
                            const logContainer = document.getElementById('logContainer');
                            const commandInput = document.getElementById('commandInput');
                
                            const ansi_up = new AnsiUp();
                            socket.onmessage = function(event) {
                                const logEntry = document.createElement('div');
                                logEntry.innerHTML = ansi_up.ansi_to_html(event.data);
                                if (logEntry.textContent.trim() !== "") {
                                    logContainer.appendChild(logEntry);
                                    logContainer.scrollTop = logContainer.scrollHeight;
                                }
                            };
                
                            function sendCommand() {
                                const command = commandInput.value.trim();
                                if (command) {
                                    socket.send(command);
                                    commandInput.value = '';
                                }
                            }

                            function sendDayCommand() {
                                socket.send("time set day");
                            }
                
                            function sendNightCommand() {
                                socket.send("time set night");
                            }
                
                            function sendWClearCommand() {
                                socket.send("weather clear");
                            }
                
                            function sendWRainCommand() {
                                socket.send("weather rain");
                            }
                
                            commandInput.addEventListener('keypress', function(e) {
                                if (e.key === 'Enter') {
                                    sendCommand();
                                }
                            });
                        </script>
                    </body>
                </html>
                """;

                exchange.getResponseHeaders().set("Content-Type", "text/html");
                exchange.sendResponseHeaders(200, response.getBytes().length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(response.getBytes());
                }
            }
        }
    }
}
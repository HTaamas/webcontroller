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
                        <title>Minecraft Web Terminál</title>
                        <script src="https://cdn.tailwindcss.com"></script>
                        <script src="https://cdn.jsdelivr.net/npm/ansi_up@4.0.4/ansi_up.min.js"></script>

                        <style>
                            /* Spinner animation */
                            @keyframes spin {
                                0% { transform: rotate(0deg); }
                                100% { transform: rotate(360deg); }
                            }
                        </style>
                    </head>
                    <body class="bg-gray-900 text-white p-6">
                        <div class="max-w-4xl mx-auto">
                            <h1 class="text-3xl font-bold mb-4">Minecraft Web Terminál</h1>

                            <!-- Command buttons -->
                            <button onclick="sendDayCommand()" class="bg-yellow-600 hover:bg-yellow-700 text-white font-bold py-2 px-4 rounded mb-4">Time Set Day</button>
                            <button onclick="sendNightCommand()" class="bg-gray-700 hover:bg-gray-800 text-white font-bold py-2 px-4 rounded mb-4">Time Set Night</button>
                            <button onclick="sendWClearCommand()" class="bg-blue-500 hover:bg-blue-600 text-white font-bold py-2 px-4 rounded mb-4">Weather Clear</button>
                            <button onclick="sendWRainCommand()" class="bg-blue-800 hover:bg-blue-900 text-white font-bold py-2 px-4 rounded mb-4">Weather Rain</button>
                            <button onclick="openTellModal()" class="bg-green-600 hover:bg-green-700 text-white font-bold py-2 px-4 rounded mb-4">Tell</button>
                            <button onclick="openStopModal()" class="bg-red-600 hover:bg-red-700 text-white font-bold py-2 px-4 rounded mb-4">Stop</button>

                            <!-- Custom command input -->
                            <div class="mb-4">
                                <input type="text" id="commandInput" class="w-full p-2 bg-gray-800 border border-gray-700 rounded text-white" placeholder="Saját parancs helye...">
                            </div>
                            <button onclick="sendCommand()" class="bg-green-600 hover:bg-green-700 text-white font-bold py-2 px-4 rounded mb-4">Saját parancs küldése</button>

                            <!-- Log container -->
                            <div id="logContainer" class="bg-gray-800 p-4 rounded h-96 overflow-y-auto font-mono text-sm"></div>

                            <!-- Connecting Modal -->
                            <div id="connectingModal" class="fixed inset-0 bg-black bg-opacity-75 flex items-center justify-center">
                                <div class="bg-gray-900 p-8 rounded-lg shadow-lg text-center flex justify-between items-center w-90 space-x-4">
                                    <div class="flex flex-col items-start mr-4">
                                        <!-- Connecting text on the left -->
                                        <h2 class="text-xl font-semibold text-left">Csatlakozás...</h2>
                                        <!-- Subtext below the Connecting text -->
                                        <p class="text-sm text-gray-400 text-left">Kérjük várj ameddig csatlakozunk a szerverhez.</p>
                                        <p class="text-sm text-gray-400 text-left">Ellenőrizd a szerver állapotát!</p>
                                    </div>

                                    <!-- Spinner on the right -->
                                    <div class="w-8 h-8 border-4 border-gray-600 border-t-transparent border-solid rounded-full animate-spin shrink-0" style="width: 32px; height: 32px;"></div>
                                </div>
                            </div>

                            <!-- Tell Modal -->
                            <div id="tellModal" class="hidden fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center transition-opacity duration-300 ease-out opacity-0" onclick="closeTellModal()">
                                <div class="bg-gray-800 p-6 rounded-lg shadow-lg max-w-md w-full relative transform transition-transform duration-300 ease-out scale-90" onclick="event.stopPropagation()">
                                    <button onclick="closeTellModal()" class="absolute top-5.5 right-6 text-gray-400 hover:text-gray-200 font-bold text-3xl w-8 h-8 flex items-center justify-center p-0">&times;</button>
                                    <h2 class="text-2xl font-bold mb-4">Üzenet küldése</h2>
                                    <input type="text" id="tellInput" class="w-full p-2 mb-4 bg-gray-700 border border-gray-600 rounded text-white" placeholder="Mit akarsz üzenni ...">
                                    <div class="flex justify-end">
                                        <button onclick="closeTellModal()" class="bg-gray-600 hover:bg-gray-700 text-white font-bold py-2 px-4 rounded mr-2">Mégse</button>
                                        <button onclick="sendTellCommand()" class="bg-green-600 hover:bg-green-700 text-white font-bold py-2 px-4 rounded">Küldés</button>
                                    </div>
                                </div>
                            </div>

                            <!-- Stop Modal -->
                            <div id="stopModal" class="hidden fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center transition-opacity duration-300 ease-out opacity-0" onclick="closeStopModal()">
                                <div class="bg-gray-800 p-6 rounded-lg shadow-lg max-w-md w-full relative transform transition-transform duration-300 ease-out scale-90" onclick="event.stopPropagation()">
                                    <button onclick="closeStopModal()" class="absolute top-5.5 right-6 text-gray-400 hover:text-gray-200 font-bold text-3xl w-8 h-8 flex items-center justify-center p-0">&times;</button>
                                    <h2 class="text-2xl font-bold mb-4 text-red-500">Leállító parancs küldése</h2>
                                    <p class="mb-4">Biztos le akarod állítani a szervert?</p>
                                    <div class="flex justify-end">
                                        <button onclick="closeStopModal()" class="bg-gray-600 hover:bg-gray-700 text-white font-bold py-2 px-4 rounded mr-2">Nem</button>
                                        <button onclick="sendStopCommand()" class="bg-green-600 hover:bg-green-700 text-white font-bold py-2 px-4 rounded">Igen</button>
                                    </div>
                                </div>
                            </div>
                        </div>

                        <script>
                            let socket;
                            const logContainer = document.getElementById('logContainer');
                            const commandInput = document.getElementById('commandInput');
                            const ansiUp = new AnsiUp(); // Create an instance of AnsiUp
                            
                            // Add an initial log entry to verify the container is working
                            function addLogEntry(message, isDebug = false) {
                                const logEntry = document.createElement('div');
                                logEntry.className = 'log-entry';

                                function formatTime(number) {
                                    return number < 10 ? '0' + number : number;
                                }

                                let now = new Date();
                                let hours = formatTime(now.getHours());
                                let minutes = formatTime(now.getMinutes());
                                let seconds = formatTime(now.getSeconds());
                                
                                // Convert ANSI codes to HTML and sanitize the message
                                const sanitizedMessage = isDebug ? 
                                    `<span style="color: #888">[${hours}:${minutes}:${seconds} INFO]: ${message}</span>` : 
                                    ansiUp.ansi_to_html(message);
                                
                                logEntry.innerHTML = sanitizedMessage;
                                
                                if (logEntry.textContent.trim() !== "") {
                                    logContainer.appendChild(logEntry);
                                    logContainer.scrollTop = logContainer.scrollHeight;
                                    
                                    // Keep only the last 1000 entries to prevent memory issues
                                    while (logContainer.children.length > 1000) {
                                        logContainer.removeChild(logContainer.firstChild);
                                    }
                                }
                            }

                            function connectWebSocket() {
                                socket = new WebSocket(`ws://${window.location.hostname}:8081`);
                                
                                addLogEntry('Connecting to WebSocket server...', true);

                                socket.onopen = function() {
                                    addLogEntry('Connected to server!', true);
                                    document.getElementById('connectingModal').style.display = 'none';
                                    startHeartbeat();
                                };

                                socket.onmessage = function(event) {
                                    console.log('Received message:', event.data); // Debug log
                                    addLogEntry(event.data);
                                };

                                socket.onerror = function(error) {
                                    console.error('WebSocket error:', error);
                                };

                                socket.onclose = function() {
                                    addLogEntry('Connection lost, attempting to reconnect...', true);
                                    document.getElementById('connectingModal').style.display = 'flex';
                                    setTimeout(connectWebSocket, 5000);
                                };
                            }

                            function sendCommand() {
                                const command = commandInput.value.trim();
                                if (command && socket.readyState === WebSocket.OPEN) {
                                    addLogEntry(`> ${command}`, true); // Log the sent command
                                    socket.send(command);
                                    commandInput.value = '';
                                }
                            }

                            // Enhanced command functions with logging
                            function sendDayCommand() {
                                if (socket.readyState === WebSocket.OPEN) {
                                    addLogEntry('> time set day', true);
                                    socket.send("time set day");
                                }
                            }

                            function sendNightCommand() {
                                if (socket.readyState === WebSocket.OPEN) {
                                    addLogEntry('> time set night', true);
                                    socket.send("time set night");
                                }
                            }

                            function sendWClearCommand() {
                                if (socket.readyState === WebSocket.OPEN) {
                                    addLogEntry('> weather clear', true);
                                    socket.send("weather clear");
                                }
                            }

                            function sendWRainCommand() {
                                if (socket.readyState === WebSocket.OPEN) {
                                    addLogEntry('> weather rain', true);
                                    socket.send("weather rain");
                                }
                            }

                            function sendStopCommand() {
                                closeStopModal();  // Close modal after sending
                                addLogEntry(`> stop`, true); // Log the sent command
                                socket.send("stop");
                            }

                            function sendTellCommand() {
                                const tellMessage = document.getElementById('tellInput').value.trim();
                                if (tellMessage) {
                                    socket.send('tell @a ' + tellMessage);  // Sends a "tell" command
                                    addLogEntry(`> tell @a ${tellMessage}`, true); // Log the sent command
                                    closeTellModal();  // Close modal after sending
                                }
                            }

                            function openTellModal() {
                                const modal = document.getElementById('tellModal');
                                modal.classList.remove('hidden');
                                setTimeout(() => {
                                    modal.classList.add('opacity-100');
                                    modal.querySelector('div').classList.add('scale-100');
                                }, 10);
                            }

                            function closeTellModal() {
                                const modal = document.getElementById('tellModal');
                                modal.classList.remove('opacity-100');
                                modal.querySelector('div').classList.remove('scale-100');
                                setTimeout(() => {
                                    modal.classList.add('hidden');
                                }, 300); // Matches the duration of the transition
                            }

                            function openStopModal() {
                                const modal = document.getElementById('stopModal');
                                modal.classList.remove('hidden');
                                setTimeout(() => {
                                    modal.classList.add('opacity-100');
                                    modal.querySelector('div').classList.add('scale-100');
                                }, 10);
                            }

                            function closeStopModal() {
                                const modal = document.getElementById('stopModal');
                                modal.classList.remove('opacity-100');
                                modal.querySelector('div').classList.remove('scale-100');
                                setTimeout(() => {
                                    modal.classList.add('hidden');
                                }, 300); // Matches the duration of the transition
                            }

                            // Start the connection when the page loads
                            connectWebSocket();

                            // Add command input Enter key handler
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
package cz.zcu.kiv.ups.sp;

import java.io.*;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import cz.zcu.kiv.ups.sp.Logger;

/**
 * Handles TCP connection to the game server.
 */
public class NetworkClient {
    private Socket socket;
    private BufferedReader reader;
    private PrintWriter writer;
    private String serverHost;
    private int serverPort;
    private boolean connected;

    // Heartbeat mechanism for detecting server unavailability
    private volatile boolean heartbeatRunning = false;
    private Thread heartbeatThread;
    private static final int HEARTBEAT_INTERVAL_MS = 5000;   // 5 seconds
    private static final int HEARTBEAT_TIMEOUT_MS = 8000;   // 8 seconds - if no message received, connection is dead
    private volatile long lastMessageReceivedTime = 0;

    /**
     * Creates a new network client
     * @param host server hostname
     * @param port server port
     */
    public NetworkClient(String host, int port) {
        this.serverHost = host;
        this.serverPort = port;
        this.connected = false;
    }

    /**
     * Connects to the server
     * @return true if connection successful, false otherwise
     */
    public boolean connect() {
        try {
            socket = new Socket(serverHost, serverPort);
            socket.setSoTimeout(5000);
            reader = new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)
            );
            writer = new PrintWriter(
                new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8),
                true
            );
            connected = true;
            lastMessageReceivedTime = System.currentTimeMillis();  // Initialize timestamp
            return true;
        } catch (IOException e) {
            Logger.error("Failed to connect to server: " + e.getMessage());
            connected = false;
            return false;
        }
    }

    /**
     * Sends a message to the server
     * @param message message to send
     * @return true if sent successfully
     */
    public boolean send(String message) {
        if (!connected || writer == null) {
            Logger.error("Not connected to server");
            return false;
        }

        try {
            writer.print(message + "\n");
            writer.flush();
            return true;
        } catch (Exception e) {
            Logger.error("Failed to send message: " + e.getMessage());
            connected = false;
            return false;
        }
    }

    /**
     * Receives a message from the server
     * @return received message or null if error
     */
    public synchronized String receive() {
        if (!connected || reader == null) {
            return null;
        }

        try {
            String line = reader.readLine();
            if (line == null) {
                Logger.error("Server closed connection");
                connected = false;
                return null;
            }
            // Update last message received timestamp
            lastMessageReceivedTime = System.currentTimeMillis();
            return line;
        } catch (SocketTimeoutException e) {
            // Timeout is normal - just return null
            return null;
        } catch (IOException e) {
            Logger.error("Network error: " + e.getMessage());
            connected = false;
            return null;
        }
    }

    /**
     * Closes the connection to the server
     */
    public void disconnect() {
        try {
            if (writer != null) {
                writer.close();
            }
            if (reader != null) {
                reader.close();
            }
            if (socket != null) {
                socket.close();
            }
            connected = false;
        } catch (IOException e) {
            Logger.error("Error closing connection: " + e.getMessage());
        }
    }

    /**
     * Starts heartbeat mechanism to detect server unavailability
     * @param onConnectionLost callback to run when connection is lost
     */
    public void startHeartbeat(Runnable onConnectionLost) {
        if (heartbeatRunning) {
            return;
        }

        heartbeatRunning = true;
        heartbeatThread = new Thread(() -> {
            while (heartbeatRunning && connected) {
                try {
                    Thread.sleep(HEARTBEAT_INTERVAL_MS);

                    if (!connected) break;

                    // Send PING to keep connection alive and trigger PONG response
                    send("PING");

                    // Check if we've received ANY message recently
                    long timeSinceLastMessage = System.currentTimeMillis() - lastMessageReceivedTime;

                    if (timeSinceLastMessage > HEARTBEAT_TIMEOUT_MS) {
                        // No message received for too long - connection is dead
                        Logger.error("Heartbeat: No message received for " + timeSinceLastMessage + "ms (timeout: " + HEARTBEAT_TIMEOUT_MS + "ms)");
                        Logger.error("Heartbeat: Connection lost - triggering reconnect");
                        onConnectionLost.run();
                        break;
                    }

                } catch (InterruptedException e) {
                    break;
                }
            }
            heartbeatRunning = false;
        });
        heartbeatThread.setDaemon(true);
        heartbeatThread.start();
    }

    /**
     * Stops the heartbeat mechanism
     */
    public void stopHeartbeat() {
        heartbeatRunning = false;
        if (heartbeatThread != null) {
            heartbeatThread.interrupt();
        }
    }

    public String getServerHost() {
        return serverHost;
    }

    public int getServerPort() {
        return serverPort;
    }
    
    /**
     * Checks if client is connected to server
     * @return true if connected
     */
    public boolean isConnected() {
        return connected && socket != null && !socket.isClosed();
    }
}

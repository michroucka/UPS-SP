package cz.zcu.kiv.ups.sp;

import java.io.*;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

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
            return true;
        } catch (IOException e) {
            System.err.println("Failed to connect to server: " + e.getMessage());
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
            System.err.println("Not connected to server");
            return false;
        }

        try {
            writer.print(message + "\n");
            writer.flush();
            return true;
        } catch (Exception e) {
            System.err.println("Failed to send message: " + e.getMessage());
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
                System.err.println("Server closed connection");
                connected = false;
                return null;
            }
            return line;
        } catch (SocketTimeoutException e) {
            // Timeout is normal - just return null
            return null;
        } catch (IOException e) {
            System.err.println("Network error: " + e.getMessage());
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
            System.err.println("Error closing connection: " + e.getMessage());
        }
    }

    /**
     * Checks if client is connected to server
     * @return true if connected
     */
    public boolean isConnected() {
        return connected && socket != null && !socket.isClosed();
    }
}

package cz.zcu.kiv.ups.sp;

import java.util.ArrayList;
import java.util.List;
import cz.zcu.kiv.ups.sp.Logger;

/**
 * Game client that handles communication with the game server
 * and maintains game state.
 */
public class GameClient {
    private NetworkClient networkClient;
    private String sessionId;
    private String nickname;
    private String currentRoomId;
    private ClientState state;
    private List<String> playerCards;
    private List<String> opponentCards;
    private int opponentCardCount;
    private String opponentNickname;
    private String currentRole; // BANKER or PLAYER
    private int yourScore;
    private int opponentScore;
    private int currentRound;
    private int playerHandValue;      // Hodnota hráčovy ruky (ze serveru)
    private int opponentHandValue;    // Hodnota soupeřovy ruky (ze serveru)

    // Reconnect query info
    private boolean hasPendingReconnectQuery;
    private String reconnectRoomId;
    private String reconnectOpponentNickname;

    /**
     * Client states
     */
    public enum ClientState {
        DISCONNECTED,
        CONNECTED,
        LOBBY,
        IN_ROOM,
        PLAYING
    }

    /**
     * Creates a new game client
     * @param host server host
     * @param port server port
     */
    public GameClient(String host, int port) {
        this.networkClient = new NetworkClient(host, port);
        this.state = ClientState.DISCONNECTED;
        this.playerCards = new ArrayList<>();
        this.opponentCards = new ArrayList<>();
        this.opponentCardCount = 0;
    }

    /**
     * Connects to the server
     * @return true if successful
     */
    public boolean connect() {
        Logger.info("Connecting to server at " + networkClient.getServerHost() + ":" + networkClient.getServerPort());
        if (networkClient.connect()) {
            setState(ClientState.CONNECTED);
            Logger.info("Successfully connected to server.");
            return true;
        }
        Logger.warning("Failed to connect to server.");
        return false;
    }

    /**
     * Logs in with a nickname (new login)
     * @param nickname player nickname
     * @return true if successful
     */
    public boolean login(String nickname) {
        return login(nickname, null);
    }

    /**
     * Logs in with nickname and session ID (reconnect)
     * @param nickname player nickname
     * @param sessionId session ID for reconnect (null for new login)
     * @return true if successful
     */
    public boolean login(String nickname, String sessionId) {
        if (state != ClientState.CONNECTED) {
            Logger.warning("Cannot login, client not connected.");
            return false;
        }

        ProtocolMessage loginMsg;
        if (sessionId != null && !sessionId.isEmpty()) {
            // Reconnect - include session ID
            Logger.info("Attempting to reconnect with nickname: " + nickname + " and session ID: " + sessionId);
            loginMsg = new ProtocolMessage("LOGIN", nickname, sessionId);
        } else {
            // New login
            Logger.info("Attempting to login with nickname: " + nickname);
            loginMsg = ProtocolMessage.login(nickname);
        }

        if (!networkClient.send(loginMsg.toString())) {
            Logger.error("Failed to send login message.");
            return false;
        }

        String response = networkClient.receive();
        if (response == null) {
            Logger.error("No response from server during login.");
            return false;
        }

        ProtocolMessage msg = ProtocolMessage.parse(response);
        if (msg == null) {
            Logger.error("Failed to parse server response during login.");
            return false;
        }

        if (msg.isError()) {
            Logger.error("Login failed: " + msg.getErrorMessage());

            // Clear session ID if expired/invalid
            String errorMsg = msg.getErrorMessage();
            if (errorMsg != null && errorMsg.contains("Session")) {
                this.sessionId = null;
            }
            return false;
        }

        if ("RECONNECT_QUERY".equals(msg.getCommand())) {
            // Server is asking if we want to reconnect to an ongoing game
            if (msg.getParameterCount() >= 2) {
                Logger.info("Received reconnect query for room: " + msg.getParameter(0));
                this.hasPendingReconnectQuery = true;
                this.reconnectRoomId = msg.getParameter(0);
                this.reconnectOpponentNickname = msg.getParameter(1);
                this.nickname = nickname;  // Store nickname for later
                return true;  // Return true - caller should check hasPendingReconnectQuery()
            }
            Logger.warning("Received invalid RECONNECT_QUERY with " + msg.getParameterCount() + " parameters.");
            return false;
        }

        if ("OK".equals(msg.getCommand()) && msg.getParameterCount() > 0) {
            this.sessionId = msg.getParameter(0);
            this.nickname = nickname;
            setState(ClientState.LOBBY);
            this.hasPendingReconnectQuery = false;  // Clear any pending query
            Logger.info("Login successful. Session ID: " + this.sessionId);
            return true;
        }

        Logger.warning("Unknown response from server during login: " + response);
        return false;
    }

    /**
     * Receives and processes a message from the server
     * @return received message or null
     */
    public ProtocolMessage receiveMessage() {
        String response = networkClient.receive();
        if (response == null) {
            return null;
        }

        ProtocolMessage msg = ProtocolMessage.parse(response);
        if (msg == null) {
            return null;
        }

        // Process certain messages automatically
        if ("GAME_STATE".equals(msg.getCommand())) {
            int newRound = Integer.parseInt(msg.getParameter(0));
            yourScore = Integer.parseInt(msg.getParameter(1));
            opponentScore = Integer.parseInt(msg.getParameter(2));
            String newRole = msg.getParameter(3);

            currentRound = newRound;
            currentRole = newRole;
        }

        return msg;
    }

    /**
     * Sends a message without waiting for response
     * @param msg message to send
     * @return true if successful
     */
    public boolean sendMessage(ProtocolMessage msg) {
        return networkClient.send(msg.toString());
    }



    /**
     * Disconnects from server
     */
    public void disconnect() {
        if (state != ClientState.DISCONNECTED) {
            Logger.info("Disconnecting from server.");
            networkClient.send(ProtocolMessage.disconnect().toString());
            networkClient.disconnect();
            setState(ClientState.DISCONNECTED);
        }
    }

    // Getters

    public ClientState getState() {
        return state;
    }

    public String getSessionId() {
        return sessionId;
    }

    public NetworkClient getNetworkClient() {
        return networkClient;
    }

    public List<String> getPlayerCards() {
        return new ArrayList<>(playerCards);
    }

    public void clearPlayerCards() {
        playerCards.clear();
    }

    public void addPlayerCard(String card) {
        playerCards.add(card);
    }

    public List<String> getOpponentCards() {
        return new ArrayList<>(opponentCards);
    }

    public void setOpponentCards(List<String> cards) {
        this.opponentCards = new ArrayList<>(cards);
    }

    public void clearOpponentCards() {
        opponentCards.clear();
    }

    public int getOpponentCardCount() {
        return opponentCardCount;
    }

    public void setOpponentCardCount(int count) {
        this.opponentCardCount = count;
    }

    public String getOpponentNickname() {
        return opponentNickname;
    }

    public String getCurrentRole() {
        return currentRole;
    }

    public int getYourScore() {
        return yourScore;
    }

    public int getOpponentScore() {
        return opponentScore;
    }

    public int getCurrentRound() {
        return currentRound;
    }

    public void setPlayerHandValue(int value) {
        this.playerHandValue = value;
    }

    public void setOpponentHandValue(int value) {
        this.opponentHandValue = value;
    }

    public boolean isConnected() {
        return networkClient.isConnected();
    }

    public void setState(ClientState newState) {
        if (this.state != newState) {
            Logger.info("Client state changed from " + this.state + " to " + newState);
            this.state = newState;
        }
    }

    public void setCurrentRoomId(String roomId) {
        this.currentRoomId = roomId;
    }

    public void setOpponentNickname(String opponentNickname) {
        this.opponentNickname = opponentNickname;
    }

    public void setCurrentRole(String role) {
        this.currentRole = role;
    }

    /**
     * Resets game state (cards, scores, round) for a new game
     */
    public void resetGameState() {
        this.playerCards.clear();
        this.opponentNickname = null;
        this.currentRole = null;
        this.yourScore = 0;
        this.opponentScore = 0;
        this.currentRound = 0;
        this.currentRoomId = null;
        this.playerHandValue = 0;
        this.opponentHandValue = 0;
    }

    // Reconnect query methods

    /**
     * Checks if there is a pending reconnect query from the server
     * @return true if server asked whether to reconnect
     */
    public boolean hasPendingReconnectQuery() {
        return hasPendingReconnectQuery;
    }

    /**
     * Gets the room ID for pending reconnect
     * @return room ID or null
     */
    public String getReconnectRoomId() {
        return reconnectRoomId;
    }

    /**
     * Gets the opponent nickname for pending reconnect
     * @return opponent nickname or null
     */
    public String getReconnectOpponentNickname() {
        return reconnectOpponentNickname;
    }

    /**
     * Accepts the reconnect query and rejoins the game
     * @return true if successful
     */
    public boolean acceptReconnect() {
        if (!hasPendingReconnectQuery) {
            Logger.warning("acceptReconnect called with no pending query.");
            return false;
        }

        Logger.info("Accepting reconnect query.");
        // Send RECONNECT_ACCEPT
        if (!networkClient.send(ProtocolMessage.reconnectAccept().toString())) {
            Logger.error("Failed to send RECONNECT_ACCEPT.");
            return false;
        }

        // Wait for OK response with session ID
        String response = networkClient.receive();
        if (response == null) {
            Logger.error("No response from server after accepting reconnect.");
            return false;
        }

        ProtocolMessage msg = ProtocolMessage.parse(response);
        if (msg == null) {
            Logger.error("Failed to parse server response after accepting reconnect.");
            return false;
        }

        if (msg.isError()) {
            Logger.error("Reconnect accept failed: " + msg.getErrorMessage());
            hasPendingReconnectQuery = false;
            return false;
        }

        if ("OK".equals(msg.getCommand()) && msg.getParameterCount() > 0) {
            this.sessionId = msg.getParameter(0);
            setState(ClientState.PLAYING);  // Reconnecting to game
            this.hasPendingReconnectQuery = false;
            Logger.info("Successfully reconnected. New session ID: " + this.sessionId);
            return true;
        }

        Logger.warning("Unknown response from server after accepting reconnect: " + response);
        return false;
    }

    /**
     * Declines the reconnect query and starts fresh in lobby
     * @return true if successful
     */
    public boolean declineReconnect() {
        if (!hasPendingReconnectQuery) {
            Logger.warning("declineReconnect called with no pending query.");
            return false;
        }

        Logger.info("Declining reconnect query.");
        // Send RECONNECT_DECLINE
        if (!networkClient.send(ProtocolMessage.reconnectDecline().toString())) {
            Logger.error("Failed to send RECONNECT_DECLINE.");
            return false;
        }

        // Wait for OK response with new session ID
        String response = networkClient.receive();
        if (response == null) {
            Logger.error("No response from server after declining reconnect.");
            return false;
        }

        ProtocolMessage msg = ProtocolMessage.parse(response);
        if (msg == null) {
            Logger.error("Failed to parse server response after declining reconnect.");
            return false;
        }

        if (msg.isError()) {
            Logger.error("Reconnect decline failed: " + msg.getErrorMessage());
            hasPendingReconnectQuery = false;
            return false;
        }

        if ("OK".equals(msg.getCommand()) && msg.getParameterCount() > 0) {
            this.sessionId = msg.getParameter(0);
            setState(ClientState.LOBBY);
            this.hasPendingReconnectQuery = false;
            Logger.info("Successfully declined reconnect. New session ID: " + this.sessionId);
            return true;
        }

        Logger.warning("Unknown response from server after declining reconnect: " + response);
        return false;
    }
}

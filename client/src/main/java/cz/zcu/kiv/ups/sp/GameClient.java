package cz.zcu.kiv.ups.sp;

import java.util.ArrayList;
import java.util.List;

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
        if (networkClient.connect()) {
            state = ClientState.CONNECTED;
            return true;
        }
        return false;
    }

    /**
     * Logs in with a nickname
     * @param nickname player nickname
     * @return true if successful
     */
    public boolean login(String nickname) {
        if (state != ClientState.CONNECTED) {
            return false;
        }

        ProtocolMessage loginMsg = ProtocolMessage.login(nickname);
        if (!networkClient.send(loginMsg.toString())) {
            return false;
        }

        String response = networkClient.receive();
        if (response == null) {
            return false;
        }

        ProtocolMessage msg = ProtocolMessage.parse(response);
        if (msg == null) {
            return false;
        }

        if (msg.isError()) {
            System.err.println("Login failed: " + msg.getErrorMessage());
            return false;
        }

        if ("OK".equals(msg.getCommand()) && msg.getParameterCount() > 0) {
            this.sessionId = msg.getParameter(0);
            this.nickname = nickname;
            this.state = ClientState.LOBBY;
            return true;
        }

        return false;
    }

    /**
     * Lists available rooms
     * @return list of room info strings
     * @deprecated Use queue pattern from GameController instead
     */
    @Deprecated
    public List<String> listRooms() {
        if (state != ClientState.LOBBY) {
            return null;
        }

        if (!networkClient.send(ProtocolMessage.roomList().toString())) {
            return null;
        }

        String response = networkClient.receive();
        if (response == null) {
            return null;
        }

        ProtocolMessage msg = ProtocolMessage.parse(response);
        if (msg == null || !"ROOMS".equals(msg.getCommand())) {
            return null;
        }

        int roomCount = 0;
        try {
            roomCount = Integer.parseInt(msg.getParameter(0));
        } catch (NumberFormatException e) {
            return null;
        }

        List<String> rooms = new ArrayList<>();
        for (int i = 0; i < roomCount; i++) {
            response = networkClient.receive();
            if (response != null) {
                rooms.add(response);
            }
        }

        return rooms;
    }

    /**
     * Creates a new room
     * @param roomName room name
     * @return room ID or null if failed
     */
    /**
     * @deprecated Use queue pattern from GameController instead
     */
    @Deprecated
    public String createRoom(String roomName) {
        if (state != ClientState.LOBBY) {
            return null;
        }

        if (!networkClient.send(ProtocolMessage.createRoom(roomName).toString())) {
            return null;
        }

        String response = networkClient.receive();
        if (response == null) {
            return null;
        }

        ProtocolMessage msg = ProtocolMessage.parse(response);
        if (msg == null || msg.isError()) {
            return null;
        }

        if ("ROOM_CREATED".equals(msg.getCommand()) && msg.getParameterCount() > 0) {
            currentRoomId = msg.getParameter(0);
            state = ClientState.IN_ROOM;
            return currentRoomId;
        }

        return null;
    }

    /**
     * Joins an existing room
     * @param roomId room ID
     * @return true if successful
     */
    /**
     * @deprecated Use queue pattern from GameController instead
     */
    @Deprecated
    public boolean joinRoom(String roomId) {
        if (state != ClientState.LOBBY) {
            return false;
        }

        if (!networkClient.send(ProtocolMessage.joinRoom(roomId).toString())) {
            return false;
        }

        String response = networkClient.receive();
        if (response == null) {
            return false;
        }

        ProtocolMessage msg = ProtocolMessage.parse(response);
        if (msg == null || msg.isError()) {
            return false;
        }

        if ("JOINED".equals(msg.getCommand())) {
            currentRoomId = roomId;
            state = ClientState.IN_ROOM;
            return true;
        }

        return false;
    }

    /**
     * Waits for game to start and processes initial game messages
     * @return true if game started successfully
     * @deprecated Use queue pattern from GameController instead
     */
    @Deprecated
    public boolean waitForGameStart() {
        while (state == ClientState.IN_ROOM) {
            String response = networkClient.receive();
            if (response == null) {
                return false;
            }

            ProtocolMessage msg = ProtocolMessage.parse(response);
            if (msg == null) {
                continue;
            }

            if ("GAME_START".equals(msg.getCommand())) {
                currentRole = msg.getParameter(0);
                opponentNickname = msg.getParameter(1);
                state = ClientState.PLAYING;
                return true;
            }
        }
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
     * Sends a HIT request
     * @return true if successful
     */
    public boolean hit() {
        return networkClient.send(ProtocolMessage.hit().toString());
    }

    /**
     * Sends a STAND request
     * @return true if successful
     */
    public boolean stand() {
        return networkClient.send(ProtocolMessage.stand().toString());
    }

    /**
     * Plays a card
     * @param card card to play
     * @return true if successful
     */
    public boolean playCard(String card) {
        return networkClient.send(ProtocolMessage.playCard(card).toString());
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
     * Leaves current room
     * @return true if successful
     * @deprecated Use queue pattern from GameController instead
     */
    @Deprecated
    public boolean leaveRoom() {
        if (state != ClientState.IN_ROOM && state != ClientState.PLAYING) {
            return false;
        }

        if (!networkClient.send(ProtocolMessage.leaveRoom().toString())) {
            return false;
        }

        String response = networkClient.receive();
        if (response != null) {
            ProtocolMessage msg = ProtocolMessage.parse(response);
            if (msg != null && "OK".equals(msg.getCommand())) {
                state = ClientState.LOBBY;
                currentRoomId = null;
                return true;
            }
        }
        return false;
    }

    /**
     * Disconnects from server
     */
    public void disconnect() {
        if (state != ClientState.DISCONNECTED) {
            networkClient.send(ProtocolMessage.disconnect().toString());
            networkClient.disconnect();
            state = ClientState.DISCONNECTED;
        }
    }

    // Getters

    public ClientState getState() {
        return state;
    }

    public String getNickname() {
        return nickname;
    }

    public String getSessionId() {
        return sessionId;
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

    public String getCurrentRoomId() {
        return currentRoomId;
    }

    public boolean isConnected() {
        return networkClient.isConnected();
    }

    public void setState(ClientState newState) {
        this.state = newState;
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
    }
}

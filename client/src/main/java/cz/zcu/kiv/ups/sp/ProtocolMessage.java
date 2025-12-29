package cz.zcu.kiv.ups.sp;

/**
 * Represents a protocol message according to the game protocol.
 * Format: COMMAND|param1|param2|...
 */
public class ProtocolMessage {
    // VALIDATION: Maximum message size to prevent buffer overflow
    private static final int MAX_MESSAGE_SIZE = 4096;
    private static final int MAX_PARAMETERS = 100;  // Reasonable limit for parameters

    private String command;
    private String[] parameters;

    /**
     * Creates a protocol message
     * @param command command name
     * @param parameters command parameters
     */
    public ProtocolMessage(String command, String... parameters) {
        this.command = command;
        this.parameters = parameters;
    }

    /**
     * Parses a message string into a ProtocolMessage
     * @param message message string
     * @return parsed ProtocolMessage or null if invalid
     */
    public static ProtocolMessage parse(String message) {
        if (message == null || message.isEmpty()) {
            return null;
        }

        // VALIDATION: Check message size to prevent buffer overflow
        if (message.length() > MAX_MESSAGE_SIZE) {
            System.err.println("Message too large: " + message.length() + " bytes (max " + MAX_MESSAGE_SIZE + ")");
            return null;
        }

        String[] parts = message.split("\\|");
        if (parts.length == 0) {
            return null;
        }

        // VALIDATION: Check parameter count
        if (parts.length > MAX_PARAMETERS + 1) {  // +1 for command
            System.err.println("Too many parameters: " + (parts.length - 1) + " (max " + MAX_PARAMETERS + ")");
            return null;
        }

        String command = parts[0];
        String[] parameters = new String[parts.length - 1];
        System.arraycopy(parts, 1, parameters, 0, parameters.length);

        return new ProtocolMessage(command, parameters);
    }

    /**
     * Gets the command name
     * @return command name
     */
    public String getCommand() {
        return command;
    }

    /**
     * Gets all parameters
     * @return array of parameters
     */
    public String[] getParameters() {
        return parameters;
    }

    /**
     * Gets parameter at specific index
     * @param index parameter index
     * @return parameter value or null if index out of bounds
     */
    public String getParameter(int index) {
        if (index >= 0 && index < parameters.length) {
            return parameters[index];
        }
        return null;
    }

    /**
     * Gets number of parameters
     * @return parameter count
     */
    public int getParameterCount() {
        return parameters.length;
    }

    /**
     * Checks if message is an error
     * @return true if command is ERROR
     */
    public boolean isError() {
        return "ERROR".equals(command);
    }

    /**
     * Gets error message if this is an error message
     * @return error message or null
     */
    public String getErrorMessage() {
        if (isError() && parameters.length > 0) {
            return parameters[0];
        }
        return null;
    }

    /**
     * Converts message to protocol string format
     * @return formatted message string
     */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(command);
        for (String param : parameters) {
            sb.append('|').append(param);
        }
        return sb.toString();
    }

    // Factory methods for common messages

    /**
     * Creates a LOGIN message
     * @param nickname player nickname
     * @return LOGIN message
     */
    public static ProtocolMessage login(String nickname) {
        return new ProtocolMessage("LOGIN", nickname);
    }

    /**
     * Creates a PING message
     * @return PING message
     */
    public static ProtocolMessage ping() {
        return new ProtocolMessage("PING");
    }

    /**
     * Creates a DISCONNECT message
     * @return DISCONNECT message
     */
    public static ProtocolMessage disconnect() {
        return new ProtocolMessage("DISCONNECT");
    }

    /**
     * Creates a ROOM_LIST message
     * @return ROOM_LIST message
     */
    public static ProtocolMessage roomList() {
        return new ProtocolMessage("ROOM_LIST");
    }

    /**
     * Creates a CREATE_ROOM message
     * @param roomName room name
     * @return CREATE_ROOM message
     */
    public static ProtocolMessage createRoom(String roomName) {
        return new ProtocolMessage("CREATE_ROOM", roomName);
    }

    /**
     * Creates a JOIN_ROOM message
     * @param roomId room ID
     * @return JOIN_ROOM message
     */
    public static ProtocolMessage joinRoom(String roomId) {
        return new ProtocolMessage("JOIN_ROOM", roomId);
    }

    /**
     * Creates a LEAVE_ROOM message
     * @return LEAVE_ROOM message
     */
    public static ProtocolMessage leaveRoom() {
        return new ProtocolMessage("LEAVE_ROOM");
    }

    /**
     * Creates a PLAY_CARD message
     * @param card card to play
     * @return PLAY_CARD message
     */
    public static ProtocolMessage playCard(String card) {
        return new ProtocolMessage("PLAY_CARD", card);
    }

    /**
     * Creates a HIT message
     * @return HIT message
     */
    public static ProtocolMessage hit() {
        return new ProtocolMessage("HIT");
    }

    /**
     * Creates a STAND message
     * @return STAND message
     */
    public static ProtocolMessage stand() {
        return new ProtocolMessage("STAND");
    }

    /**
     * Creates a RECONNECT message
     * @param sessionId session ID
     * @return RECONNECT message
     */
    public static ProtocolMessage reconnect(String sessionId) {
        return new ProtocolMessage("RECONNECT", sessionId);
    }

    /**
     * Creates an ACK_DEAL_CARDS message
     * @return ACK_DEAL_CARDS message
     */
    public static ProtocolMessage ackDealCards() {
        return new ProtocolMessage("ACK_DEAL_CARDS");
    }

    /**
     * Creates an ACK_ROUND_END message
     * @return ACK_ROUND_END message
     */
    public static ProtocolMessage ackRoundEnd() {
        return new ProtocolMessage("ACK_ROUND_END");
    }

    /**
     * Creates an ACK_GAME_END message
     * @return ACK_GAME_END message
     */
    public static ProtocolMessage ackGameEnd() {
        return new ProtocolMessage("ACK_GAME_END");
    }

    /**
     * Creates an ACK_GAME_STATE message
     * @return ACK_GAME_STATE message
     */
    public static ProtocolMessage ackGameState() {
        return new ProtocolMessage("ACK_GAME_STATE");
    }
}

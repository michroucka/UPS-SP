package cz.zcu.kiv.ups.sp;

/**
 * Reprezentace místnosti pro zobrazení v tabulce
 */
public class RoomInfo {
    private final String id;
    private final String name;
    private final int playerCount;
    private final int maxPlayers;
    private final String status;

    public RoomInfo(String id, String name, int playerCount, int maxPlayers, String status) {
        this.id = id;
        this.name = name;
        this.playerCount = playerCount;
        this.maxPlayers = maxPlayers;
        this.status = status;
    }

    /**
     * Parsuje room string z protokolu do RoomInfo objektu
     * Format: ROOM|id|name|playerCount|maxPlayers|status
     */
    public static RoomInfo parse(String roomString) {
        String[] parts = roomString.split("\\|");
        if (parts.length < 6 || !"ROOM".equals(parts[0])) {
            return null;
        }

        try {
            String id = parts[1];
            String name = parts[2];
            int playerCount = Integer.parseInt(parts[3]);
            int maxPlayers = Integer.parseInt(parts[4]);
            String status = parts[5];

            return new RoomInfo(id, name, playerCount, maxPlayers, status);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public int getPlayerCount() {
        return playerCount;
    }

    public int getMaxPlayers() {
        return maxPlayers;
    }

    public String getStatus() {
        return status;
    }

    public String getPlayersDisplay() {
        return playerCount + "/" + maxPlayers;
    }

    public boolean canJoin() {
        return "WAITING".equals(status) && playerCount < maxPlayers;
    }
}

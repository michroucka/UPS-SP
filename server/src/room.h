#ifndef ROOM_H
#define ROOM_H

#include <string>
#include <vector>
#include <mutex>
#include "client.h"
#include "game.h"

/**
 * Room state.
 */
enum RoomState {
    ROOM_WAITING,
    ROOM_PLAYING,
    ROOM_FINISHED
};

/**
 * Game room for 2 players.
 */
class Room {
public:
    /**
     * Room constructor.
     * @param id Unique room ID
     * @param name Room name
     * @param creator Client who created the room
     */
    Room(int id, const std::string& name, Client* creator);

    /**
     * Destructor - releases game.
     */
    ~Room();

    /** Returns room ID */
    int getId() const { return id; }

    /** Returns room name */
    std::string getName() const { return name; }

    /** Returns room state */
    RoomState getState() const { return state; }

    /** Returns number of players in room */
    int getPlayerCount() const { return players.size(); }

    /** Returns maximum number of players (2) */
    int getMaxPlayers() const { return MAX_PLAYERS; }

    /**
     * Checks if room is full.
     * @return true if 2 players, false otherwise
     */
    bool isFull() const { return players.size() >= MAX_PLAYERS; }

    /**
     * Checks if room contains given player.
     * @param client Client to verify
     * @return true if player is in room, false otherwise
     */
    bool hasPlayer(Client* client) const;

    /**
     * Adds player to room. If room is full, starts game.
     * @param client Client to add
     * @return true if added, false on error
     */
    bool addPlayer(Client* client);

    /**
     * Removes player from room.
     * @param client Client to remove
     * @param isDisconnect true on disconnect (game waits for reconnect), false on intentional leave
     */
    void removePlayer(Client* client, bool isDisconnect = false);

    /**
     * Reconnects disconnected player (after reconnect).
     * @param client Client to reconnect
     */
    void reconnectPlayer(Client* client);

    /**
     * Starts game with 2 players.
     */
    void startGame();

    /**
     * Returns pointer to game.
     * @return Pointer to game or nullptr
     */
    Game* getGame() { return game; }

    /**
     * Checks game end and returns players to lobby.
     */
    void checkAndHandleGameEnd();

    /**
     * Resets game and returns room to WAITING state.
     */
    void resetGame();

    std::string getStateString() const {
        switch (state) {
            case ROOM_WAITING: return "WAITING";
            case ROOM_PLAYING: return "PLAYING";
            case ROOM_FINISHED: return "FINISHED";
            default: return "UNKNOWN";
        }
    }

    std::string toProtocolString() const {
        return "ROOM|" + std::to_string(id) + "|" + name + "|" +
               std::to_string(getPlayerCount()) + "|" + std::to_string(MAX_PLAYERS) + "|" +
               getStateString();
    }

private:
    static const int MAX_PLAYERS = 2;

    int id;
    std::string name;
    RoomState state;
    std::vector<Client*> players;
    Game* game;
    mutable std::mutex roomMutex;  // For thread safety
};

#endif // ROOM_H

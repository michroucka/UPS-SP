#ifndef ROOM_H
#define ROOM_H

#include <string>
#include <vector>
#include <mutex>
#include "client.h"
#include "game.h"

/**
 * Stav místnosti.
 */
enum RoomState {
    ROOM_WAITING,
    ROOM_PLAYING,
    ROOM_FINISHED
};

/**
 * Herní místnost pro 2 hráče.
 */
class Room {
public:
    Room(int id, const std::string& name, Client* creator);
    ~Room();

    int getId() const { return id; }
    std::string getName() const { return name; }
    RoomState getState() const { return state; }
    int getPlayerCount() const { return players.size(); }
    int getMaxPlayers() const { return MAX_PLAYERS; }

    bool isFull() const { return players.size() >= MAX_PLAYERS; }
    bool hasPlayer(Client* client) const;

    bool addPlayer(Client* client);
    void removePlayer(Client* client, bool isDisconnect = false);
    void reconnectPlayer(Client* client);  // Add player back after disconnect

    void startGame();
    Game* getGame() { return game; }
    void checkAndHandleGameEnd();

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

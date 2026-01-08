#include "room.h"
#include "logger.h"
#include "protocol.h"

Room::Room(int id, const std::string& name, Client* creator)
    : id(id), name(name), state(ROOM_WAITING), game(nullptr) {

    addPlayer(creator);
    LOG_INFO("Room " + std::to_string(id) + " '" + name + "' created by player " + creator->getNickname());
}

Room::~Room() {
    if (game) {
        delete game;
    }
}

bool Room::hasPlayer(Client* client) const {
    for (Client* player : players) {
        if (player == client) {
            return true;
        }
    }
    return false;
}

bool Room::addPlayer(Client* client) {
    std::lock_guard<std::mutex> lock(roomMutex);  // Thread safety

    if (isFull()) {
        LOG_WARNING("Attempt to join full room " + std::to_string(id));
        return false;
    }

    if (state != ROOM_WAITING) {
        LOG_WARNING("Attempt to join room " + std::to_string(id) + " in state " + getStateString());
        return false;
    }

    // VALIDATION: Check for duplicate player
    if (hasPlayer(client)) {
        LOG_WARNING("Attempt for duplicate join " + client->getNickname() + " to room " + std::to_string(id));
        return false;
    }

    players.push_back(client);
    client->setRoomId(id);
    client->setState(Protocol::IN_ROOM);

    // If room is full, start the game
    if (isFull()) {
        startGame();
    }

    return true;
}

void Room::removePlayer(Client* client, bool isDisconnect) {
    std::lock_guard<std::mutex> lock(roomMutex);  // Thread safety

    std::string leavingPlayerName = client->getNickname();

    // If game is running, inform other players
    if (state == ROOM_PLAYING) {
        if (isDisconnect) {
            LOG_INFO("Player " + leavingPlayerName + " disconnected from ongoing game in room " + std::to_string(id) + " - game waiting for reconnect");

            // Inform other players about disconnection (game continues, waiting for reconnect)
            for (Client* player : players) {
                if (player != client) {
                    player->queueMessage(Protocol::buildMessage({
                        Protocol::CMD_PLAYER_DISCONNECTED,
                        leavingPlayerName
                    }));
                }
            }
        } else {
            LOG_INFO("Player " + leavingPlayerName + " left active game in room " + std::to_string(id));

            // Inform other players
            for (Client* player : players) {
                if (player != client) {
                    player->queueMessage(Protocol::buildMessage({
                        Protocol::CMD_PLAYER_DISCONNECTED,
                        leavingPlayerName
                    }));
                    // Return remaining players back to IN_ROOM state (waiting for another player)
                    player->setState(Protocol::IN_ROOM);
                }
            }

            // End game (intentional leave)
            if (game) {
                delete game;
                game = nullptr;
            }

            // Change room state back to WAITING
            state = ROOM_WAITING;
        }
    }

    // Remove the leaving player from the room
    for (auto it = players.begin(); it != players.end(); ++it) {
        if (*it == client) {
            players.erase(it);
            client->setRoomId(-1);
            client->setState(Protocol::LOBBY);
            break;
        }
    }
}

void Room::reconnectPlayer(Client* client) {
    // Add player back to room after reconnect
    players.push_back(client);
    client->setRoomId(id);
    client->setState(Protocol::PLAYING);

    LOG_INFO("Player " + client->getNickname() + " reconnected to room " + std::to_string(id));

    // Notify other players about reconnect
    // (They will see game resume when reconnected player takes action)
}

void Room::startGame() {
    if (players.size() < MAX_PLAYERS) {
        LOG_WARNING("Unable to start game in room " + std::to_string(id) + " - not enough players");
        return;
    }

    LOG_INFO("Starting game in room " + std::to_string(id));

    state = ROOM_PLAYING;
    game = new Game(id);

    for (Client* player : players) {
        player->setState(Protocol::PLAYING);
        game->addPlayer(player);
    }

    game->start();
}

void Room::resetGame() {
    std::lock_guard<std::mutex> lock(roomMutex);

    if (game) {
        delete game;
        game = nullptr;
    }

    state = ROOM_WAITING;
}

void Room::checkAndHandleGameEnd() {
    if (!game) {
        return;
    }

    if (game->isGameOver()) {
        LOG_INFO("Game in room " + std::to_string(id) + " ended");

        // Set room state to FINISHED
        state = ROOM_FINISHED;

        // Remove players from room and return them to lobby
        for (Client* player : players) {
            player->setRoomId(-1);
            player->setState(Protocol::LOBBY);
        }

        // Clear player list
        players.clear();
    }
}

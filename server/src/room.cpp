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

    LOG_INFO("Player " + client->getNickname() + " added to room " + std::to_string(id));

    // Pokud je místnost plná, spustit hru
    if (isFull()) {
        startGame();
    }

    return true;
}

void Room::removePlayer(Client* client, bool isDisconnect) {
    std::lock_guard<std::mutex> lock(roomMutex);  // Thread safety

    std::string leavingPlayerName = client->getNickname();

    // Pokud hra běží, informovat ostatní hráče
    if (state == ROOM_PLAYING) {
        if (isDisconnect) {
            LOG_INFO("Player " + leavingPlayerName + " disconnected from ongoing game in room " + std::to_string(id) + " - game waiting for reconnect");

            // Informovat ostatní hráče o odpojení (hra pokračuje, čeká na reconnect)
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

            // Informovat ostatní hráče
            for (Client* player : players) {
                if (player != client) {
                    player->queueMessage(Protocol::buildMessage({
                        Protocol::CMD_PLAYER_DISCONNECTED,
                        leavingPlayerName
                    }));
                    // Vrátit zbývající hráče zpět do stavu IN_ROOM (čekání na dalšího hráče)
                    player->setState(Protocol::IN_ROOM);
                }
            }

            // Ukončit hru (úmyslné opuštění)
            if (game) {
                delete game;
                game = nullptr;
            }

            // Změnit stav místnosti zpět na WAITING
            state = ROOM_WAITING;
            LOG_INFO("Room " + std::to_string(id) + " returned to state WAITING after player left during ongoing game");
        }
    }

    // Odebrat opouštějícího hráče z místnosti
    for (auto it = players.begin(); it != players.end(); ++it) {
        if (*it == client) {
            players.erase(it);
            client->setRoomId(-1);
            client->setState(Protocol::LOBBY);
            LOG_INFO("Player " + leavingPlayerName + " removed from room " + std::to_string(id));
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
    LOG_INFO("Other players will be informed of reconnect of player " + client->getNickname());
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
    LOG_INFO("Room " + std::to_string(id) + " game reset, state set to WAITING");
}

void Room::checkAndHandleGameEnd() {
    if (!game) {
        return;
    }

    if (game->isGameOver()) {
        LOG_INFO("Game in room " + std::to_string(id) + " ended");

        // Nastavit stav místnosti na FINISHED
        state = ROOM_FINISHED;

        // Odebrat hráče z místnosti a vrátit je do lobby
        for (Client* player : players) {
            player->setRoomId(-1);
            player->setState(Protocol::LOBBY);
            LOG_INFO("Player " + player->getNickname() + " returned to lobby after game ended");
        }

        // Vyčistit seznam hráčů
        players.clear();

        LOG_INFO("Room " + std::to_string(id) + " ready for deletion");
    }
}

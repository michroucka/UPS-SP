#include "server.h"
#include "logger.h"
#include "protocol.h"
#include <sys/socket.h>
#include <sys/select.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <unistd.h>
#include <fcntl.h>
#include <cstring>
#include <algorithm>

Server::Server(const std::string& address, int port, int max_clients, int max_rooms)
    : address(address), port(port), serverSocket(-1), running(false), nextRoomId(1), MAX_CLIENTS(max_clients), MAX_ROOMS(max_rooms) {
}

Server::~Server() {
    shutdown();
}

/**
 * Server initialization - socket creation, bind, listen.
 */
bool Server::initialize() {
    LOG_INFO("Initializing server on " + address + ":" + std::to_string(port));

    if (!createSocket()) {
        return false;
    }

    if (!bindSocket()) {
        return false;
    }

    if (!listenSocket()) {
        return false;
    }

    LOG_INFO("Server initialized successfully");
    return true;
}

/**
 * Creates a TCP socket.
 */
bool Server::createSocket() {
    serverSocket = socket(AF_INET, SOCK_STREAM, 0);
    if (serverSocket < 0) {
        LOG_ERROR("Unable to create socket: " + std::string(strerror(errno)));
        return false;
    }

    // Set SO_REUSEADDR for immediate restart capability
    int opt = 1;
    if (setsockopt(serverSocket, SOL_SOCKET, SO_REUSEADDR, &opt, sizeof(opt)) < 0) {
        LOG_WARNING("Unable to set SO_REUSEADDR: " + std::string(strerror(errno)));
    }

    return true;
}

/**
 * Binds socket to address and port.
 */
bool Server::bindSocket() {
    struct sockaddr_in serverAddr;
    memset(&serverAddr, 0, sizeof(serverAddr));
    serverAddr.sin_family = AF_INET;
    serverAddr.sin_port = htons(port);

    if (inet_pton(AF_INET, address.c_str(), &serverAddr.sin_addr) <= 0) {
        LOG_ERROR("Invalid IP address: " + address);
        return false;
    }

    if (bind(serverSocket, (struct sockaddr*)&serverAddr, sizeof(serverAddr)) < 0) {
        LOG_ERROR("Unable to bind socket: " + std::string(strerror(errno)));
        return false;
    }

    return true;
}

/**
 * Sets socket to listening mode.
 */
bool Server::listenSocket() {
    if (listen(serverSocket, 10) < 0) {
        LOG_ERROR("Unable to set socket to listening mode: " + std::string(strerror(errno)));
        return false;
    }

    return true;
}

/**
 * Main server loop - uses select() for parallel handling.
 */
void Server::run() {
    running = true;
    LOG_INFO("Server is running and waiting for connection");

    while (running) {
        fd_set readfds, writefds;
        FD_ZERO(&readfds);
        FD_ZERO(&writefds);

        // Add server socket
        FD_SET(serverSocket, &readfds);
        int maxfd = serverSocket;

        // Add all client sockets
        for (const auto& pair : clients) {
            int clientSocket = pair.first;
            Client* client = pair.second.get();

            FD_SET(clientSocket, &readfds);
            if (client->hasMessagesToSend()) {
                FD_SET(clientSocket, &writefds);
            }

            if (clientSocket > maxfd) {
                maxfd = clientSocket;
            }
        }

        // Select with 1 second timeout
        struct timeval timeout;
        timeout.tv_sec = 1;
        timeout.tv_usec = 0;

        int activity = select(maxfd + 1, &readfds, &writefds, nullptr, &timeout);

        if (activity < 0) {
            if (errno == EINTR) {
                continue;  // Interrupted by signal, continue
            }
            LOG_ERROR("Error select(): " + std::string(strerror(errno)));
            break;
        }

        // New connection
        if (FD_ISSET(serverSocket, &readfds)) {
            acceptNewClient();
        }

        // Process clients (must copy keys because we may delete)
        std::vector<int> clientSockets;
        for (const auto& pair : clients) {
            clientSockets.push_back(pair.first);
        }

        for (int clientSocket : clientSockets) {
            // Client may have been disconnected in the meantime
            if (clients.find(clientSocket) == clients.end()) {
                continue;
            }

            Client* client = clients[clientSocket].get();

            // Read data
            if (FD_ISSET(clientSocket, &readfds)) {
                handleClientData(client);
            }

            // Write data
            if (clients.find(clientSocket) != clients.end() &&
                FD_ISSET(clientSocket, &writefds)) {
                std::string message = client->getNextMessageToSend();
                if (!message.empty()) {
                    sendToClient(client, message);
                }
            }
        }

        // Cleanup timed out clients
        cleanupTimedOutClients();

        // Cleanup timed out disconnected players (5 minute timeout)
        cleanupTimedOutDisconnectedPlayers();
    }

    LOG_INFO("Server terminated");
}

/**
 * Accepts a new client.
 */
void Server::acceptNewClient() {
    struct sockaddr_in clientAddr;
    socklen_t clientLen = sizeof(clientAddr);

    int clientSocket = accept(serverSocket, (struct sockaddr*)&clientAddr, &clientLen);
    if (clientSocket < 0) {
        LOG_ERROR("Error while accept(): " + std::string(strerror(errno)));
        return;
    }

    // Check client limit
    if (clients.size() >= static_cast<size_t>(MAX_CLIENTS)) {
        LOG_WARNING("Connection refused - client limit reached");
        close(clientSocket);
        return;
    }

    // Get client address
    char clientIp[INET_ADDRSTRLEN];
    inet_ntop(AF_INET, &clientAddr.sin_addr, clientIp, INET_ADDRSTRLEN);
    std::string clientAddress = std::string(clientIp) + ":" + std::to_string(ntohs(clientAddr.sin_port));

    // Create client
    auto client = std::make_unique<Client>(clientSocket, clientAddress);
    clients[clientSocket] = std::move(client);

    LOG_INFO("New client connected: " + clientAddress + " (fd: " + std::to_string(clientSocket) + ")");
}

/**
 * Processes data from client.
 */
void Server::handleClientData(Client* client) {
    char buffer[1024];
    int bytesRead = recv(client->getSocket(), buffer, sizeof(buffer) - 1, 0);

    if (bytesRead <= 0) {
        // Client disconnected
        std::string reason = (bytesRead == 0) ? "Client ended connection" : "Read error";
        disconnectClient(client, reason);
        return;
    }

    buffer[bytesRead] = '\0';

    // VALIDATION: Catch buffer overflow exceptions
    try {
        client->appendToReadBuffer(std::string(buffer));
    } catch (const std::runtime_error& e) {
        LOG_WARNING("Buffer overflow from client " + client->getAddress() + ": " + e.what());
        disconnectClient(client, "Message too large");
        return;
    }

    client->updateLastActivity();

    // Process all complete messages
    while (client->hasCompleteMessage()) {
        std::string message = client->extractMessage();
        processMessage(client, message);

        // Client may have been disconnected during processing
        if (clients.find(client->getSocket()) == clients.end()) {
            break;
        }
    }
}

/**
 * Sends a message to client.
 */
void Server::sendToClient(Client* client, const std::string& message) {
    int bytesSent = send(client->getSocket(), message.c_str(), message.length(), 0);

    if (bytesSent < 0) {
        LOG_ERROR("Error sending to client " + client->getAddress());
        disconnectClient(client, "Error while sending");
        return;
    }
}

/**
 * Disconnects a client.
 */
void Server::disconnectClient(Client* client, const std::string& reason) {
    LOG_INFO("Disconnecting " + client->getAddress() + " (" + client->getNickname() + "): " + reason);

    // Log state for recovery
    std::map<std::string, std::string> stateData = {
        {"nickname", client->getNickname()},
        {"reason", reason}
    };

    // Remove from room if in one
    Room* room = getRoomForClient(client);
    if (room) {
        int roomId = room->getId();
        stateData["roomId"] = std::to_string(roomId);

        // Check if game was in progress
        bool gameInProgress = (room->getGame() != nullptr);
        if (gameInProgress) {
            stateData["gameInProgress"] = "true";
        }

        // removePlayer with isDisconnect=true to preserve game state for reconnect
        room->removePlayer(client, true);

        // If room is empty, delete it
        // Note: Don't delete room if game was in progress - wait for reconnect
        if (room->getPlayerCount() == 0 && !gameInProgress) {
            rooms.erase(roomId);
        }
    }

    // If player was in a game, save their info for reconnect
    if (room && room->getGame()) {
        DisconnectedPlayerInfo info;
        info.roomId = room->getId();
        info.sessionId = client->getSessionId();
        info.disconnectTime = time(nullptr);
        disconnectedPlayers[client->getNickname()] = info;

        // DON'T remove from activeNicknames - we want to prevent someone else from taking this nickname
        // while the player might reconnect
    } else {
        // Normal disconnect - remove from active nicknames
        if (!client->getNickname().empty()) {
            activeNicknames.erase(client->getNickname());
        }
    }

    int socket = client->getSocket();
    clients.erase(socket);
}

/**
 * Processes a message from client.
 */
void Server::processMessage(Client* client, const std::string& message) {
    if (message.empty()) {
        handleInvalidMessage(client, "Empty message");
        return;
    }

    std::vector<std::string> parts = Protocol::parseMessage(message);
    if (parts.empty()) {
        handleInvalidMessage(client, "Unable to parse message");
        return;
    }

    const std::string& command = parts[0];

    // Process based on command
    if (command == Protocol::CMD_LOGIN) {
        handleLogin(client, parts);
    } else if (command == Protocol::CMD_PING) {
        handlePing(client);
    } else if (command == Protocol::CMD_DISCONNECT) {
        handleDisconnect(client);
    } else if (command == Protocol::CMD_ROOM_LIST) {
        handleRoomList(client);
    } else if (command == Protocol::CMD_CREATE_ROOM) {
        handleCreateRoom(client, parts);
    } else if (command == Protocol::CMD_JOIN_ROOM) {
        handleJoinRoom(client, parts);
    } else if (command == Protocol::CMD_LEAVE_ROOM) {
        handleLeaveRoom(client);
    } else if (command == Protocol::CMD_HIT) {
        handleHit(client);
    } else if (command == Protocol::CMD_STAND) {
        handleStand(client);
    } else if (command == Protocol::CMD_ACK_DEAL_CARDS) {
        handleAckDealCards(client);
    } else if (command == Protocol::CMD_ACK_ROUND_END) {
        handleAckRoundEnd(client);
    } else if (command == Protocol::CMD_ACK_GAME_END) {
        handleAckGameEnd(client);
    } else if (command == Protocol::CMD_ACK_GAME_STATE) {
        handleAckGameState(client);
    } else if (command == Protocol::CMD_RECONNECT_ACCEPT) {
        handleReconnectAccept(client);
    } else if (command == Protocol::CMD_RECONNECT_DECLINE) {
        handleReconnectDecline(client);
    } else {
        handleInvalidMessage(client, "Invalid command: " + command);
    }
}

/**
 * Processes LOGIN command (new login or reconnect).
 *
 * Function handles:
 * - New login: nickname validation, session ID creation
 * - Reconnect with session ID: session validation, game state restoration
 * - Reconnect query: asking client if they want to continue ongoing game
 * - Game state restoration: GAME_STATE, DEAL_CARDS, YOUR_TURN
 * - Opponent notification about reconnect (PLAYER_RECONNECTED)
 *
 * @param client Client who is logging in
 * @param parts Parsed message parts (LOGIN|nickname [|sessionId])
 */
void Server::handleLogin(Client* client, const std::vector<std::string>& parts) {
    // Accept 2 parameters (LOGIN|nickname) or 3 (LOGIN|nickname|sessionId)
    if (parts.size() != 2 && parts.size() != 3) {
        handleInvalidMessage(client, "Invalid parameter count");
        return;
    }

    if (client->getState() != Protocol::CONNECTED) {
        handleInvalidMessage(client, "Already logged in");
        return;
    }

    std::string nickname = parts[1];
    std::string providedSessionId = (parts.size() == 3) ? parts[2] : "";
    bool isReconnectAttempt = !providedSessionId.empty();

    if (!Protocol::isValidNickname(nickname)) {
        client->queueMessage(Protocol::buildMessage({Protocol::CMD_ERROR, "Invalid nickname"}));
        return;
    }

    // Set nickname early so it's available in reconnect handlers
    client->setNickname(nickname);

    // Check if this is a reconnect attempt
    auto disconnectedIt = disconnectedPlayers.find(nickname);
    if (disconnectedIt != disconnectedPlayers.end()) {
        // Player is in disconnected list
        DisconnectedPlayerInfo& info = disconnectedIt->second;

        if (isReconnectAttempt) {
            // Validate session ID
            if (providedSessionId != info.sessionId) {
                LOG_WARNING("Invalid session ID for reconnect by " + nickname);
                client->queueMessage(Protocol::buildMessage({Protocol::CMD_ERROR, "Invalid session ID"}));

                // Clean up - session is invalid, remove from disconnectedPlayers and close connection
                disconnectedPlayers.erase(disconnectedIt);
                activeNicknames.erase(nickname);
                int socket = client->getSocket();
                clients.erase(socket);
                return;
            }


            // Restore player info
            client->setNickname(nickname);
            client->setSessionId(info.sessionId);

            // Find the room
            auto roomIt = rooms.find(info.roomId);
            if (roomIt != rooms.end()) {
                Room* room = roomIt->second.get();

                // Reconnect player to room
                room->reconnectPlayer(client);


                // Send OK with session ID
                client->queueMessage(Protocol::buildMessage({Protocol::CMD_OK, client->getSessionId()}));

                // Send game state to reconnected player
                Game* game = room->getGame();
                if (game) {
                    // Find the player by nickname and update their client pointer
                    Player* reconnectedPlayer = game->getPlayerByNickname(nickname);
                    if (reconnectedPlayer) {
                        // Update the client pointer to the new connection
                        reconnectedPlayer->client = client;
                    }

                    // Now get the opponent (this will work correctly since we updated the client pointer)
                    const Player* opponent = game->getOpponent(client);

                    if (reconnectedPlayer && opponent) {
                        // Check if opponent is actually connected (not in disconnectedPlayers)
                        std::string opponentNickname = opponent->nickname;  // Use stored nickname, not client->getNickname()
                        bool opponentIsDisconnected = (disconnectedPlayers.find(opponentNickname) != disconnectedPlayers.end());

                        if (opponentIsDisconnected) {
                            // Opponent is still disconnected - inform reconnected player
                            // DON'T send GAME_START yet - wait until both players are connected

                            client->queueMessage(Protocol::buildMessage({
                                Protocol::CMD_PLAYER_DISCONNECTED,
                                opponentNickname
                            }));

                            // Don't send GAME_START or game state - player should wait for opponent
                        } else {
                            // Opponent is connected - NOW send GAME_START to both players

                            std::string role = game->getPlayerRole(client);
                            std::string opponentRole = game->getPlayerRole(opponent->client);

                            // Send GAME_START to reconnected player
                            client->queueMessage(Protocol::buildMessage({
                                Protocol::CMD_GAME_START,
                                role,
                                opponentNickname
                            }));

                            // Send GAME_STATE
                            game->notifyGameState();

                            // Send player's cards
                            if (!reconnectedPlayer->hand.empty()) {
                                std::vector<std::string> dealCardsMsg = {
                                    Protocol::CMD_DEAL_CARDS,
                                    std::to_string(reconnectedPlayer->hand.size())
                                };
                                for (const Card& card : reconnectedPlayer->hand) {
                                    dealCardsMsg.push_back(card.toString());
                                }
                                client->queueMessage(Protocol::buildMessage(dealCardsMsg));
                            }

                            // If it's player's turn, send YOUR_TURN
                            if (game->isPlayerTurn(client)) {
                                game->notifyYourTurn(const_cast<Player*>(reconnectedPlayer));
                            }

                            // Notify opponent about reconnect
                            opponent->client->queueMessage(Protocol::buildMessage({
                                Protocol::CMD_PLAYER_RECONNECTED,
                                reconnectedPlayer->client->getNickname()
                            }));

                            // Send GAME_START to opponent (they didn't get it when they reconnected because other player was disconnected)
                            opponent->client->queueMessage(Protocol::buildMessage({
                                Protocol::CMD_GAME_START,
                                opponentRole,
                                reconnectedPlayer->nickname
                            }));

                            // Send opponent's cards
                            if (!opponent->hand.empty()) {
                                std::vector<std::string> opponentCardsMsg = {
                                    Protocol::CMD_DEAL_CARDS,
                                    std::to_string(opponent->hand.size())
                                };
                                for (const Card& card : opponent->hand) {
                                    opponentCardsMsg.push_back(card.toString());
                                }
                                opponent->client->queueMessage(Protocol::buildMessage(opponentCardsMsg));
                            }

                            // If it's opponent's turn, send YOUR_TURN
                            if (game->isPlayerTurn(opponent->client)) {
                                game->notifyYourTurn(const_cast<Player*>(opponent));
                            }
                        }
                    }

                }

                // Remove from disconnected players
                disconnectedPlayers.erase(disconnectedIt);

                // Log state for recovery
                std::map<std::string, std::string> reconnectData = {
                    {"nickname", nickname},
                    {"roomId", std::to_string(info.roomId)}
                };

                return;
            } else {
                // Room doesn't exist anymore
                LOG_WARNING("Room " + std::to_string(info.roomId) + " no longer exists for reconnect of " + nickname);
                disconnectedPlayers.erase(disconnectedIt);
                activeNicknames.erase(nickname);
                client->queueMessage(Protocol::buildMessage({Protocol::CMD_ERROR, "Session expired"}));

                // Clean up - close connection after sending error
                int socket = client->getSocket();
                clients.erase(socket);
                return;
            }
        } else {
            // No session ID provided but player is in disconnected list
            // This is a reconnect situation (client restarted, lost session ID)
            // Send RECONNECT_QUERY to ask user if they want to reconnect

            int roomId = info.roomId;

            // Get room and opponent info for the prompt
            std::string opponentNickname = "";
            auto roomIt = rooms.find(roomId);
            if (roomIt != rooms.end()) {
                Room* room = roomIt->second.get();
                Game* game = room->getGame();
                if (game) {
                    // Find opponent by getting the reconnecting player first
                    Player* reconnectingPlayer = game->getPlayerByNickname(nickname);
                    if (reconnectingPlayer) {
                        const Player* opponent = game->getOpponent(reconnectingPlayer->client);
                        if (opponent) {
                            opponentNickname = opponent->nickname;
                        }
                    }
                }
            }

            // Send RECONNECT_QUERY message
            // Format: RECONNECT_QUERY|roomId|opponentNickname
            client->queueMessage(Protocol::buildMessage({
                Protocol::CMD_RECONNECT_QUERY,
                std::to_string(roomId),
                opponentNickname
            }));

            return;
        }
    } else {
        // Player NOT in disconnected list
        if (isReconnectAttempt) {
            // Session ID provided but player not in list (expired)
            LOG_WARNING("Player " + nickname + " session expired");
            client->queueMessage(Protocol::buildMessage({Protocol::CMD_ERROR, "Session expired"}));
            return;
        }

        // Normal login (not a reconnect)
        if (isNicknameTaken(nickname)) {
            client->queueMessage(Protocol::buildMessage({Protocol::CMD_ERROR, "Nickname already in use"}));
            return;
        }

        // Login successful
        client->setNickname(nickname);
        client->setState(Protocol::LOBBY);
        activeNicknames.insert(nickname);

        client->queueMessage(Protocol::buildMessage({Protocol::CMD_OK, client->getSessionId()}));
        LOG_INFO("Client " + client->getAddress() + " logged in as " + nickname);

        // Log state for recovery
        std::map<std::string, std::string> loginData = {
            {"nickname", nickname},
            {"sessionId", client->getSessionId()},
            {"address", client->getAddress()}
        };
    }
}

/**
 * Processes PING command.
 */
void Server::handlePing(Client* client) {
    client->queueMessage(Protocol::buildMessage({Protocol::CMD_PONG}));
}

/**
 * Processes DISCONNECT command.
 */
void Server::handleDisconnect(Client* client) {
    client->queueMessage(Protocol::buildMessage({Protocol::CMD_OK}));
    disconnectClient(client, "Client disconnected");
}

/**
 * Processes ROOM_LIST command.
 */
void Server::handleRoomList(Client* client) {
    if (client->getState() != Protocol::LOBBY) {
        handleInvalidMessage(client, "You are not in lobby");
        return;
    }

    // Number of rooms
    client->queueMessage(Protocol::buildMessage({Protocol::CMD_ROOMS, std::to_string(rooms.size())}));

    // List of rooms
    for (const auto& pair : rooms) {
        client->queueMessage(pair.second->toProtocolString() + "\n");
    }
}

/**
 * Processes CREATE_ROOM command.
 */
void Server::handleCreateRoom(Client* client, const std::vector<std::string>& parts) {
    if (!validateMessage(client, parts, 2)) {
        return;
    }

    if (client->getState() != Protocol::LOBBY) {
        handleInvalidMessage(client, "You are not in lobby");
        return;
    }

    if (rooms.size() >= static_cast<size_t>(MAX_ROOMS)) {
        client->queueMessage(Protocol::buildMessage({Protocol::CMD_ERROR, "Too many rooms"}));
        return;
    }

    std::string roomName = parts[1];
    if (roomName.empty() || roomName.length() > 50) {
        client->queueMessage(Protocol::buildMessage({Protocol::CMD_ERROR, "Invalid name"}));
        return;
    }

    int roomId = nextRoomId++;
    auto room = std::make_unique<Room>(roomId, roomName, client);
    rooms[roomId] = std::move(room);

    client->queueMessage(Protocol::buildMessage({Protocol::CMD_ROOM_CREATED, std::to_string(roomId)}));

    // Log state for recovery
    std::map<std::string, std::string> roomData = {
        {"roomId", std::to_string(roomId)},
        {"name", roomName},
        {"creator", client->getNickname()}
    };
}

/**
 * Processes JOIN_ROOM command.
 */
void Server::handleJoinRoom(Client* client, const std::vector<std::string>& parts) {
    if (!validateMessage(client, parts, 2)) {
        return;
    }

    if (client->getState() != Protocol::LOBBY) {
        handleInvalidMessage(client, "You are not in lobby");
        return;
    }

    int roomId;
    try {
        roomId = std::stoi(parts[1]);
    } catch (...) {
        client->queueMessage(Protocol::buildMessage({Protocol::CMD_ERROR, "Invalid room ID"}));
        return;
    }

    auto it = rooms.find(roomId);
    if (it == rooms.end()) {
        client->queueMessage(Protocol::buildMessage({Protocol::CMD_ERROR, "Room does not exist"}));
        return;
    }

    Room* room = it->second.get();

    if (room->isFull()) {
        client->queueMessage(Protocol::buildMessage({Protocol::CMD_ERROR, "Room is full"}));
        return;
    }

    if (room->getState() != ROOM_WAITING) {
        client->queueMessage(Protocol::buildMessage({Protocol::CMD_ERROR, "Game already in progress"}));
        return;
    }

    client->queueMessage(Protocol::buildMessage({
        Protocol::CMD_JOINED,
        std::to_string(roomId),
        std::to_string(room->getPlayerCount())
    }));
    room->addPlayer(client);
}

/**
 * Processes LEAVE_ROOM command.
 */
void Server::handleLeaveRoom(Client* client) {
    if (client->getState() != Protocol::IN_ROOM && client->getState() != Protocol::PLAYING) {
        handleInvalidMessage(client, "You are not in a room");
        return;
    }

    Room* room = getRoomForClient(client);
    if (!room) {
        handleInvalidMessage(client, "You are not in a room");
        return;
    }

    int roomId = room->getId();
    bool gameWasInProgress = (room->getGame() != nullptr);

    // This is intentional leave, not disconnect - use isDisconnect=false
    room->removePlayer(client, false);
    client->queueMessage(Protocol::buildMessage({Protocol::CMD_OK}));

    // If game was in progress and this player left intentionally,
    // clean up any disconnected players from this room
    if (gameWasInProgress) {
        // Find and remove all disconnected players from this room
        std::vector<std::string> toRemove;
        for (const auto& pair : disconnectedPlayers) {
            if (pair.second.roomId == roomId) {
                toRemove.push_back(pair.first);
            }
        }

        for (const std::string& nickname : toRemove) {
            disconnectedPlayers.erase(nickname);
            // Free up the nickname so someone else can use it
            activeNicknames.erase(nickname);
        }
    }

    // Always delete room if it's now empty (regardless of whether game was in progress)
    auto roomIt = rooms.find(roomId);
    if (roomIt != rooms.end() && roomIt->second->getPlayerCount() == 0) {
        rooms.erase(roomIt);
    }
}

/**
 * Processes HIT command.
 */
void Server::handleHit(Client* client) {
    if (client->getState() != Protocol::PLAYING) {
        handleInvalidMessage(client, "You are not in game");
        return;
    }

    Room* room = getRoomForClient(client);
    if (!room || !room->getGame()) {
        handleInvalidMessage(client, "You are not in game");
        return;
    }

    room->getGame()->playerHit(client);

    // Check if game ended and clean up room if needed
    room->checkAndHandleGameEnd();

    // If room is empty and finished, delete it
    if (room->getState() == ROOM_FINISHED && room->getPlayerCount() == 0) {
        int roomId = room->getId();
        rooms.erase(roomId);
    }
}

/**
 * Processes STAND command.
 */
void Server::handleStand(Client* client) {
    if (client->getState() != Protocol::PLAYING) {
        handleInvalidMessage(client, "You are not in game");
        return;
    }

    Room* room = getRoomForClient(client);
    if (!room || !room->getGame()) {
        handleInvalidMessage(client, "You are not in game");
        return;
    }

    room->getGame()->playerStand(client);

    // Check if game ended and clean up room if needed
    room->checkAndHandleGameEnd();

    // If room is empty and finished, delete it
    if (room->getState() == ROOM_FINISHED && room->getPlayerCount() == 0) {
        int roomId = room->getId();
        rooms.erase(roomId);
    }
}

/**
 * Validates message - checks parameter count.
 */
bool Server::validateMessage(Client* client, const std::vector<std::string>& parts, size_t expectedSize) {
    if (parts.size() != expectedSize) {
        handleInvalidMessage(client, "Invalid parameter count");
        return false;
    }
    return true;
}

/**
 * Processes invalid message.
 */
void Server::handleInvalidMessage(Client* client, const std::string& reason) {
    LOG_WARNING("Invalid message from " + client->getAddress() + ": " + reason);

    client->incrementInvalidMessageCount();
    client->queueMessage(Protocol::buildMessage({Protocol::CMD_ERROR, reason}));

    if (client->shouldDisconnect()) {
        disconnectClient(client, "Too many invalid messages");
    }
}

/**
 * Checks if nickname is already taken.
 */
bool Server::isNicknameTaken(const std::string& nickname) {
    return activeNicknames.find(nickname) != activeNicknames.end();
}

/**
 * Removes timed out clients.
 */
void Server::cleanupTimedOutClients() {
    std::vector<Client*> timedOut;

    for (const auto& pair : clients) {
        Client* client = pair.second.get();
        if (client->isTimedOut(CLIENT_TIMEOUT)) {
            timedOut.push_back(client);
        }
    }

    for (Client* client : timedOut) {
        disconnectClient(client, "Timeout");
    }
}

/**
 * Finds room for client.
 */
Room* Server::getRoomForClient(Client* client) {
    int roomId = client->getRoomId();
    if (roomId < 0) {
        return nullptr;
    }

    auto it = rooms.find(roomId);
    if (it == rooms.end()) {
        return nullptr;
    }

    return it->second.get();
}

/**
 * Processes ACK_DEAL_CARDS message.
 */
void Server::handleAckDealCards(Client* client) {
    // ACK received, client processed DEAL_CARDS
    (void)client;  // Suppress warnings for unused parameter
}

/**
 * Processes ACK_ROUND_END message.
 */
void Server::handleAckRoundEnd(Client* client) {
    // ACK received, client processed ROUND_END
    (void)client;
}

/**
 * Processes ACK_GAME_END message.
 */
void Server::handleAckGameEnd(Client* client) {
    // ACK received, client processed GAME_END
    (void)client;
}

/**
 * Processes ACK_GAME_STATE message.
 */
void Server::handleAckGameState(Client* client) {
    // ACK received, client processed GAME_STATE
    (void)client;
}

/**
 * Processes RECONNECT_ACCEPT - player agrees to reconnect.
 */
void Server::handleReconnectAccept(Client* client) {
    std::string nickname = client->getNickname();
    LOG_INFO("Player " + nickname + " accepted reconnect");

    // Find player in disconnectedPlayers
    auto disconnectedIt = disconnectedPlayers.find(nickname);
    if (disconnectedIt == disconnectedPlayers.end()) {
        LOG_WARNING("RECONNECT_ACCEPT from " + nickname + " but not in disconnectedPlayers");
        client->queueMessage(Protocol::buildMessage({Protocol::CMD_ERROR, "Reconnect failed"}));
        return;
    }

    DisconnectedPlayerInfo& info = disconnectedIt->second;

    // Restore session ID
    client->setSessionId(info.sessionId);

    // Find the room
    auto roomIt = rooms.find(info.roomId);
    if (roomIt == rooms.end()) {
        LOG_WARNING("Room " + std::to_string(info.roomId) + " no longer exists for reconnect of " + nickname);
        disconnectedPlayers.erase(disconnectedIt);
        activeNicknames.erase(nickname);
        client->queueMessage(Protocol::buildMessage({Protocol::CMD_ERROR, "Room no longer exists"}));
        return;
    }

    Room* room = roomIt->second.get();

    // Reconnect player to room
    room->reconnectPlayer(client);


    // Send OK with session ID
    client->queueMessage(Protocol::buildMessage({Protocol::CMD_OK, client->getSessionId()}));

    // Send game state to reconnected player
    Game* game = room->getGame();
    if (game) {
        // Find the player by nickname and update their client pointer
        Player* reconnectedPlayer = game->getPlayerByNickname(nickname);
        if (reconnectedPlayer) {
            reconnectedPlayer->client = client;
        }

        const Player* opponent = game->getOpponent(client);

        if (reconnectedPlayer && opponent) {
            std::string opponentNickname = opponent->nickname;
            bool opponentIsDisconnected = (disconnectedPlayers.find(opponentNickname) != disconnectedPlayers.end());

            if (opponentIsDisconnected) {
                client->queueMessage(Protocol::buildMessage({
                    Protocol::CMD_PLAYER_DISCONNECTED,
                    opponentNickname
                }));
            } else {

                std::string role = game->getPlayerRole(client);
                std::string opponentRole = game->getPlayerRole(opponent->client);

                // Send GAME_START to reconnected player
                client->queueMessage(Protocol::buildMessage({
                    Protocol::CMD_GAME_START,
                    role,
                    opponentNickname
                }));

                // Send GAME_STATE
                game->notifyGameState();

                // Send player's cards
                if (!reconnectedPlayer->hand.empty()) {
                    std::vector<std::string> dealCardsMsg = {
                        Protocol::CMD_DEAL_CARDS,
                        std::to_string(reconnectedPlayer->hand.size())
                    };
                    for (const Card& card : reconnectedPlayer->hand) {
                        dealCardsMsg.push_back(card.toString());
                    }
                    client->queueMessage(Protocol::buildMessage(dealCardsMsg));
                }

                // If it's player's turn, send YOUR_TURN
                if (game->isPlayerTurn(client)) {
                    game->notifyYourTurn(const_cast<Player*>(reconnectedPlayer));
                }

                // Notify opponent about reconnect
                opponent->client->queueMessage(Protocol::buildMessage({
                    Protocol::CMD_PLAYER_RECONNECTED,
                    reconnectedPlayer->client->getNickname()
                }));

                // Send GAME_START to opponent
                opponent->client->queueMessage(Protocol::buildMessage({
                    Protocol::CMD_GAME_START,
                    opponentRole,
                    reconnectedPlayer->nickname
                }));

                // Send opponent's cards
                if (!opponent->hand.empty()) {
                    std::vector<std::string> opponentCardsMsg = {
                        Protocol::CMD_DEAL_CARDS,
                        std::to_string(opponent->hand.size())
                    };
                    for (const Card& card : opponent->hand) {
                        opponentCardsMsg.push_back(card.toString());
                    }
                    opponent->client->queueMessage(Protocol::buildMessage(opponentCardsMsg));
                }

                // If it's opponent's turn, send YOUR_TURN
                if (game->isPlayerTurn(opponent->client)) {
                    game->notifyYourTurn(const_cast<Player*>(opponent));
                }
            }
        }

    }

    // Remove from disconnected players
    disconnectedPlayers.erase(disconnectedIt);

    // Log state for recovery
    std::map<std::string, std::string> reconnectData = {
        {"nickname", nickname},
        {"roomId", std::to_string(info.roomId)}
    };
}

/**
 * Processes RECONNECT_DECLINE - player declines reconnect, wants fresh login.
 */
void Server::handleReconnectDecline(Client* client) {
    std::string nickname = client->getNickname();
    LOG_INFO("Player " + nickname + " declined reconnect, allowing fresh login");

    // Find player in disconnectedPlayers
    auto disconnectedIt = disconnectedPlayers.find(nickname);
    if (disconnectedIt == disconnectedPlayers.end()) {
        LOG_WARNING("RECONNECT_DECLINE from " + nickname + " but not in disconnectedPlayers");
        client->queueMessage(Protocol::buildMessage({Protocol::CMD_ERROR, "Already removed"}));
        return;
    }

    DisconnectedPlayerInfo& info = disconnectedIt->second;
    int oldRoomId = info.roomId;

    // Remove from disconnected players
    disconnectedPlayers.erase(disconnectedIt);

    // Check room and reset it to WAITING state
    auto roomIt = rooms.find(oldRoomId);
    if (roomIt != rooms.end()) {
        Room* room = roomIt->second.get();

        // If room is empty (all players disconnected/declined), delete it
        if (room->getPlayerCount() == 0) {
            rooms.erase(roomIt);
        } else {
            // Room has other player(s) - notify them and reset room to WAITING
            Game* game = room->getGame();
            if (game) {
                // Notify remaining players that opponent declined reconnect
                // Find opponent by nickname (client pointer may be invalid after disconnect)
                Player* opponent = game->getOpponentByNickname(nickname);
                if (opponent && opponent->client) {
                    // Send OPPONENT_LEFT to indicate opponent won't reconnect (different from temporary disconnect)
                    opponent->client->queueMessage(Protocol::buildMessage({
                        Protocol::CMD_OPPONENT_LEFT,
                        nickname,
                        "declined"  // reason: declined or timeout
                    }));
                    // Return opponent back to IN_ROOM state
                    opponent->client->setState(Protocol::IN_ROOM);
                }
            }

            // Reset room to WAITING state (deletes game, sets state to WAITING)
            room->resetGame();

            // Remove all other disconnected players from this room
            // (since the game no longer exists, they cannot reconnect)
            std::vector<std::string> playersToRemove;
            for (const auto& pair : disconnectedPlayers) {
                if (pair.second.roomId == oldRoomId && pair.first != nickname) {
                    playersToRemove.push_back(pair.first);
                }
            }

            for (const std::string& playerNickname : playersToRemove) {
                disconnectedPlayers.erase(playerNickname);
                activeNicknames.erase(playerNickname);
                LOG_INFO("Removed player " + playerNickname + " from disconnectedPlayers - room was reset");
            }
        }
    }

    // Now allow fresh login - send OK with new session ID
    client->setState(Protocol::LOBBY);
    activeNicknames.insert(nickname);

    client->queueMessage(Protocol::buildMessage({Protocol::CMD_OK, client->getSessionId()}));

    // Log state
    std::map<std::string, std::string> loginData = {
        {"nickname", nickname},
        {"sessionId", client->getSessionId()},
        {"address", client->getAddress()},
        {"reconnectDeclined", "true"}
    };
}

/**
 * Removes players from disconnectedPlayers who exceeded timeout.
 */
void Server::cleanupTimedOutDisconnectedPlayers() {
    if (disconnectedPlayers.empty()) {
        return;
    }

    time_t now = time(nullptr);
    std::vector<std::string> toRemove;

    for (const auto& pair : disconnectedPlayers) {
        const std::string& nickname = pair.first;
        const DisconnectedPlayerInfo& info = pair.second;

        time_t duration = now - info.disconnectTime;

        if (duration > Protocol::RECONNECT_TIMEOUT) {
            toRemove.push_back(nickname);
            LOG_INFO("Player " + nickname + " timed out (disconnected " +
                     std::to_string(duration) + " seconds)");
        }
    }

    // Remove expired players
    for (const std::string& nickname : toRemove) {
        auto it = disconnectedPlayers.find(nickname);
        if (it != disconnectedPlayers.end()) {
            int roomId = it->second.roomId;
            disconnectedPlayers.erase(it);
            activeNicknames.erase(nickname);

            // Check if room should be deleted
            auto roomIt = rooms.find(roomId);
            if (roomIt != rooms.end()) {
                Room* room = roomIt->second.get();
                if (room->getPlayerCount() == 0 && room->getGame() != nullptr) {
                    rooms.erase(roomIt);
                } else {
                    // Room has other player(s) - notify them and reset room to WAITING
                    Game* game = room->getGame();
                    if (game) {
                        // Notify remaining players that opponent timed out
                        // Find opponent by nickname (client pointer may be invalid after disconnect)
                        Player* opponent = game->getOpponentByNickname(nickname);
                        if (opponent && opponent->client) {
                            // Send OPPONENT_LEFT to indicate opponent won't reconnect (different from temporary disconnect)
                            opponent->client->queueMessage(Protocol::buildMessage({
                                Protocol::CMD_OPPONENT_LEFT,
                                nickname,
                                "timeout"  // reason: declined or timeout
                            }));
                            // Return opponent back to IN_ROOM state
                            opponent->client->setState(Protocol::IN_ROOM);
                        }
                    }

                    // Reset room to WAITING state (deletes game, sets state to WAITING)
                    room->resetGame();

                    // Remove all other disconnected players from this room
                    // (since the game no longer exists, they cannot reconnect)
                    std::vector<std::string> playersToRemove;
                    for (const auto& pair : disconnectedPlayers) {
                        if (pair.second.roomId == roomId && pair.first != nickname) {
                            playersToRemove.push_back(pair.first);
                        }
                    }

                    for (const std::string& playerNickname : playersToRemove) {
                        disconnectedPlayers.erase(playerNickname);
                        activeNicknames.erase(playerNickname);
                        LOG_INFO("Removed player " + playerNickname + " from disconnectedPlayers - room was reset");
                    }
                }
            }

        }
    }
}

/**
 * Shuts down the server.
 */
void Server::shutdown() {
    running = false;

    // Delete rooms
    rooms.clear();

    // Disconnect all clients
    for (const auto& pair : clients) {
        close(pair.first);
    }
    clients.clear();

    // Close server socket
    if (serverSocket >= 0) {
        close(serverSocket);
        serverSocket = -1;
    }

    LOG_INFO("Server terminated");
}

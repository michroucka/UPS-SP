#ifndef SERVER_H
#define SERVER_H

#include <string>
#include <map>
#include <vector>
#include <set>
#include <memory>
#include "client.h"
#include "room.h"

/**
 * Main TCP server class for Oko Bere game.
 * Uses select() for parallel handling of multiple clients.
 */
class Server {
public:
    Server(const std::string& address, int port, int max_clients, int max_rooms);
    ~Server();

    bool initialize();
    void run();
    void shutdown();

private:
    // Network functions
    bool createSocket();
    bool bindSocket();
    bool listenSocket();
    void acceptNewClient();
    void handleClientData(Client* client);
    void sendToClient(Client* client, const std::string& message);
    void disconnectClient(Client* client, const std::string& reason);

    // Message processing
    void processMessage(Client* client, const std::string& message);
    void handleLogin(Client* client, const std::vector<std::string>& parts);
    void handlePing(Client* client);
    void handleDisconnect(Client* client);
    void handleRoomList(Client* client);
    void handleCreateRoom(Client* client, const std::vector<std::string>& parts);
    void handleJoinRoom(Client* client, const std::vector<std::string>& parts);
    void handleLeaveRoom(Client* client);
    void handleHit(Client* client);
    void handleStand(Client* client);
    void handleAckDealCards(Client* client);
    void handleAckRoundEnd(Client* client);
    void handleAckGameEnd(Client* client);
    void handleAckGameState(Client* client);
    void handleReconnectAccept(Client* client);
    void handleReconnectDecline(Client* client);

    // Validation
    bool validateMessage(Client* client, const std::vector<std::string>& parts, size_t expectedSize);
    void handleInvalidMessage(Client* client, const std::string& reason);

    // Utility
    bool isNicknameTaken(const std::string& nickname);
    void cleanupTimedOutClients();
    void cleanupTimedOutDisconnectedPlayers();
    Room* getRoomForClient(Client* client);

    // Server configuration
    std::string address;
    int port;
    int serverSocket;
    bool running;

    // Clients
    std::map<int, std::unique_ptr<Client>> clients;  // socket -> Client
    std::set<std::string> activeNicknames;

    // Disconnected players waiting for reconnect
    struct DisconnectedPlayerInfo {
        int roomId;
        std::string sessionId;
        time_t disconnectTime;
    };
    std::map<std::string, DisconnectedPlayerInfo> disconnectedPlayers;  // nickname -> info

    // Rooms
    std::map<int, std::unique_ptr<Room>> rooms;  // roomId -> Room
    int nextRoomId;

    // Limits
    const int MAX_CLIENTS;
    const int CLIENT_TIMEOUT = 10;  // 10 seconds (client sends PING every 5s)
    const int MAX_ROOMS;
};

#endif // SERVER_H

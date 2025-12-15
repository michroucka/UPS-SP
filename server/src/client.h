#ifndef CLIENT_H
#define CLIENT_H

#include <string>
#include <queue>
#include "protocol.h"

/**
 * Reprezentace připojeného klienta.
 */
class Client {
public:
    Client(int socket, const std::string& address);
    ~Client();

    // Gettery
    int getSocket() const { return socket; }
    std::string getAddress() const { return address; }
    std::string getNickname() const { return nickname; }
    std::string getSessionId() const { return sessionId; }
    Protocol::ClientState getState() const { return state; }
    int getRoomId() const { return roomId; }
    int getInvalidMessageCount() const { return invalidMessageCount; }

    // Settery
    void setNickname(const std::string& nick) { nickname = nick; }
    void setSessionId(const std::string& id) { sessionId = id; }
    void setState(Protocol::ClientState newState) { state = newState; }
    void setRoomId(int id) { roomId = id; }

    // Operace s buffery
    void appendToReadBuffer(const std::string& data);
    std::string getReadBuffer() const { return readBuffer; }
    void clearReadBuffer() { readBuffer.clear(); }
    bool hasCompleteMessage() const;
    std::string extractMessage();

    void queueMessage(const std::string& message);
    std::string getNextMessageToSend();
    bool hasMessagesToSend() const { return !writeQueue.empty(); }

    // Validace
    void incrementInvalidMessageCount() { invalidMessageCount++; }
    void resetInvalidMessageCount() { invalidMessageCount = 0; }
    bool shouldDisconnect() const { return invalidMessageCount >= Protocol::MAX_INVALID_MESSAGES; }

    // Timeouty
    void updateLastActivity();
    time_t getLastActivity() const { return lastActivity; }
    bool isTimedOut(int timeoutSeconds) const;

private:
    int socket;
    std::string address;
    std::string nickname;
    std::string sessionId;
    Protocol::ClientState state;
    int roomId;

    std::string readBuffer;
    std::queue<std::string> writeQueue;

    int invalidMessageCount;
    time_t lastActivity;

    std::string generateSessionId();
};

#endif // CLIENT_H

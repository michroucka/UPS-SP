#include "client.h"
#include <unistd.h>
#include <random>
#include <sstream>
#include <iomanip>

Client::Client(int socket, const std::string& address)
    : socket(socket), address(address), state(Protocol::CONNECTED),
      roomId(-1), invalidMessageCount(0) {
    sessionId = generateSessionId();
    updateLastActivity();
}

Client::~Client() {
    if (socket >= 0) {
        close(socket);
    }
}

/**
 * Adds data to the read buffer.
 * VALIDATION: Throws exception if buffer would exceed MAX_MESSAGE_SIZE
 */
void Client::appendToReadBuffer(const std::string& data) {
    // VALIDATION: Check buffer size to prevent overflow
    if (readBuffer.length() + data.length() > Protocol::MAX_MESSAGE_SIZE) {
        throw std::runtime_error("Message buffer overflow - message too large");
    }
    readBuffer += data;
}

/**
 * Checks if the buffer contains a complete message (terminated with \n).
 */
bool Client::hasCompleteMessage() const {
    return readBuffer.find(Protocol::MESSAGE_END) != std::string::npos;
}

/**
 * Extracts one complete message from the buffer.
 * @return Message without the trailing \n
 */
std::string Client::extractMessage() {
    size_t pos = readBuffer.find(Protocol::MESSAGE_END);
    if (pos == std::string::npos) {
        return "";
    }

    std::string message = readBuffer.substr(0, pos);
    readBuffer.erase(0, pos + 1);

    return message;
}

/**
 * Adds a message to the send queue.
 */
void Client::queueMessage(const std::string& message) {
    writeQueue.push(message);
}

/**
 * Returns the next message to send.
 */
std::string Client::getNextMessageToSend() {
    if (writeQueue.empty()) {
        return "";
    }

    std::string message = writeQueue.front();
    writeQueue.pop();
    return message;
}

/**
 * Updates the last activity time.
 */
void Client::updateLastActivity() {
    lastActivity = time(nullptr);
}

/**
 * Checks if the client has timed out.
 */
bool Client::isTimedOut(int timeoutSeconds) const {
    time_t now = time(nullptr);
    return (now - lastActivity) > timeoutSeconds;
}

/**
 * Generates a random session ID.
 */
std::string Client::generateSessionId() {
    std::random_device rd;
    std::mt19937 gen(rd());
    std::uniform_int_distribution<> dis(0, 15);

    std::ostringstream oss;
    for (int i = 0; i < 16; ++i) {
        oss << std::hex << dis(gen);
    }

    return oss.str();
}

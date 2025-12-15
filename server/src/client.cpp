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
 * Přidá data do read bufferu.
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
 * Kontroluje, zda buffer obsahuje kompletní zprávu (ukončenou \n).
 */
bool Client::hasCompleteMessage() const {
    return readBuffer.find(Protocol::MESSAGE_END) != std::string::npos;
}

/**
 * Extrahuje jednu kompletní zprávu z bufferu.
 * @return Zpráva bez koncového \n
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
 * Přidá zprávu do fronty pro odeslání.
 */
void Client::queueMessage(const std::string& message) {
    writeQueue.push(message);
}

/**
 * Vrátí další zprávu k odeslání.
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
 * Aktualizuje čas poslední aktivity.
 */
void Client::updateLastActivity() {
    lastActivity = time(nullptr);
}

/**
 * Kontroluje, zda klient není timed out.
 */
bool Client::isTimedOut(int timeoutSeconds) const {
    time_t now = time(nullptr);
    return (now - lastActivity) > timeoutSeconds;
}

/**
 * Generuje náhodné session ID.
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

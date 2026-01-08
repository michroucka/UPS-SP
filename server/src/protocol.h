#ifndef PROTOCOL_H
#define PROTOCOL_H

#include <string>
#include <vector>

/**
 * Constants and utility functions for protocol operations.
 */
namespace Protocol {
    // Protocol constants
    const char DELIMITER = '|';
    const char MESSAGE_END = '\n';
    const int MAX_MESSAGE_SIZE = 4096;
    const int MAX_INVALID_MESSAGES = 3;

    // Timeouts (in seconds)
    const int RECONNECT_TIMEOUT = 30;

    // Commands client -> server
    const std::string CMD_LOGIN = "LOGIN";
    const std::string CMD_PING = "PING";
    const std::string CMD_DISCONNECT = "DISCONNECT";
    const std::string CMD_ROOM_LIST = "ROOM_LIST";
    const std::string CMD_CREATE_ROOM = "CREATE_ROOM";
    const std::string CMD_JOIN_ROOM = "JOIN_ROOM";
    const std::string CMD_LEAVE_ROOM = "LEAVE_ROOM";
    const std::string CMD_PLAY_CARD = "PLAY_CARD";
    const std::string CMD_HIT = "HIT";
    const std::string CMD_STAND = "STAND";
    const std::string CMD_RECONNECT = "RECONNECT";

    // Commands server -> client
    const std::string CMD_OK = "OK";
    const std::string CMD_ERROR = "ERROR";
    const std::string CMD_PONG = "PONG";
    const std::string CMD_ROOMS = "ROOMS";
    const std::string CMD_ROOM = "ROOM";
    const std::string CMD_ROOM_CREATED = "ROOM_CREATED";
    const std::string CMD_JOINED = "JOINED";
    const std::string CMD_GAME_START = "GAME_START";
    const std::string CMD_DEAL_CARDS = "DEAL_CARDS";
    const std::string CMD_GAME_STATE = "GAME_STATE";
    const std::string CMD_YOUR_TURN = "YOUR_TURN";
    const std::string CMD_CARD = "CARD";
    const std::string CMD_OPPONENT_ACTION = "OPPONENT_ACTION";
    const std::string CMD_ROUND_END = "ROUND_END";
    const std::string CMD_GAME_END = "GAME_END";
    const std::string CMD_PLAYER_DISCONNECTED = "PLAYER_DISCONNECTED";
    const std::string CMD_PLAYER_RECONNECTED = "PLAYER_RECONNECTED";
    const std::string CMD_OPPONENT_LEFT = "OPPONENT_LEFT";  // Opponent declined reconnect or timed out

    // Reconnect prompt messages
    const std::string CMD_RECONNECT_QUERY = "RECONNECT_QUERY";      // server -> client
    const std::string CMD_RECONNECT_ACCEPT = "RECONNECT_ACCEPT";    // client -> server
    const std::string CMD_RECONNECT_DECLINE = "RECONNECT_DECLINE";  // client -> server

    // ACK messages (client -> server)
    const std::string CMD_ACK_DEAL_CARDS = "ACK_DEAL_CARDS";
    const std::string CMD_ACK_ROUND_END = "ACK_ROUND_END";
    const std::string CMD_ACK_GAME_END = "ACK_GAME_END";
    const std::string CMD_ACK_GAME_STATE = "ACK_GAME_STATE";

    // Client states
    enum ClientState {
        CONNECTED,
        LOBBY,
        IN_ROOM,
        PLAYING
    };

    // Utility functions
    std::vector<std::string> parseMessage(const std::string& message);
    std::string buildMessage(const std::vector<std::string>& parts);
    std::string escapeString(const std::string& str);
    bool isValidNickname(const std::string& nickname);
}

#endif // PROTOCOL_H

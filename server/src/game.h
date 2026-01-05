#ifndef GAME_H
#define GAME_H

#include <vector>
#include <string>
#include <mutex>
#include "card.h"
#include "client.h"

#define INIT_HAND_SIZE 2
#define SCORE_TO_WIN 3

/**
 * Hráč ve hře.
 */
struct Player {
    Client* client;
    std::string nickname;  // Store nickname to avoid accessing invalid client pointer
    std::vector<Card> hand;
    int score;
    bool standing;
    bool busted;

    Player(Client* c) : client(c), nickname(c->getNickname()), score(0), standing(false), busted(false) {}

    int getHandValue() const {
        if (hasDoubleAce())
            return 21;

        int total = 0;
        for (const Card& card : hand) {
            total += card.getValue();
        }
        return total;
    }

    bool hasDoubleAce() const {
        bool hasFirst = false;
        for (const Card& card : hand) {
            if (card.getRank() != Card::ESO) {
                return false;
            }

            if (hasFirst) {
                return true;
            }
            hasFirst = true;
        }

        return false;
    }

    void reset() {
        hand.clear();
        standing = false;
        busted = false;
    }

    std::string getHandString() const {
        std::string result;
        for (size_t i = 0; i < hand.size(); i++) {
            if (i > 0) result += ",";
            result += hand[i].toString();
        }
        return result;
    }
};

/**
 * Stav hry.
 */
enum GameState {
    WAITING_FOR_PLAYERS,
    PLAYING,
    ROUND_ENDED,
    GAME_ENDED
};

/**
 * Hra Oko Bere pro 2 hráče.
 */
class Game {
public:
    Game(int gameId);
    ~Game();

    void addPlayer(Client* client);
    bool canStart() const;
    void start();

    void playerHit(Client* client);
    void playerStand(Client* client);

    bool isPlayerTurn(Client* client) const;
    GameState getState() const { return state; }
    int getCurrentRound() const { return currentRound; }

    const Player* getPlayer(Client* client) const;
    const Player* getOpponent(Client* client) const;
    Player* getPlayer(Client* client);
    Player* getOpponent(Client* client);

    bool isGameOver() const;
    std::string getWinner() const;

    // Public for reconnect support
    void notifyGameState();
    void notifyYourTurn(Player* player);
    std::string getPlayerRole(Client* client) const;
    Player* getPlayerByNickname(const std::string& nickname);
    Player* getOpponentByNickname(const std::string& nickname);

private:
    void dealInitialCards();
    void checkRoundEnd();
    void endRound();
    void endGame();
    void switchTurns();
    void notifyDealCards(Player* player);
    void notifyOpponentAction(Player* player, const std::string& action, const std::string& data = "");

    int gameId;
    GameState state;
    Deck deck;

    Player* player1;
    Player* player2;
    Player* currentPlayer;

    int currentRound;
    bool player1IsBanker;  // true = player1 is BANKER, false = player1 is PLAYER

    mutable std::mutex gameMutex;  // For thread safety
};

#endif // GAME_H

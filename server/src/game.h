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
 * Player in the game.
 */
struct Player {
    Client* client;
    std::string nickname;  // Store nickname to avoid accessing invalid client pointer
    std::vector<Card> hand;
    int score;
    bool standing;
    bool busted;

    /**
     * Player constructor.
     * @param c Pointer to client connection
     */
    Player(Client* c) : client(c), nickname(c->getNickname()), score(0), standing(false), busted(false) {}

    /**
     * Calculates the value of player's hand.
     * Special case: two aces = automatically 21
     * @return Total value of cards in hand
     */
    int getHandValue() const {
        if (hasDoubleAce())
            return 21;

        int total = 0;
        for (const Card& card : hand) {
            total += card.getValue();
        }
        return total;
    }

    /**
     * Checks if player has two aces (special rule = 21).
     * @return true if player has exactly two aces, false otherwise
     */
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

    /**
     * Resets player state for new round.
     * Clears cards, standing and busted flags.
     */
    void reset() {
        hand.clear();
        standing = false;
        busted = false;
    }

    /**
     * Returns cards in hand as string for protocol.
     * @return Cards separated by commas (e.g. "SRDCE-ESO,KULE-KRAL")
     */
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
 * Game state.
 */
enum GameState {
    WAITING_FOR_PLAYERS,
    PLAYING,
    ROUND_ENDED,
    GAME_ENDED
};

/**
 * Oko Bere game for 2 players.
 */
class Game {
public:
    /**
     * Game constructor.
     * @param gameId Unique game ID
     */
    Game(int gameId);

    /**
     * Game destructor - releases players.
     */
    ~Game();

    /**
     * Adds player to the game.
     * @param client Pointer to client
     */
    void addPlayer(Client* client);

    /**
     * Checks if game can start (2 players).
     * @return true if 2 players, false otherwise
     */
    bool canStart() const;

    /**
     * Starts the game - deals cards, sets roles, notifies players.
     */
    void start();

    /**
     * Processes HIT action (player takes a card).
     * @param client Client performing the action
     */
    void playerHit(Client* client);

    /**
     * Processes STAND action (player stays).
     * @param client Client performing the action
     */
    void playerStand(Client* client);

    /**
     * Checks if given player is on turn.
     * @param client Client to verify
     * @return true if client is on turn, false otherwise
     */
    bool isPlayerTurn(Client* client) const;

    /**
     * Returns current game state.
     * @return Game state (WAITING_FOR_PLAYERS, PLAYING, ROUND_ENDED, GAME_ENDED)
     */
    GameState getState() const { return state; }

    /**
     * Returns current round number.
     * @return Round number (1, 2, 3, ...)
     */
    int getCurrentRound() const { return currentRound; }

    /**
     * Finds player by client (const version).
     * @param client Client to find
     * @return Pointer to player or nullptr
     */
    const Player* getPlayer(Client* client) const;

    /**
     * Finds opponent by client (const version).
     * @param client Client to find
     * @return Pointer to opponent or nullptr
     */
    const Player* getOpponent(Client* client) const;

    /**
     * Finds player by client.
     * @param client Client to find
     * @return Pointer to player or nullptr
     */
    Player* getPlayer(Client* client);

    /**
     * Finds opponent by client.
     * @param client Client to find
     * @return Pointer to opponent or nullptr
     */
    Player* getOpponent(Client* client);

    /**
     * Checks if game is over (someone reached SCORE_TO_WIN victories).
     * @return true if game is over, false otherwise
     */
    bool isGameOver() const;

    /**
     * Returns winner's nickname.
     * @return Winner's nickname or empty string
     */
    std::string getWinner() const;

    /**
     * Sends current game state to all players (GAME_STATE).
     * Used at round start and after reconnect.
     */
    void notifyGameState();

    /**
     * Notifies player that it's their turn (YOUR_TURN).
     * @param player Player to notify
     */
    void notifyYourTurn(Player* player);

    /**
     * Returns player's role in current round.
     * @param client Client to verify
     * @return "PLAYER" or "BANKER"
     */
    std::string getPlayerRole(Client* client) const;

    /**
     * Finds player by nickname (for reconnect).
     * @param nickname Nickname to find
     * @return Pointer to player or nullptr
     */
    Player* getPlayerByNickname(const std::string& nickname);

    /**
     * Finds opponent by nickname (for reconnect).
     * @param nickname Nickname to find
     * @return Pointer to opponent or nullptr
     */
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

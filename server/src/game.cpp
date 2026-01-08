#include "game.h"
#include "protocol.h"
#include "logger.h"
#include <sstream>
#include <thread>
#include <chrono>

Game::Game(int gameId)
    : gameId(gameId), state(WAITING_FOR_PLAYERS), player1(nullptr), player2(nullptr),
      currentPlayer(nullptr), currentRound(0), player1IsBanker(false) {
    deck.shuffle();
}

Game::~Game() {
    delete player1;
    delete player2;
}

void Game::addPlayer(Client* client) {
    if (!player1) {
        player1 = new Player(client);
    } else if (!player2) {
        player2 = new Player(client);
    }
}

bool Game::canStart() const {
    return player1 && player2;
}

void Game::start() {
    if (!canStart()) {
        return;
    }

    state = PLAYING;
    currentRound = 1;
    currentPlayer = player1IsBanker ? player2 : player1;  // PLAYER starts first

    LOG_INFO("Game " + std::to_string(gameId) + " starting");

    // Notify game start (role depends on player1IsBanker)
    std::string role1 = player1IsBanker ? "BANKER" : "PLAYER";
    std::string role2 = player1IsBanker ? "PLAYER" : "BANKER";

    player1->client->queueMessage(Protocol::buildMessage({
        Protocol::CMD_GAME_START, role1, player2->client->getNickname()
    }));

    player2->client->queueMessage(Protocol::buildMessage({
        Protocol::CMD_GAME_START, role2, player1->client->getNickname()
    }));

    // Reset players for new round
    player1->reset();
    player2->reset();

    // Deal initial cards
    dealInitialCards();

    // Notify game state
    notifyGameState();

    // Get PLAYER and BANKER
    Player* playerRole = player1IsBanker ? player2 : player1;
    Player* bankerRole = player1IsBanker ? player1 : player2;

    // Check double ace for PLAYER
    if (playerRole->hasDoubleAce()) {
        playerRole->standing = true;

        // Send YOUR_TURN so client knows to wait
        notifyYourTurn(playerRole);

        // Immediately call stand
        playerRole->client->queueMessage(Protocol::buildMessage({
            Protocol::CMD_OK
        }));

        // Notify BANKER
        if (bankerRole) {
            notifyOpponentAction(bankerRole, "STAND", "");
        }

        // Notify PLAYER to wait for opponent
        notifyOpponentAction(playerRole, "HIT", "");

        // Switch to BANKER
        switchTurns();

        // Check double ace for BANKER
        if (bankerRole->hasDoubleAce()) {
            bankerRole->standing = true;

            notifyYourTurn(bankerRole);

            bankerRole->client->queueMessage(Protocol::buildMessage({
                Protocol::CMD_OK
            }));

            notifyOpponentAction(playerRole, "STAND", "");

            checkRoundEnd();
        } else {
            notifyYourTurn(bankerRole);
        }
    } else {
        // Notify PLAYER that it's their turn
        notifyYourTurn(playerRole);
    }
}

void Game::dealInitialCards() {
    Player* playerRole = player1IsBanker ? player2 : player1;
    Player* bankerRole = player1IsBanker ? player1 : player2;

    // Deal cards
    for (int i = 0; i < INIT_HAND_SIZE; i++) {
        playerRole->hand.push_back(deck.draw());
        bankerRole->hand.push_back(deck.draw());
    }
    notifyDealCards(playerRole);
    notifyDealCards(bankerRole);
}

void Game::playerHit(Client* client) {
    std::lock_guard<std::mutex> lock(gameMutex);  // Thread safety

    Player* player = getPlayer(client);
    if (!player) {
        LOG_WARNING("playerHit: client not in this game");
        return;
    }

    // VALIDATION: Check game state
    if (state != PLAYING) {
        client->queueMessage(Protocol::buildMessage({Protocol::CMD_ERROR, "Game not in state PLAYING"}));
        LOG_WARNING("playerHit: HIT attempt in state " + std::to_string(state));
        return;
    }

    if (currentPlayer != player) {
        client->queueMessage(Protocol::buildMessage({Protocol::CMD_ERROR, "Not your turn"}));
        return;
    }

    if (player->standing || player->busted) {
        client->queueMessage(Protocol::buildMessage({Protocol::CMD_ERROR, "Your turn ended"}));
        return;
    }

    // Send OK confirmation (same as playerStand)
    client->queueMessage(Protocol::buildMessage({ Protocol::CMD_OK }));

    // Add card
    Card card = deck.draw();
    player->hand.push_back(card);

    // Send card to player
    client->queueMessage(Protocol::buildMessage({Protocol::CMD_CARD, card.toString()}));

    // Notify opponent
    Player* opponent = getOpponent(client);
    if (opponent) {
        notifyOpponentAction(opponent, "HIT", "");
    }

    if (player->hasDoubleAce()) {
        playerStand(player->client);
    }

    // Check busted
    if (player->getHandValue() > 21) {
        player->busted = true;
        LOG_INFO("Player " + client->getNickname() + " busted with value " + std::to_string(player->getHandValue()));

        // Notify opponent
        if (opponent) {
            notifyOpponentAction(opponent, "BUSTED", "");
        }

        checkRoundEnd();
    } else {
        // Player can continue
        notifyYourTurn(player);
    }
}

void Game::playerStand(Client* client) {
    std::lock_guard<std::mutex> lock(gameMutex);  // Thread safety

    Player* player = getPlayer(client);
    if (!player) {
        LOG_WARNING("playerStand: client not in this game");
        return;
    }

    // VALIDATION: Check game state
    if (state != PLAYING) {
        client->queueMessage(Protocol::buildMessage({Protocol::CMD_ERROR, "Game not in state PLAYING"}));
        LOG_WARNING("playerStand: STAND attempt in state " + std::to_string(state));
        return;
    }

    if (currentPlayer != player) {
        client->queueMessage(Protocol::buildMessage({Protocol::CMD_ERROR, "Not your turn"}));
        return;
    }

    if (player->standing || player->busted) {
        client->queueMessage(Protocol::buildMessage({Protocol::CMD_ERROR, "Your turn ended"}));
        return;
    }

    player->standing = true;
    client->queueMessage(Protocol::buildMessage({ Protocol::CMD_OK }));

    // Notify opponent
    Player* opponent = getOpponent(client);
    if (opponent) {
        notifyOpponentAction(opponent, "STAND", "");
    }

    // Determine who is PLAYER and who is BANKER
    Player* playerRole = player1IsBanker ? player2 : player1;
    Player* bankerRole = player1IsBanker ? player1 : player2;

    // If this is PLAYER, switch to BANKER
    if (player == playerRole) {
        switchTurns();

        if (bankerRole->hasDoubleAce()) {
            bankerRole->standing = true;

            // Send YOUR_TURN so client knows it's a new round
            notifyYourTurn(bankerRole);

            // Notify PLAYER
            if (playerRole) {
                notifyOpponentAction(playerRole, "STAND", "");
            }

            // End of round - both are standing
            checkRoundEnd();
        } else {
            // BANKER doesn't have double ace, it's their turn
            notifyYourTurn(bankerRole);
        }
    } else {
        // BANKER is standing, end of round
        checkRoundEnd();
    }
}

void Game::switchTurns() {
    if (currentPlayer == player1) {
        currentPlayer = player2;
    } else {
        currentPlayer = player1;
    }
}

void Game::checkRoundEnd() {
    // Round ends when:
    // 1. Someone busted
    // 2. Both are standing

    if (player1->busted || player2->busted || (player1->standing && player2->standing)) {
        endRound();
    }
}

void Game::endRound() {
    LOG_INFO("End of round " + std::to_string(currentRound));

    int val1 = player1->getHandValue();
    int val2 = player2->getHandValue();

    std::string winner;

    // Evaluation
    if (player1->busted) {
        winner = "OPPONENT";  // For player1 this means they lost
        player2->score++;
    } else if (player2->busted) {
        winner = "YOU";  // For player1 this means they won
        player1->score++;
    } else if (val1 > val2) {
        winner = "YOU";
        player1->score++;
    } else if (val2 > val1) {
        winner = "OPPONENT";
        player2->score++;
    } else {
        // Same value - BANKER wins
        if (player1IsBanker) {
            winner = "YOU";  // player1 is BANKER, wins
            player1->score++;
        } else {
            winner = "OPPONENT";  // player2 is BANKER, wins
            player2->score++;
        }
    }

    // Notify players about ROUND_END
    player1->client->queueMessage(Protocol::buildMessage({
        Protocol::CMD_ROUND_END,
        winner,
        std::to_string(val1),
        std::to_string(val2),
        player1->getHandString(),
        player2->getHandString()
    }));

    // For player2 the winner is reversed
    std::string winner2 = winner;
    if (winner == "YOU") winner2 = "OPPONENT";
    else if (winner == "OPPONENT") winner2 = "YOU";

    player2->client->queueMessage(Protocol::buildMessage({
        Protocol::CMD_ROUND_END,
        winner2,
        std::to_string(val2),
        std::to_string(val1),
        player2->getHandString(),
        player1->getHandString()
    }));

    state = ROUND_ENDED;

    // Check game end - game ends when player reaches SCORE_TO_WIN (3 victories)
    if (player1->score >= SCORE_TO_WIN || player2->score >= SCORE_TO_WIN) {
        endGame();
    } else {
        // Next round - swap roles
        currentRound++;
        player1IsBanker = !player1IsBanker;  // Swap roles!

        // Reset players
        player1->reset();
        player2->reset();

        // Get PLAYER and BANKER for new round
        Player* playerRole = player1IsBanker ? player2 : player1;
        Player* bankerRole = player1IsBanker ? player1 : player2;

        currentPlayer = playerRole;  // PLAYER starts

        dealInitialCards();

        state = PLAYING;
        notifyGameState();

        // Check double ace for PLAYER
        if (playerRole->hasDoubleAce()) {
            playerRole->standing = true;

            // Send YOUR_TURN so client knows it's a new round
            notifyYourTurn(playerRole);

            // Immediately call stand
            playerRole->client->queueMessage(Protocol::buildMessage({
                Protocol::CMD_OK
            }));

            // Notify BANKER
            if (bankerRole) {
                notifyOpponentAction(bankerRole, "STAND", "");
            }

            // Notify PLAYER to wait for opponent
            notifyOpponentAction(playerRole, "HIT", "");

            // Switch to BANKER
            switchTurns();

            // Check double ace for BANKER
            if (bankerRole->hasDoubleAce()) {
                bankerRole->standing = true;

                notifyYourTurn(bankerRole);

                bankerRole->client->queueMessage(Protocol::buildMessage({
                    Protocol::CMD_OK
                }));

                notifyOpponentAction(playerRole, "STAND", "");

                checkRoundEnd();
            } else {
                notifyYourTurn(bankerRole);
            }
        } else {
            notifyYourTurn(playerRole);
        }
    }
}

void Game::endGame() {
    LOG_INFO("End of game " + std::to_string(gameId));

    std::string winner1;
    std::string winner2;

    if (player1->score > player2->score) {
        winner1 = "YOU";
        winner2 = "OPPONENT";
    } else if (player2->score > player1->score) {
        winner1 = "OPPONENT";
        winner2 = "YOU";
    } else {
        winner1 = winner2 = "TIE";
    }

    player1->client->queueMessage(Protocol::buildMessage({
        Protocol::CMD_GAME_END,
        winner1,
        std::to_string(player1->score),
        std::to_string(player2->score)
    }));

    player2->client->queueMessage(Protocol::buildMessage({
        Protocol::CMD_GAME_END,
        winner2,
        std::to_string(player2->score),
        std::to_string(player1->score)
    }));

    state = GAME_ENDED;

    // Player state will be updated in Room::checkAndHandleGameEnd()
}

bool Game::isPlayerTurn(Client* client) const {
    if (currentPlayer && currentPlayer->client == client) {
        return true;
    }
    return false;
}

Player* Game::getPlayer(Client* client) {
    if (player1 && player1->client == client) return player1;
    if (player2 && player2->client == client) return player2;
    return nullptr;
}

const Player* Game::getPlayer(Client* client) const {
    if (player1 && player1->client == client) return player1;
    if (player2 && player2->client == client) return player2;
    return nullptr;
}

Player* Game::getOpponent(Client* client) {
    if (player1 && player1->client == client) return player2;
    if (player2 && player2->client == client) return player1;
    return nullptr;
}

std::string Game::getPlayerRole(Client* client) const {
    if (!client) return "";

    if (player1 && player1->client == client) {
        return player1IsBanker ? "BANKER" : "PLAYER";
    }
    if (player2 && player2->client == client) {
        return player1IsBanker ? "PLAYER" : "BANKER";
    }
    return "";
}

Player* Game::getPlayerByNickname(const std::string& nickname) {
    if (player1 && player1->nickname == nickname) {
        return player1;
    }
    if (player2 && player2->nickname == nickname) {
        return player2;
    }
    return nullptr;
}

Player* Game::getOpponentByNickname(const std::string& nickname) {
    if (player1 && player1->nickname == nickname) {
        return player2;
    }
    if (player2 && player2->nickname == nickname) {
        return player1;
    }
    return nullptr;
}

const Player* Game::getOpponent(Client* client) const {
    if (player1 && player1->client == client) return player2;
    if (player2 && player2->client == client) return player1;
    return nullptr;
}

bool Game::isGameOver() const {
    return state == GAME_ENDED;
}

std::string Game::getWinner() const {
    if (state != GAME_ENDED) return "";

    if (player1->score > player2->score) {
        return player1->client->getNickname();
    } else if (player2->score > player1->score) {
        return player2->client->getNickname();
    }
    return "TIE";
}

void Game::notifyGameState() {
    // Determine roles based on player1IsBanker
    std::string role1 = player1IsBanker ? "BANKER" : "PLAYER";
    std::string role2 = player1IsBanker ? "PLAYER" : "BANKER";

    if (player1) {
        player1->client->queueMessage(Protocol::buildMessage({
            Protocol::CMD_GAME_STATE,
            std::to_string(currentRound),
            std::to_string(player1->score),
            std::to_string(player2 ? player2->score : 0),
            role1,
            currentPlayer == player1 ? role1 : (currentPlayer == player2 ? role2 : "WAITING")
        }));
    }

    if (player2) {
        player2->client->queueMessage(Protocol::buildMessage({
            Protocol::CMD_GAME_STATE,
            std::to_string(currentRound),
            std::to_string(player2->score),
            std::to_string(player1 ? player1->score : 0),
            role2,
            currentPlayer == player2 ? role2 : (currentPlayer == player1 ? role1 : "WAITING")
        }));
    }
}

void Game::notifyDealCards(Player* player) {
    std::vector<std::string> msg;
    msg.push_back(Protocol::CMD_DEAL_CARDS);
    msg.push_back(std::to_string(player->hand.size()));

    for (const Card& card : player->hand) {
        msg.push_back(card.toString());
    }

    player->client->queueMessage(Protocol::buildMessage(msg));
}

void Game::notifyYourTurn(Player* player) {
    player->client->queueMessage(Protocol::buildMessage({
        Protocol::CMD_YOUR_TURN,
        "NONE"  // We don't have table card yet
    }));
}

void Game::notifyOpponentAction(Player* player, const std::string& action, const std::string& data) {
    player->client->queueMessage(Protocol::buildMessage({
        Protocol::CMD_OPPONENT_ACTION,
        action,
        data
    }));
}

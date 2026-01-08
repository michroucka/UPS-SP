#ifndef CARD_H
#define CARD_H

#include <algorithm>
#include <string>
#include <vector>
#include <random>

/**
 * Representation of a game card.
 */
class Card {
public:
    enum Suit { SRDCE, KULE, LISTY, ZALUDY };
    enum Rank { SEDM, OSM, DEVET, DESET, SPODEK, SVRSEK, KRAL, ESO };

    /**
     * Card constructor.
     * @param suit Card suit
     * @param rank Card rank
     */
    Card(Suit suit, Rank rank) : suit(suit), rank(rank) {}

    /** Returns card suit */
    Suit getSuit() const { return suit; }

    /** Returns card rank */
    Rank getRank() const { return rank; }

    /**
     * Returns point value of card according to Oko Bere rules.
     * @return Card value (7-11)
     */
    int getValue() const {
        switch (rank) {
            case SEDM: return 7;
            case OSM: return 8;
            case DEVET: return 9;
            case DESET: return 10;
            case SPODEK:
            case SVRSEK:
                return 1;
            case KRAL: return 2;
            case ESO: return 11;
            default: return 0;
        }
    }

    /**
     * Returns string representation of card for protocol.
     * @return Format "SUIT-RANK" (e.g. "SRDCE-ESO")
     */
    std::string toString() const {
        std::string suitStr;
        switch (suit) {
            case SRDCE: suitStr = "SRDCE"; break;
            case KULE: suitStr = "KULE"; break;
            case LISTY: suitStr = "LISTY"; break;
            case ZALUDY: suitStr = "ZALUDY"; break;
        }

        std::string rankStr;
        switch (rank) {
            case SEDM: rankStr = "SEDM"; break;
            case OSM: rankStr = "OSM"; break;
            case DEVET: rankStr = "DEVET"; break;
            case DESET: rankStr = "DESET"; break;
            case SPODEK: rankStr = "SPODEK"; break;
            case SVRSEK: rankStr = "SVRSEK"; break;
            case KRAL: rankStr = "KRAL"; break;
            case ESO: rankStr = "ESO"; break;
        }

        return suitStr + "-" + rankStr;
    }

private:
    Suit suit;
    Rank rank;
};

/**
 * Deck of cards - 32 cards from Marias deck.
 */
class Deck {
public:
    /**
     * Deck constructor - creates and resets deck.
     */
    Deck() {
        reset();
    }

    /**
     * Resets deck to 32 cards (all suits and ranks).
     */
    void reset() {
        cards.clear();
        for (int s = Card::SRDCE; s <= Card::ZALUDY; s++) {
            for (int r = Card::SEDM; r <= Card::ESO; r++) {
                cards.push_back(Card(static_cast<Card::Suit>(s), static_cast<Card::Rank>(r)));
            }
        }
    }

    /**
     * Shuffles deck using Mersenne Twister generator.
     */
    void shuffle() {
        std::random_device rd;
        std::mt19937 g(rd());
        std::shuffle(cards.begin(), cards.end(), g);
    }

    /**
     * Draws card from deck.
     * If deck runs out, automatically resets and shuffles.
     * @return Next card from deck
     */
    Card draw() {
        if (cards.empty()) {
            reset();
            shuffle();
        }
        Card card = cards.back();
        cards.pop_back();
        return card;
    }

    /**
     * Returns number of cards remaining in deck.
     * @return Number of cards
     */
    size_t size() const {
        return cards.size();
    }

private:
    std::vector<Card> cards;
};

#endif // CARD_H

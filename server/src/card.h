#ifndef CARD_H
#define CARD_H

#include <algorithm>
#include <string>
#include <vector>
#include <random>

/**
 * Reprezentace herní karty.
 */
class Card {
public:
    enum Suit { SRDCE, KULE, LISTY, ZALUDY };
    enum Rank { SEDM, OSM, DEVET, DESET, SPODEK, SVRSEK, KRAL, ESO };

    Card(Suit suit, Rank rank) : suit(suit), rank(rank) {}

    Suit getSuit() const { return suit; }
    Rank getRank() const { return rank; }

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
 * Balíček karet.
 */
class Deck {
public:
    Deck() {
        reset();
    }

    void reset() {
        cards.clear();
        for (int s = Card::SRDCE; s <= Card::ZALUDY; s++) {
            for (int r = Card::SEDM; r <= Card::ESO; r++) {
                cards.push_back(Card(static_cast<Card::Suit>(s), static_cast<Card::Rank>(r)));
            }
        }
    }

    void shuffle() {
        std::random_device rd;
        std::mt19937 g(rd());
        std::shuffle(cards.begin(), cards.end(), g);
    }

    Card draw() {
        if (cards.empty()) {
            reset();
            shuffle();
        }
        Card card = cards.back();
        cards.pop_back();
        return card;
    }

    size_t size() const {
        return cards.size();
    }

private:
    std::vector<Card> cards;
};

#endif // CARD_H

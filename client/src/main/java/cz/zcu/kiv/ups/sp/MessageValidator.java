package cz.zcu.kiv.ups.sp;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Utility class for validating protocol messages and their parameters.
 * Provides comprehensive validation to prevent malformed or malicious data.
 */
public class MessageValidator {

    // Valid card suits and ranks for Oko Bere
    private static final Set<String> VALID_SUITS = new HashSet<>(Arrays.asList(
        "SRDCE", "KULE", "ZALUDY", "LISTY"
    ));

    private static final Set<String> VALID_RANKS = new HashSet<>(Arrays.asList(
        "SEDM", "OSM", "DEVET", "DESET", "SPODEK", "SVRSEK", "KRAL", "ESO"
    ));

    // Valid roles
    private static final Set<String> VALID_ROLES = new HashSet<>(Arrays.asList(
        "BANKER", "PLAYER"
    ));

    // Valid opponent actions
    private static final Set<String> VALID_ACTIONS = new HashSet<>(Arrays.asList(
        "HIT", "STAND", "BUSTED", "PLAYED_CARD"
    ));

    // Valid round/game end winners
    private static final Set<String> VALID_WINNERS = new HashSet<>(Arrays.asList(
        "YOU", "OPPONENT", "TIE"
    ));

    // Card format regex: SUIT-RANK
    private static final Pattern CARD_PATTERN = Pattern.compile("^(SRDCE|KULE|ZALUDY|LISTY)-(SEDM|OSM|DEVET|DESET|SPODEK|SVRSEK|KRAL|ESO)$");

    // Reasonable limits
    private static final int MAX_ROOM_COUNT = 1000;
    private static final int MAX_CARD_COUNT = 20;  // Max cards in hand
    private static final int MAX_ROOM_ID = 999999;
    private static final int MAX_SCORE = 100;
    private static final int MAX_ROUND = 1000;
    private static final int MAX_HAND_VALUE = 100;

    /**
     * Validates that a parameter is not null
     * @param param parameter to check
     * @param paramName parameter name for error message
     * @return true if valid, false otherwise
     */
    public static boolean validateNotNull(String param, String paramName) {
        if (param == null) {
            System.err.println("Validation error: " + paramName + " is null");
            return false;
        }
        return true;
    }

    /**
     * Validates parameter count
     * @param msg message to validate
     * @param expectedCount expected number of parameters
     * @return true if valid, false otherwise
     */
    public static boolean validateParameterCount(ProtocolMessage msg, int expectedCount) {
        if (msg.getParameterCount() < expectedCount) {
            System.err.println("Validation error: Expected " + expectedCount + " parameters, got " + msg.getParameterCount());
            return false;
        }
        return true;
    }

    /**
     * Validates an integer parameter and its range
     * @param param parameter string
     * @param paramName parameter name for error message
     * @param min minimum value (inclusive)
     * @param max maximum value (inclusive)
     * @return parsed integer or null if invalid
     */
    public static Integer validateIntRange(String param, String paramName, int min, int max) {
        if (!validateNotNull(param, paramName)) {
            return null;
        }

        try {
            int value = Integer.parseInt(param);
            if (value < min || value > max) {
                System.err.println("Validation error: " + paramName + " value " + value + " out of range [" + min + ", " + max + "]");
                return null;
            }
            return value;
        } catch (NumberFormatException e) {
            System.err.println("Validation error: " + paramName + " is not a valid integer: " + param);
            return null;
        }
    }

    /**
     * Validates room count
     */
    public static Integer validateRoomCount(String param) {
        return validateIntRange(param, "roomCount", 0, MAX_ROOM_COUNT);
    }

    /**
     * Validates card count
     */
    public static Integer validateCardCount(String param) {
        return validateIntRange(param, "cardCount", 0, MAX_CARD_COUNT);
    }

    /**
     * Validates room ID
     */
    public static Integer validateRoomId(String param) {
        return validateIntRange(param, "roomId", 1, MAX_ROOM_ID);
    }

    /**
     * Validates score value
     */
    public static Integer validateScore(String param) {
        return validateIntRange(param, "score", 0, MAX_SCORE);
    }

    /**
     * Validates round number
     */
    public static Integer validateRound(String param) {
        return validateIntRange(param, "round", 1, MAX_ROUND);
    }

    /**
     * Validates hand value
     */
    public static Integer validateHandValue(String param) {
        return validateIntRange(param, "handValue", 0, MAX_HAND_VALUE);
    }

    /**
     * Validates card format
     * @param card card string (e.g., "SRDCE-ESO")
     * @return true if valid, false otherwise
     */
    public static boolean validateCardFormat(String card) {
        if (!validateNotNull(card, "card")) {
            return false;
        }

        if (!CARD_PATTERN.matcher(card).matches()) {
            System.err.println("Validation error: Invalid card format: " + card);
            return false;
        }

        String[] parts = card.split("-");
        if (parts.length != 2) {
            return false;
        }

        if (!VALID_SUITS.contains(parts[0]) || !VALID_RANKS.contains(parts[1])) {
            System.err.println("Validation error: Invalid card suit or rank: " + card);
            return false;
        }

        return true;
    }

    /**
     * Validates role
     */
    public static boolean validateRole(String role) {
        if (!validateNotNull(role, "role")) {
            return false;
        }

        if (!VALID_ROLES.contains(role)) {
            System.err.println("Validation error: Invalid role: " + role);
            return false;
        }

        return true;
    }

    /**
     * Validates opponent action
     */
    public static boolean validateOpponentAction(String action) {
        if (!validateNotNull(action, "action")) {
            return false;
        }

        if (!VALID_ACTIONS.contains(action)) {
            System.err.println("Validation error: Invalid opponent action: " + action);
            return false;
        }

        return true;
    }

    /**
     * Validates winner
     */
    public static boolean validateWinner(String winner) {
        if (!validateNotNull(winner, "winner")) {
            return false;
        }

        if (!VALID_WINNERS.contains(winner)) {
            System.err.println("Validation error: Invalid winner: " + winner);
            return false;
        }

        return true;
    }
}

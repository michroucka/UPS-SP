#include "protocol.h"
#include <sstream>
#include <algorithm>

namespace Protocol {

/**
 * Parses protocol message into parts.
 * @param message Message to parse (without \n at the end)
 * @return Vector containing individual message parts
 */
std::vector<std::string> parseMessage(const std::string& message) {
    std::vector<std::string> parts;
    std::stringstream ss(message);
    std::string part;

    while (std::getline(ss, part, DELIMITER)) {
        parts.push_back(part);
    }

    return parts;
}

/**
 * Builds protocol message from parts.
 * @param parts Vector containing message parts
 * @return Complete message including \n at the end
 */
std::string buildMessage(const std::vector<std::string>& parts) {
    if (parts.empty()) {
        return "\n";
    }

    std::string message;
    for (size_t i = 0; i < parts.size(); ++i) {
        if (i > 0) {
            message += DELIMITER;
        }
        message += parts[i];
    }
    message += MESSAGE_END;

    return message;
}

/**
 * Escapes special characters in string.
 * @param str String to escape
 * @return Escaped string
 */
std::string escapeString(const std::string& str) {
    std::string result = str;
    std::replace(result.begin(), result.end(), DELIMITER, '_');
    std::replace(result.begin(), result.end(), MESSAGE_END, ' ');
    return result;
}

/**
 * Validates nickname.
 * @param nickname Nickname to validate
 * @return true if nickname is valid, false otherwise
 */
bool isValidNickname(const std::string& nickname) {
    if (nickname.empty() || nickname.length() > 20) {
        return false;
    }

    bool hasNonWhitespace = false;

    // Check that it doesn't contain special characters and isn't only whitespace
    for (char c : nickname) {
        // VALIDATION: Reject delimiter, newline, carriage return
        if (c == DELIMITER || c == MESSAGE_END || c == '\r') {
            return false;
        }

        // VALIDATION: Reject control characters (0x00-0x1F except space, and 0x7F)
        if ((c >= 0 && c < 32 && c != ' ' && c != '\t') || c == 127) {
            return false;
        }

        // Track if we have at least one non-whitespace character
        if (c != ' ' && c != '\t') {
            hasNonWhitespace = true;
        }
    }

    // VALIDATION: Nickname must contain at least one non-whitespace character
    if (!hasNonWhitespace) {
        return false;
    }

    return true;
}

} // namespace Protocol

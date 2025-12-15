#include "protocol.h"
#include <sstream>
#include <algorithm>

namespace Protocol {

/**
 * Parsuje zprávu protokolu na jednotlivé části.
 * @param message Zpráva k parsování (bez \n na konci)
 * @return Vector obsahující jednotlivé části zprávy
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
 * Sestaví zprávu protokolu z jednotlivých částí.
 * @param parts Vector obsahující části zprávy
 * @return Kompletní zpráva včetně \n na konci
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
 * Escapuje speciální znaky v řetězci.
 * @param str Řetězec k escapování
 * @return Escapovaný řetězec
 */
std::string escapeString(const std::string& str) {
    std::string result = str;
    // Nahradí | za _
    std::replace(result.begin(), result.end(), DELIMITER, '_');
    // Nahradí \n za mezeru
    std::replace(result.begin(), result.end(), MESSAGE_END, ' ');
    return result;
}

/**
 * Validuje přezdívku.
 * @param nickname Přezdívka k validaci
 * @return true pokud je přezdívka validní, false jinak
 */
bool isValidNickname(const std::string& nickname) {
    if (nickname.empty() || nickname.length() > 20) {
        return false;
    }

    bool hasNonWhitespace = false;

    // Kontrola, že neobsahuje speciální znaky a není pouze whitespace
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

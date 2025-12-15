#include "logger.h"
#include <iostream>
#include <sstream>
#include <iomanip>

Logger::Logger() : consoleOutput(true) {}

Logger::~Logger() {
    if (logFile.is_open()) {
        logFile.close();
    }
    if (stateLogFile.is_open()) {
        stateLogFile.close();
    }
}

Logger& Logger::getInstance() {
    static Logger instance;
    return instance;
}

void Logger::log(Level level, const std::string& message) {
    std::lock_guard<std::mutex> lock(mutex);

    std::string timestamp = getCurrentTime();
    std::string levelStr = levelToString(level);
    std::string fullMessage = "[" + timestamp + "] [" + levelStr + "] " + message;

    if (consoleOutput) {
        std::cout << fullMessage << std::endl;
    }

    if (logFile.is_open()) {
        logFile << fullMessage << std::endl;
        logFile.flush();
    }
}

void Logger::setLogFile(const std::string& filename) {
    std::lock_guard<std::mutex> lock(mutex);
    if (logFile.is_open()) {
        logFile.close();
    }
    logFile.open(filename, std::ios::app);
    if (!logFile.is_open()) {
        std::cerr << "Unable to open log file: " << filename << std::endl;
    }
}

void Logger::setConsoleOutput(bool enabled) {
    consoleOutput = enabled;
}

std::string Logger::getCurrentTime() {
    time_t now = time(nullptr);
    struct tm* timeinfo = localtime(&now);

    std::ostringstream oss;
    oss << std::put_time(timeinfo, "%Y-%m-%d %H:%M:%S");
    return oss.str();
}

std::string Logger::levelToString(Level level) {
    switch (level) {
        case DEBUG: return "DEBUG";
        case INFO: return "INFO";
        case WARNING: return "WARNING";
        case ERROR: return "ERROR";
        default: return "UNKNOWN";
    }
}

void Logger::setStateLogFile(const std::string& filename) {
    std::lock_guard<std::mutex> lock(stateMutex);
    if (stateLogFile.is_open()) {
        stateLogFile.close();
    }
    stateLogFile.open(filename, std::ios::app);
    if (!stateLogFile.is_open()) {
        std::cerr << "Unable to open state log file: " << filename << std::endl;
    }
}

void Logger::logState(const std::string& eventType, const std::map<std::string, std::string>& data) {
    std::lock_guard<std::mutex> lock(stateMutex);

    if (!stateLogFile.is_open()) {
        return;
    }

    // Format: STATE|timestamp|eventType|key1=value1|key2=value2|...
    std::string logLine = "STATE|" + getTimestamp() + "|" + eventType;

    for (const auto& pair : data) {
        logLine += "|" + pair.first + "=" + pair.second;
    }

    stateLogFile << logLine << std::endl;
    stateLogFile.flush();
}

std::string Logger::getTimestamp() {
    time_t now = time(nullptr);
    return std::to_string(now);
}

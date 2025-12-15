#ifndef LOGGER_H
#define LOGGER_H

#include <string>
#include <fstream>
#include <mutex>
#include <ctime>
#include <map>

/**
 * Jednoduchý logger pro zaznamenávání událostí serveru.
 */
class Logger {
public:
    enum Level {
        DEBUG,
        INFO,
        WARNING,
        ERROR
    };

    static Logger& getInstance();

    void log(Level level, const std::string& message);
    void setLogFile(const std::string& filename);
    void setConsoleOutput(bool enabled);

    // State logging for persistence and recovery
    void setStateLogFile(const std::string& filename);
    void logState(const std::string& eventType, const std::map<std::string, std::string>& data);

private:
    Logger();
    ~Logger();
    Logger(const Logger&) = delete;
    Logger& operator=(const Logger&) = delete;

    std::string getCurrentTime();
    std::string getTimestamp();  // Unix timestamp for state logs
    std::string levelToString(Level level);

    std::ofstream logFile;
    std::ofstream stateLogFile;
    std::mutex mutex;
    std::mutex stateMutex;
    bool consoleOutput;
};

// Makra pro jednoduché použití
#define LOG_DEBUG(msg) Logger::getInstance().log(Logger::DEBUG, msg)
#define LOG_INFO(msg) Logger::getInstance().log(Logger::INFO, msg)
#define LOG_WARNING(msg) Logger::getInstance().log(Logger::WARNING, msg)
#define LOG_ERROR(msg) Logger::getInstance().log(Logger::ERROR, msg)
#define LOG_STATE(event, data) Logger::getInstance().logState(event, data)

#endif // LOGGER_H

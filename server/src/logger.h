#ifndef LOGGER_H
#define LOGGER_H

#include <string>
#include <fstream>
#include <mutex>
#include <ctime>

/**
 * Jednoduchý logger pro zaznamenávání událostí serveru.
 */
class Logger {
public:
    enum Level {
        INFO,
        WARNING,
        ERROR
    };

    static Logger& getInstance();

    void log(Level level, const std::string& message);
    void setLogFile(const std::string& filename);
    void setConsoleOutput(bool enabled);

private:
    Logger();
    ~Logger();
    Logger(const Logger&) = delete;
    Logger& operator=(const Logger&) = delete;

    std::string getCurrentTime();
    std::string levelToString(Level level);

    std::ofstream logFile;
    std::mutex mutex;
    bool consoleOutput;
};

// Makra pro jednoduché použití
#define LOG_INFO(msg) Logger::getInstance().log(Logger::INFO, msg)
#define LOG_WARNING(msg) Logger::getInstance().log(Logger::WARNING, msg)
#define LOG_ERROR(msg) Logger::getInstance().log(Logger::ERROR, msg)

#endif // LOGGER_H

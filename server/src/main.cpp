#include <iostream>
#include <csignal>
#include "server.h"
#include "logger.h"

// Global pointer to server for signal handler
Server* globalServer = nullptr;
int max_clients = 10;
int max_rooms = 5;

/**
 * Handler for SIGINT (Ctrl+C) - graceful shutdown.
 */
void signalHandler(int signal) {
    if (signal == SIGINT) {
        std::cout << "\nReceived SIGINT, terminating server..." << std::endl;
        if (globalServer) {
            globalServer->shutdown();
        }
        exit(0);
    }
}

/**
 * Prints program usage help.
 */
void printUsage(const char* programName) {
    std::cout << "Usage: " << programName << " <IP address> <port> [-c <max clients>] [-r <max rooms>]" << std::endl;
    std::cout << "Example: " << programName << " 127.0.0.1 10000 -c 5 -r 2" << std::endl;
}

int main(int argc, char* argv[]) {
    // Check arguments
    if (argc < 3 || argc > 7) {
        printUsage(argv[0]);
        return 1;
    }

    std::string address = argv[1];
    int port;

    try {
        port = std::stoi(argv[2]);
        if (port < 1024 || port > 65535) {
            std::cerr << "Error: Port must be in range 1024-65535" << std::endl;
            return 1;
        }
    } catch (const std::exception& e) {
        std::cerr << "Error: Invalid port" << std::endl;
        return 1;
    }

    for (int i = 3; i < argc; i += 2) {
        if (i + 1 >= argc) {
            printUsage(argv[0]);
            return 1;
        }

        std::string option = argv[i];

        try {
            if (option == "-c") {
                max_clients = std::stoi(argv[i + 1]);
                if (max_clients < 2) {
                    throw std::invalid_argument("max_clients");
                }
            } else if (option == "-r") {
                max_rooms = std::stoi(argv[i + 1]);
                if (max_rooms < 1) {
                    throw std::invalid_argument("max_rooms");
                }
            } else {
                printUsage(argv[0]);
                return 1;
            }
        } catch (...) {
            std::cerr << "Error: Invalid value for " << option << std::endl;
            return 1;
        }
    }


    // Logger setup
    Logger::getInstance().setLogFile("server.log");
    Logger::getInstance().setConsoleOutput(true);

    LOG_INFO("=== Oko Bere Server ===");
    LOG_INFO("Address: " + address);
    LOG_INFO("Port: " + std::to_string(port));

    // Create server
    Server server(address, port, max_clients, max_rooms);
    globalServer = &server;

    // Register signal handler for Ctrl+C
    signal(SIGINT, signalHandler);

    // Initialize and start
    if (!server.initialize()) {
        LOG_ERROR("Unable to initialize server");
        return 1;
    }

    server.run();

    return 0;
}

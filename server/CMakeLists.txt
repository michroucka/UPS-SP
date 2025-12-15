cmake_minimum_required(VERSION 3.10)
project(oko_bere_server)

set(CMAKE_CXX_STANDARD 17)

add_executable(oko_bere_server
        src/main.cpp
        src/server.cpp
        src/client.cpp
        src/room.cpp
        src/game.cpp
        src/protocol.cpp
        src/logger.cpp
)

target_link_libraries(oko_bere_server pthread)
# Makefile pro Oko Bere server

CXX = g++
CXXFLAGS = -std=c++17 -Wall -Wextra -g -pthread
TARGET = oko_bere_server
SRC_DIR = src
BUILD_DIR = build
IP ?= 127.0.0.1
PORT ?= 10000
C ?= 10
R ?= 10

# Všechny zdrojové soubory
SOURCES = $(wildcard $(SRC_DIR)/*.cpp)
OBJECTS = $(patsubst $(SRC_DIR)/%.cpp,$(BUILD_DIR)/%.o,$(SOURCES))

# Hlavní cíl
all: $(BUILD_DIR) $(TARGET)

# Vytvoření build adresáře
$(BUILD_DIR):
	mkdir -p $(BUILD_DIR)

# Linkování
$(TARGET): $(OBJECTS)
	$(CXX) $(CXXFLAGS) -o $@ $^
	@echo "Server zkompilován: ./$(TARGET)"

# Kompilace jednotlivých souborů
$(BUILD_DIR)/%.o: $(SRC_DIR)/%.cpp
	$(CXX) $(CXXFLAGS) -c -o $@ $<

# Spuštění serveru
run: all
	./$(TARGET) $(IP) $(PORT) -c $(C) -r $(R)

# Čištění
clean:
	rm -rf $(BUILD_DIR) $(TARGET)

clean_run: clean run

# Valgrind test
valgrind: all
	valgrind --leak-check=full --show-leak-kinds=all ./$(TARGET) $(IP) $(PORT) -c $(C) -r $(R)

.PHONY: all clean run clean_run valgrind

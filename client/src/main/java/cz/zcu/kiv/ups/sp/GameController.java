package cz.zcu.kiv.ups.sp;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import cz.zcu.kiv.ups.sp.Logger;

/**
 * JavaFX controller for the game GUI
 */
public class GameController {
    @FXML private VBox connectionPanel;
    @FXML private TextField serverHostField;
    @FXML private TextField serverPortField;
    @FXML private TextField nicknameField;
    @FXML private Button connectButton;
    @FXML private Button disconnectButton;
    @FXML private Label connectionStatus;

    @FXML private VBox lobbyPanel;
    @FXML private ListView<RoomInfo> roomListView;
    @FXML private TextField roomNameField;

    @FXML private VBox gamePanel;
    @FXML private VBox gameInfoContainer;
    @FXML private HBox gameInfoBox;
    @FXML private VBox cardsContainer;
    @FXML private VBox gameActionsContainer;
    @FXML private VBox waitingForOpponentArea;
    @FXML private Label roundLabel;
    @FXML private Label yourScoreLabel;
    @FXML private Label opponentScoreLabel;
    @FXML private Label roleLabel;
    @FXML private Label opponentNameLabel;
    @FXML private VBox waitingArea;
    @FXML private HBox opponentCardsBox;
    @FXML private Label opponentHandValueLabel;
    @FXML private HBox yourCardsBox;
    @FXML private Label handValueLabel;
    @FXML private Button hitButton;
    @FXML private Button standButton;

    @FXML private Label statusLabel;

    @FXML private VBox roundResultArea;
    @FXML private Label roundResultTitle;
    @FXML private Label roundResultMessage;

    private GameClient gameClient;
    private Thread messageReceiverThread;
    private Thread messageProcessorThread;
    private Thread waitForGameStartThread;
    private volatile boolean running = false;
    private final BlockingQueue<ProtocolMessage> asyncMessageQueue = new LinkedBlockingQueue<>();
    private final BlockingQueue<ProtocolMessage> syncResponseQueue = new LinkedBlockingQueue<>();

    // Reconnect state
    private volatile boolean isReconnecting = false;
    private volatile boolean manualDisconnect = false;
    private static final int MAX_AUTO_RECONNECT_ATTEMPTS = 5;
    private static final int SHORT_RECONNECT_DELAY_MS = 2000; // 2 seconds for short-term outage
    private static final int LONG_RECONNECT_DELAY_MS = 5000; // 5 seconds for long-term outage

    // Connection info for reconnect
    private String lastServerHost;
    private int lastServerPort;
    private String lastNickname;

    @FXML
    public void initialize() {
        CardImageLoader.preloadImages();
        updateStatus("Ready to connect");

        // Bind managed property to visible property for all conditional containers
        // This ensures they don't take up space when invisible
        waitingArea.managedProperty().bind(waitingArea.visibleProperty());
        roundResultArea.managedProperty().bind(roundResultArea.visibleProperty());
        gameInfoContainer.managedProperty().bind(gameInfoContainer.visibleProperty());
        cardsContainer.managedProperty().bind(cardsContainer.visibleProperty());
        gameActionsContainer.managedProperty().bind(gameActionsContainer.visibleProperty());
        waitingForOpponentArea.managedProperty().bind(waitingForOpponentArea.visibleProperty());

        // Setup custom cell factory for room list
        setupRoomListView();
    }

    private void setupRoomListView() {
        roomListView.setCellFactory(param -> new javafx.scene.control.ListCell<RoomInfo>() {
            private final javafx.scene.control.Button joinButton = new javafx.scene.control.Button("Join");
            private final javafx.scene.layout.HBox container = new javafx.scene.layout.HBox(10);
            private final javafx.scene.control.Label nameLabel = new javafx.scene.control.Label();
            private final javafx.scene.control.Label playersLabel = new javafx.scene.control.Label();
            private final javafx.scene.control.Label statusLabel = new javafx.scene.control.Label();

            {
                // Setup container
                container.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
                container.setPadding(new javafx.geometry.Insets(5));

                // Setup labels
                nameLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");
                nameLabel.setPrefWidth(200);

                playersLabel.setPrefWidth(60);
                playersLabel.setStyle("-fx-text-fill: #666;");

                statusLabel.setPrefWidth(80);

                // Setup button
                joinButton.setStyle("-fx-background-color: #4CAF50; -fx-text-fill: white;");
                joinButton.setPrefWidth(80);

                container.getChildren().addAll(nameLabel, playersLabel, statusLabel, joinButton);
            }

            @Override
            protected void updateItem(RoomInfo room, boolean empty) {
                super.updateItem(room, empty);

                if (empty || room == null) {
                    setGraphic(null);
                } else {
                    nameLabel.setText(room.getName());
                    playersLabel.setText(room.getPlayersDisplay());
                    statusLabel.setText(room.getStatus());

                    // Style status
                    if ("WAITING".equals(room.getStatus())) {
                        statusLabel.setStyle("-fx-text-fill: #4CAF50;");
                    } else {
                        statusLabel.setStyle("-fx-text-fill: #f44336;");
                    }

                    // Enable/disable join button
                    joinButton.setDisable(!room.canJoin());

                    // Handle join button click
                    joinButton.setOnAction(e -> joinRoomById(room.getId()));

                    setGraphic(container);
                }
            }
        });
    }

    @FXML
    private void handleConnect() {
        String host = serverHostField.getText().trim();
        String portStr = serverPortField.getText().trim();
        String nickname = nicknameField.getText().trim();

        if (nickname.isEmpty()) {
            showError("Please enter a nickname");
            return;
        }

        int port;
        try {
            port = Integer.parseInt(portStr);
        } catch (NumberFormatException e) {
            showError("Invalid port number");
            return;
        }

        // Save connection info for reconnect
        lastServerHost = host;
        lastServerPort = port;
        lastNickname = nickname;
        manualDisconnect = false;

        gameClient = new GameClient(host, port);
        updateStatus("Connecting to " + host + ":" + port + "...");

        new Thread(() -> {
            try {
                if (gameClient.connect()) {
                    Platform.runLater(() -> updateStatus("Logging in as " + nickname + "..."));

                    if (gameClient.login(nickname)) {
                        // Check if server sent RECONNECT_QUERY
                        if (gameClient.hasPendingReconnectQuery()) {

                            String opponentName = gameClient.getReconnectOpponentNickname();

                            // Show dialog on UI thread and wait for user decision
                            boolean[] userChoice = new boolean[1];
                            CountDownLatch dialogLatch = new CountDownLatch(1);

                            Platform.runLater(() -> {
                                Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
                                alert.setTitle("Reconnect to Game");
                                alert.setHeaderText("You were disconnected from a game");
                                alert.setContentText("You were playing against " + opponentName + ".\n\nDo you want to reconnect and continue the game?");

                                ButtonType yesButton = new ButtonType("Yes", ButtonBar.ButtonData.YES);
                                ButtonType noButton = new ButtonType("No", ButtonBar.ButtonData.NO);
                                alert.getButtonTypes().setAll(yesButton, noButton);

                                Optional<ButtonType> result = alert.showAndWait();
                                userChoice[0] = result.isPresent() && result.get() == yesButton;
                                dialogLatch.countDown();
                            });

                            // Wait for user decision
                            try {
                                dialogLatch.await();
                            } catch (InterruptedException e) {
                                Logger.warning("Interrupted while waiting for user decision");
                                return;
                            }

                            boolean reconnectAccepted = userChoice[0];

                            boolean reconnectResult;
                            if (reconnectAccepted) {
                                reconnectResult = gameClient.acceptReconnect();
                            } else {
                                reconnectResult = gameClient.declineReconnect();
                            }

                            if (!reconnectResult) {
                                Logger.error("Reconnect response failed");
                                Platform.runLater(() -> {
                                    showError("Failed to process reconnect response");
                                    updateStatus("Reconnect failed");
                                    gameClient = null;
                                });
                                return;
                            }

                            // Continue based on user choice
                            if (reconnectAccepted) {
                                // Start heartbeat and message receiver
                                gameClient.getNetworkClient().startHeartbeat(() -> {
                                    Platform.runLater(() -> {
                                        Logger.warning("Heartbeat detected server unavailability");
                                        handleServerUnavailable();
                                    });
                                });

                                startMessageReceiver();

                                Platform.runLater(() -> {
                                    connectionStatus.setText("Connected");
                                    connectionStatus.setStyle("-fx-text-fill: #4CAF50; -fx-font-weight: bold;");
                                    connectButton.setDisable(true);
                                    disconnectButton.setDisable(false);
                                    serverHostField.setDisable(true);
                                    serverPortField.setDisable(true);
                                    nicknameField.setDisable(true);
                                    updateStatus("Reconnecting to game...");

                                    // Show game panel
                                    lobbyPanel.setVisible(false);
                                    gamePanel.setVisible(true);
                                });

                                // Start message processor to handle incoming game state
                                startMessageProcessor();

                                // Wait for game state messages (GAME_START, GAME_STATE, etc.)
                                Platform.runLater(() -> {
                                    waitForGameStart();  // This will process GAME_START and restore state
                                });

                                return;  // Exit early - reconnect flow complete
                            } else {
                                // Fall through to normal login flow below
                            }
                        }

                        // Start heartbeat to detect server unavailability
                        gameClient.getNetworkClient().startHeartbeat(() -> {
                            Platform.runLater(() -> {
                                Logger.warning("Heartbeat detected server unavailability");
                                handleServerUnavailable();
                            });
                        });

                        // Start message receiver immediately after login!
                        startMessageReceiver();

                        Platform.runLater(() -> {
                            connectionStatus.setText("Connected");
                            connectionStatus.setStyle("-fx-text-fill: #4CAF50; -fx-font-weight: bold;");
                            connectButton.setDisable(true);
                            disconnectButton.setDisable(false);
                            serverHostField.setDisable(true);
                            serverPortField.setDisable(true);
                            nicknameField.setDisable(true);
                            updateStatus("Connected as " + nickname);
                        });

                        // Check if reconnect is detected
                        handleReconnectDetection();
                    } else {
                        Logger.error("Login failed");
                        Platform.runLater(() -> {
                            showError("Login failed. Nickname may already be in use or server rejected the connection.");
                            updateStatus("Login failed");
                            gameClient = null;
                        });
                    }
                } else {
                    Logger.error("Connection failed");
                    Platform.runLater(() -> {
                        showError("Connection failed. Make sure the server is running at " + host + ":" + port);
                        updateStatus("Connection failed");
                        gameClient = null;
                    });
                }
            } catch (Exception e) {
                Logger.error("Connection error: " + e.getMessage());
                e.printStackTrace();
                Platform.runLater(() -> {
                    showError("Connection error: " + e.getMessage());
                    updateStatus("Connection error");
                    gameClient = null;
                });
            }
        }).start();
    }

    @FXML
    private void handleDisconnect() {
        // Mark as manual disconnect to prevent auto-reconnect
        manualDisconnect = true;

        // Show immediate notification without OK button
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("Disconnecting");
        alert.setHeaderText(null);
        alert.setContentText("Disconnecting from server...");

        // Hide button bar but keep close functionality
        alert.getDialogPane().lookupButton(ButtonType.OK).setVisible(false);
        alert.show();

        // Immediate visual feedback
        disconnectButton.setDisable(true);
        updateStatus("Disconnecting...");
        connectionStatus.setText("Disconnecting...");
        connectionStatus.setStyle("-fx-text-fill: #ff9800; -fx-font-weight: bold;");

        // Run disconnect in background thread to avoid UI freezing
        new Thread(() -> {
            stopMessageReceiver();
            if (gameClient != null) {
                gameClient.getNetworkClient().stopHeartbeat();
                gameClient.disconnect();
                gameClient = null;
            }

            Platform.runLater(() -> {
                alert.close();
                connectionStatus.setText("Disconnected");
                connectionStatus.setStyle("-fx-text-fill: #f44336; -fx-font-weight: bold;");
                connectButton.setDisable(false);
                disconnectButton.setDisable(true);
                serverHostField.setDisable(false);
                serverPortField.setDisable(false);
                nicknameField.setDisable(false);

                lobbyPanel.setVisible(false);
                gamePanel.setVisible(false);
                updateStatus("Disconnected");
            });
        }).start();
    }

    /**
     * Handles server unavailability (network outage, server down)
     */
    private void handleServerUnavailable() {
        if (isReconnecting || manualDisconnect) {
            return;
        }


        // Stop message processing
        stopMessageReceiver();

        // Update UI - viditelná informace
        Platform.runLater(() -> {
            connectionStatus.setText("Server Unavailable");
            connectionStatus.setStyle("-fx-text-fill: #f44336; -fx-font-weight: bold;");
            updateStatus("Connection to server lost. Attempting to reconnect...");

            // Show alert
            showAlert("Connection Lost",
                      "Lost connection to server. Attempting automatic reconnect...");
        });

        // Attempt reconnect
        attemptReconnect();
    }

    @FXML
    private void handleRefreshRooms() {
        if (gameClient == null) return;

        // Only allow refresh when in lobby
        if (gameClient.getState() != GameClient.ClientState.LOBBY) {
            updateStatus("Cannot refresh rooms - not in lobby");
            return;
        }

        updateStatus("Refreshing room list...");

        new Thread(() -> {
            try {
                // Send ROOM_LIST message (don't wait synchronously)
                ProtocolMessage roomListMsg = ProtocolMessage.roomList();
                if (!gameClient.sendMessage(roomListMsg)) {
                    Platform.runLater(() -> {
                        showError("Failed to send room list request");
                        updateStatus("Failed to refresh rooms");
                    });
                    return;
                }

                // Wait for ROOMS response from queue
                ProtocolMessage roomsResponse = waitForResponse("ROOMS", 5);

                if (roomsResponse != null && roomsResponse.getCommand().equals("ERROR")) {
                    Platform.runLater(() -> {
                        showError("Cannot fetch rooms: " + roomsResponse.getErrorMessage());
                        updateStatus("Failed to refresh rooms");
                        roomListView.getItems().clear();
                    });
                    return;
                }

                if (roomsResponse == null || roomsResponse.getParameterCount() == 0) {
                    Platform.runLater(() -> {
                        updateStatus("No response from server");
                        roomListView.getItems().clear();
                    });
                    return;
                }

                // VALIDATION: Get and validate room count
                Integer roomCount = MessageValidator.validateRoomCount(roomsResponse.getParameter(0));
                if (roomCount == null) {
                    Platform.runLater(() -> {
                        showError("Invalid room count from server");
                        updateStatus("Invalid server response");
                    });
                    return;
                }
                List<RoomInfo> rooms = new ArrayList<>();

                // Read each ROOM message from queue and parse to RoomInfo
                for (int i = 0; i < roomCount; i++) {
                    ProtocolMessage roomMsg = waitForResponse("ROOM", 5);
                    if (roomMsg != null) {
                        // Parse ROOM message to RoomInfo
                        RoomInfo room = RoomInfo.parse(roomMsg.toString());
                        if (room != null) {
                            rooms.add(room);
                        }
                    }
                }

                // Sort rooms by ID for consistent ordering
                rooms.sort((r1, r2) -> r1.getId().compareTo(r2.getId()));

                // Update UI
                final List<RoomInfo> finalRooms = rooms;
                Platform.runLater(() -> {
                    roomListView.getItems().clear();
                    if (!finalRooms.isEmpty()) {
                        roomListView.getItems().addAll(finalRooms);
                        updateStatus("Found " + finalRooms.size() + " room(s)");
                    } else {
                        updateStatus("No rooms available");
                    }
                });

            } catch (Exception e) {
                Logger.error("Error refreshing rooms: " + e.getMessage());
                Platform.runLater(() -> {
                    showError("Error refreshing rooms: " + e.getMessage());
                    updateStatus("Failed to refresh rooms");
                });
            }
        }).start();
    }

    @FXML
    private void handleCreateRoom() {
        if (gameClient == null || gameClient.getState() != GameClient.ClientState.LOBBY) {
            updateStatus("Cannot create room - not in lobby");
            return;
        }

        String roomName = roomNameField.getText().trim();
        if (roomName.isEmpty()) {
            showError("Please enter a room name");
            return;
        }

        updateStatus("Creating room '" + roomName + "'...");

        new Thread(() -> {
            try {
                // Send CREATE_ROOM message (don't wait synchronously)
                ProtocolMessage createMsg = ProtocolMessage.createRoom(roomName);
                if (!gameClient.sendMessage(createMsg)) {
                    Platform.runLater(() -> {
                        showError("Failed to send create room request");
                        updateStatus("Failed to create room");
                    });
                    return;
                }

                // Wait for ROOM_CREATED response from queue
                ProtocolMessage response = waitForResponse("ROOM_CREATED", 10);

                if (response != null && response.getCommand().equals("ROOM_CREATED") && response.getParameterCount() > 0) {
                    // Successfully created room!
                    String roomId = response.getParameter(0);
                    gameClient.setCurrentRoomId(roomId);
                    gameClient.setState(GameClient.ClientState.IN_ROOM);

                    Platform.runLater(() -> {
                        updateStatus("Room created! Waiting for opponent...");
                        showGame();
                        gameInfoContainer.setVisible(false);
                        cardsContainer.setVisible(false);
                        gameActionsContainer.setVisible(false);
                        waitingForOpponentArea.setVisible(true);
                    });

                    waitForGameStart();
                } else if (response != null && response.getCommand().equals("ERROR")) {
                    // Server returned error
                    Platform.runLater(() -> {
                        showError("Cannot create room: " + response.getErrorMessage());
                        updateStatus("Failed to create room");
                    });
                } else {
                    // Timeout
                    Platform.runLater(() -> {
                        showError("Failed to create room (timeout)");
                        updateStatus("Failed to create room");
                    });
                }
            } catch (Exception e) {
                Logger.error("Error creating room: " + e.getMessage());
                Platform.runLater(() -> {
                    showError("Error creating room: " + e.getMessage());
                    updateStatus("Error creating room");
                });
            }
        }).start();
    }

    /**
     * Joins a room by ID (called from Join button in room list)
     */
    private void joinRoomById(String roomId) {
        if (gameClient == null || gameClient.getState() != GameClient.ClientState.LOBBY) {
            updateStatus("Cannot join room - not in lobby");
            return;
        }

        updateStatus("Joining room " + roomId + "...");

        new Thread(() -> {
            try {
                // Send JOIN_ROOM message (don't wait synchronously)
                ProtocolMessage joinMsg = ProtocolMessage.joinRoom(roomId);
                if (!gameClient.sendMessage(joinMsg)) {
                    Platform.runLater(() -> {
                        showError("Failed to send join room request");
                        updateStatus("Failed to join room");
                    });
                    return;
                }

                // Wait for JOINED response from queue
                ProtocolMessage response = waitForResponse("JOINED", 10);

                if (response != null && response.getCommand().equals("JOINED")) {
                    // Successfully joined room!
                    gameClient.setCurrentRoomId(roomId);
                    gameClient.setState(GameClient.ClientState.IN_ROOM);

                    Platform.runLater(() -> {
                        updateStatus("Joined room! Waiting for game to start...");
                        showGame();
                        gameInfoContainer.setVisible(false);
                        cardsContainer.setVisible(false);
                        gameActionsContainer.setVisible(false);
                        waitingForOpponentArea.setVisible(true);
                    });

                    waitForGameStart();
                } else if (response != null && response.getCommand().equals("ERROR")) {
                    // Server returned error
                    Platform.runLater(() -> {
                        showError("Cannot join room: " + response.getErrorMessage());
                        updateStatus("Failed to join room");
                    });
                } else {
                    // Timeout
                    Platform.runLater(() -> {
                        showError("Failed to join room (timeout)");
                        updateStatus("Failed to join room");
                    });
                }
            } catch (Exception e) {
                Logger.error("Error joining room: " + e.getMessage());
                Platform.runLater(() -> {
                    showError("Error joining room: " + e.getMessage());
                    updateStatus("Error joining room");
                });
            }
        }).start();
    }


    @FXML
    private void handleHit() {
        if (gameClient == null) return;

        // Disable buttons immediately
        hitButton.setDisable(true);
        standButton.setDisable(true);
        updateStatus("Requesting card...");

        new Thread(() -> {
            try {
                // Send HIT message
                ProtocolMessage hitMsg = ProtocolMessage.hit();
                if (!gameClient.sendMessage(hitMsg)) {
                    Platform.runLater(() -> {
                        showError("Failed to send hit request");
                        updateStatus("Failed to hit");
                        hitButton.setDisable(false);
                        standButton.setDisable(false);
                    });
                    return;
                }

                // Wait for OK response from queue
                ProtocolMessage response = waitForResponse("OK", 5);

                if (response != null && response.getCommand().equals("OK")) {
                    Platform.runLater(() -> {
                        updateStatus("Card incoming...");
                    });
                } else if (response != null && response.getCommand().equals("ERROR")) {
                    Platform.runLater(() -> {
                        showError("Server rejected HIT: " + response.getErrorMessage());
                        updateStatus("Hit rejected");
                        hitButton.setDisable(false);
                        standButton.setDisable(false);
                    });
                } else {
                    Platform.runLater(() -> {
                        showError("Server did not respond to hit request");
                        updateStatus("Hit failed");
                        hitButton.setDisable(false);
                        standButton.setDisable(false);
                    });
                }
            } catch (Exception e) {
                Logger.error("Error during hit: " + e.getMessage());
                Platform.runLater(() -> {
                    showError("Error during hit: " + e.getMessage());
                    updateStatus("Hit error");
                    hitButton.setDisable(false);
                    standButton.setDisable(false);
                });
            }
        }).start();
    }

    @FXML
    private void handleStand() {
        if (gameClient == null) return;

        // Disable buttons immediately
        hitButton.setDisable(true);
        standButton.setDisable(true);
        updateStatus("Standing...");

        new Thread(() -> {
            try {
                // Send STAND message
                ProtocolMessage standMsg = ProtocolMessage.stand();
                if (!gameClient.sendMessage(standMsg)) {
                    Platform.runLater(() -> {
                        showError("Failed to send stand request");
                        updateStatus("Failed to stand");
                    });
                    return;
                }

                // Wait for OK response from queue
                ProtocolMessage response = waitForResponse("OK", 5);

                if (response != null) {
                    Platform.runLater(() -> {
                        waitingArea.setVisible(true);
                        updateStatus("Standing - waiting for opponent...");
                    });
                } else {
                    Platform.runLater(() -> {
                        showError("Server did not respond to stand request");
                        updateStatus("Stand failed");
                        // Re-enable buttons on error
                        hitButton.setDisable(false);
                        standButton.setDisable(false);
                    });
                }
            } catch (Exception e) {
                Logger.error("Error during stand: " + e.getMessage());
                Platform.runLater(() -> {
                    showError("Error during stand: " + e.getMessage());
                    updateStatus("Stand error");
                    // Re-enable buttons on error
                    hitButton.setDisable(false);
                    standButton.setDisable(false);
                });
            }
        }).start();
    }

    @FXML
    private void handleLeaveGame() {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Leave Game");
        alert.setHeaderText("Are you sure you want to leave the game?");
        alert.setContentText("This will end the current game.");

        Optional<ButtonType> result = alert.showAndWait();
        if (result.isPresent() && result.get() == ButtonType.OK) {
            new Thread(() -> {
                try {
                    if (gameClient != null) {
                        // Send LEAVE_ROOM message (don't stop receiver yet!)
                        ProtocolMessage leaveMsg = ProtocolMessage.leaveRoom();
                        if (!gameClient.sendMessage(leaveMsg)) {
                            Platform.runLater(() -> {
                                showError("Failed to send leave room request");
                            });
                            return;
                        }

                        // Wait for OK response from queue
                        ProtocolMessage response = waitForResponse("OK", 5);

                        if (response != null && response.getCommand().equals("OK")) {
                            // Server confirmed - update state
                            gameClient.setState(GameClient.ClientState.LOBBY);
                            gameClient.setCurrentRoomId(null);

                            Platform.runLater(this::showLobby);
                        } else if (response != null && response.getCommand().equals("ERROR")) {
                            // Error (probably not in room - server restarted)
                            gameClient.resetGameState();
                            gameClient.setState(GameClient.ClientState.LOBBY);
                            gameClient.setCurrentRoomId(null);

                            Platform.runLater(() -> {
                                resetGameUI();
                                showLobby();
                            });
                        } else {
                            // Timeout
                            Platform.runLater(() -> {
                                showError("Server did not respond to leave request");
                                handleDisconnect();
                            });
                        }
                    }
                } catch (Exception e) {
                    Logger.error("Error leaving game: " + e.getMessage());
                    Platform.runLater(() -> {
                        showError("Error leaving game: " + e.getMessage());
                    });
                }
            }).start();
        }
    }

    private void waitForGameStart() {
        // Cancel any existing waitForGameStart thread
        if (waitForGameStartThread != null && waitForGameStartThread.isAlive()) {
            waitForGameStartThread.interrupt();
        }

        waitForGameStartThread = new Thread(() -> {

            // Wait for GAME_START from queue
            ProtocolMessage response = waitForResponse("GAME_START");

            if (response != null && response.getParameterCount() >= 2) {
                // VALIDATION: Validate role and opponent nickname
                String role = response.getParameter(0);
                String opponentNick = response.getParameter(1);

                if (!MessageValidator.validateRole(role)) {
                    Platform.runLater(() -> {
                        showError("Invalid role from server: " + role);
                        stopMessageReceiver();
                        showLobby();
                    });
                    return;
                }

                if (!MessageValidator.validateNotNull(opponentNick, "opponentNickname")) {
                    Platform.runLater(() -> {
                        showError("Invalid opponent nickname from server");
                        stopMessageReceiver();
                        showLobby();
                    });
                    return;
                }


                gameClient.setCurrentRole(role);
                gameClient.setOpponentNickname(opponentNick);
                gameClient.setState(GameClient.ClientState.PLAYING);

                Platform.runLater(() -> {
                    waitingForOpponentArea.setVisible(false);
                    gameInfoContainer.setVisible(true);
                    cardsContainer.setVisible(true);
                    gameActionsContainer.setVisible(true);
                    // Don't set waitingArea or buttons here - they are controlled by DEAL_CARDS and YOUR_TURN
                    // Buttons are already disabled from previous state and will be enabled by YOUR_TURN
                    updateGameInfo();
                    updateStatus("Game started!");
                });
            } else if (Thread.currentThread().isInterrupted()) {
                // Thread was cancelled - this is normal (e.g., when starting a new waitForGameStart)
            } else {
                // Real error - no response and thread not interrupted
                Platform.runLater(() -> {
                    showError("Game did not start");
                    stopMessageReceiver();
                    showLobby();
                });
            }
        });
        waitForGameStartThread.setDaemon(true);
        waitForGameStartThread.start();
    }

    private void startMessageReceiver() {
        running = true;
        asyncMessageQueue.clear();
        syncResponseQueue.clear();

        messageReceiverThread = new Thread(() -> {
            while (running && gameClient != null && gameClient.isConnected()) {
                try {
                    ProtocolMessage msg = gameClient.receiveMessage();
                    if (msg == null) {
                        if (!running) break;
                        // Connection might be lost - but continue to next iteration
                        // If truly disconnected, isConnected() will be false
                        continue;
                    }


                    // Ignore PONG messages - they are sent by server in response to heartbeat PING
                    // but heartbeat no longer waits for them (it only checks if send() succeeds)
                    if ("PONG".equals(msg.getCommand())) {
                        continue;
                    }

                    // Route message to appropriate queue based on type
                    if (isAsyncMessage(msg)) {
                        if (!asyncMessageQueue.offer(msg)) {
                            Logger.warning("Async message queue is full, dropping message: " + msg);
                        }
                    } else {
                        // Synchronous response (OK, ROOM_CREATED, JOINED, ROOMS, ROOM, etc.)
                        if (!syncResponseQueue.offer(msg)) {
                            Logger.warning("Sync response queue is full, dropping message: " + msg);
                        }
                    }

                } catch (Exception e) {
                    if (running && !manualDisconnect) {
                        Logger.error("Error in message receiver: " + e.getMessage());
                        // Connection lost unexpectedly - attempt reconnect
                        Platform.runLater(this::attemptReconnect);
                    }
                    break;
                }
            }

            // Check if we exited because connection was lost
            if (running && !manualDisconnect && (gameClient == null || !gameClient.isConnected())) {
                Platform.runLater(this::attemptReconnect);
            }
        });
        messageReceiverThread.setDaemon(true);
        messageReceiverThread.start();

        // Don't start message processor yet - wait for reconnect detection to complete
        // It will be started after we know if this is a reconnect or normal login
    }

    /**
     * Processes asynchronous messages from the queue (YOUR_TURN, ROUND_END, etc.)
     */
    private void startMessageProcessor() {
        messageProcessorThread = new Thread(() -> {
            while (running) {
                try {
                    // Only read from async queue - no need to check message type or put back
                    ProtocolMessage msg = asyncMessageQueue.poll(500, TimeUnit.MILLISECONDS);

                    if (msg == null) {
                        continue;
                    }

                    handleMessage(msg);

                    // After ROUND_END, wait before processing next message
                    // This keeps cards visible while showing round result
                    if (msg.getCommand().equals("ROUND_END")) {
                        Thread.sleep(5000);

                        // After delay, hide round result
                        // Cards will be updated when DEAL_CARDS is processed
                        Platform.runLater(() -> {
                            roundResultArea.setVisible(false);
                        });
                    }
                    // After GAME_END, wait 5s then return to lobby
                    else if (msg.getCommand().equals("GAME_END")) {
                        Thread.sleep(5000);

                        // After delay, reset everything and return to lobby
                        Platform.runLater(() -> {
                            roundResultArea.setVisible(false);
                            resetGameUI();

                            // Reset game client state
                            if (gameClient != null) {
                                gameClient.resetGameState();
                                gameClient.setState(GameClient.ClientState.LOBBY);
                            }

                            showLobby();
                            updateStatus("Returned to lobby");
                        });
                    }
                } catch (InterruptedException e) {
                    break;
                }
            }
        });
        messageProcessorThread.setDaemon(true);
        messageProcessorThread.start();
    }

    /**
     * Checks if message is asynchronous (game event) or synchronous response
     */
    private boolean isAsyncMessage(ProtocolMessage msg) {
        String cmd = msg.getCommand();
        return cmd.equals("YOUR_TURN")
            || cmd.equals("OPPONENT_ACTION")
            || cmd.equals("ROUND_END")
            || cmd.equals("GAME_END")
            || cmd.equals("PLAYER_DISCONNECTED")
            || cmd.equals("PLAYER_RECONNECTED")
            || cmd.equals("DEAL_CARDS")
            || cmd.equals("GAME_STATE")
            || cmd.equals("CARD");
    }

    /**
     * Waits for a specific response from the sync response queue
     * @param expectedCommand Expected command (e.g., "OK", "ROOM_CREATED")
     * @param timeoutSeconds Timeout in seconds
     * @return The message or null if timeout
     */
    private ProtocolMessage waitForResponse(String expectedCommand, int timeoutSeconds) {
        long deadline = System.currentTimeMillis() + (timeoutSeconds * 1000L);

        while (System.currentTimeMillis() < deadline && !Thread.currentThread().isInterrupted()) {
            try {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) break;

                // Read from sync response queue only
                ProtocolMessage msg = syncResponseQueue.poll(
                    Math.min(remaining, 1000),
                    TimeUnit.MILLISECONDS
                );

                if (msg == null) {
                    continue;
                }

                if (msg.getCommand().equals(expectedCommand)) {
                    return msg;
                }

                // Check for ERROR - this indicates the command failed
                if (msg.getCommand().equals("ERROR")) {
                    // Return the ERROR message so caller can handle it
                    return msg;
                }

                // Not the expected message - could be a different sync response
                // This might happen if multiple threads are waiting for different responses
                // Put it back and wait a bit
                syncResponseQueue.offer(msg);
                Thread.sleep(50);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); // Preserve interrupt status
                return null;
            }
        }

        return null;
    }

    /**
     * Waits for a specific response from the sync response queue without timeout
     * Used for operations that can take indefinite time (e.g., waiting for game to start)
     * Can be interrupted using Thread.interrupt()
     * @param expectedCommand Expected command (e.g., "GAME_START")
     * @return The message or null if interrupted
     */
    private ProtocolMessage waitForResponse(String expectedCommand) {

        while (!Thread.currentThread().isInterrupted()) {
            try {
                // Read from sync response queue only
                ProtocolMessage msg = syncResponseQueue.poll(1000, TimeUnit.MILLISECONDS);

                if (msg == null) {
                    continue;
                }

                if (msg.getCommand().equals(expectedCommand)) {
                    return msg;
                }

                // Check for ERROR - this indicates the command failed
                if (msg.getCommand().equals("ERROR")) {
                    // Return the ERROR message so caller can handle it
                    return msg;
                }

                // Not the expected message - could be a different sync response
                // This might happen if multiple threads are waiting for different responses
                // Put it back and wait a bit
                syncResponseQueue.offer(msg);
                Thread.sleep(50);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); // Preserve interrupt status
                return null;
            }
        }

        return null;
    }

    /**
     * Attempts to reconnect to the server automatically
     */
    private void attemptReconnect() {
        if (isReconnecting || manualDisconnect) {
            return;
        }

        isReconnecting = true;

        new Thread(() -> {
            int attempts = 0;
            boolean reconnected = false;

            while (attempts < MAX_AUTO_RECONNECT_ATTEMPTS && !reconnected && !manualDisconnect) {
                attempts++;
                int delay = attempts <= 3 ? SHORT_RECONNECT_DELAY_MS : LONG_RECONNECT_DELAY_MS;

                final int currentAttempt = attempts;
                Platform.runLater(() -> {
                    updateStatus("Connection lost. Attempting to reconnect... (" + currentAttempt + "/" + MAX_AUTO_RECONNECT_ATTEMPTS + ")");
                    connectionStatus.setText("Reconnecting...");
                    connectionStatus.setStyle("-fx-text-fill: #ff9800; -fx-font-weight: bold;");
                });

                try {
                    Thread.sleep(delay);
                } catch (InterruptedException e) {
                    break;
                }

                if (manualDisconnect) {
                    break;
                }

                // Create new client and attempt to reconnect
                GameClient newClient = new GameClient(lastServerHost, lastServerPort);

                // Try to reconnect with session ID
                String sessionIdToRestore = (gameClient != null) ? gameClient.getSessionId() : null;

                if (newClient.connect()) {
                    if (newClient.login(lastNickname, sessionIdToRestore)) {
                        reconnected = true;

                        // Replace old client with new one
                        gameClient = newClient;

                        Logger.info("Automatic reconnect successful");
                        Platform.runLater(() -> {
                            showAlert("Reconnect successful",
                                    "You have been successfully reconnected to the server.");
                            updateStatus("Reconnected successfully!");
                            connectionStatus.setText("Connected");
                            connectionStatus.setStyle("-fx-text-fill: #4CAF50; -fx-font-weight: bold;");
                        });

                        // Stop message receiver and processor first (to prevent them from consuming reconnect messages)
                        stopMessageReceiver();

                        // Restart message receiver
                        startMessageReceiver();

                        // Restart heartbeat to continue monitoring server availability
                        gameClient.getNetworkClient().startHeartbeat(() -> {
                            Platform.runLater(this::handleServerUnavailable);
                        });

                        // Wait for server to restore state (reconnect detection)
                        handleReconnectDetection();
                    }
                }
            }

            if (!reconnected && !manualDisconnect) {
                // All automatic attempts failed - offer manual reconnect
                Logger.warning("Automatic reconnect failed after " + MAX_AUTO_RECONNECT_ATTEMPTS + " attempts");
                Platform.runLater(() -> {
                    updateStatus("Unable to reconnect automatically. Please reconnect manually.");
                    connectionStatus.setText("Disconnected");
                    connectionStatus.setStyle("-fx-text-fill: #f44336; -fx-font-weight: bold;");
                    showReconnectDialog();
                });
            }

            isReconnecting = false;
        }).start();
    }

    /**
     * Detects whether this is a reconnection to an existing game or a fresh start after server restart.
     * Waits 500ms to check for GAME_START or PLAYER_DISCONNECTED messages in queues, then either
     * restores the game state or resets to lobby accordingly.
     */
    private void handleReconnectDetection() {
        new Thread(() -> {
            try {
                // Wait for potential messages indicating reconnect
                Thread.sleep(500);

                // Check sync queue for GAME_START
                ProtocolMessage gameStart = syncResponseQueue.poll();

                // Check async queue for PLAYER_DISCONNECTED (means reconnect but opponent disconnected)
                ProtocolMessage playerDisconnected = asyncMessageQueue.poll();

                if (gameStart != null && gameStart.getCommand().equals("GAME_START")) {
                    syncResponseQueue.offer(gameStart);

                    // Start message processor now
                    startMessageProcessor();

                    Platform.runLater(() -> {
                        lobbyPanel.setVisible(false);
                        gamePanel.setVisible(true);
                        waitForGameStart();
                    });

                    // Put back PLAYER_DISCONNECTED if found
                    if (playerDisconnected != null) {
                        asyncMessageQueue.offer(playerDisconnected);
                    }
                    return;
                }

                if (playerDisconnected != null && playerDisconnected.getCommand().equals("PLAYER_DISCONNECTED")) {
                    asyncMessageQueue.offer(playerDisconnected);

                    // Start message processor now
                    startMessageProcessor();

                    Platform.runLater(() -> {
                        lobbyPanel.setVisible(false);
                        gamePanel.setVisible(true);
                        // PLAYER_DISCONNECTED handler will show waitingForOpponentArea
                    });
                    return;
                }

                // Put back any messages we took
                if (gameStart != null) {
                    syncResponseQueue.offer(gameStart);
                }
                if (playerDisconnected != null) {
                    asyncMessageQueue.offer(playerDisconnected);
                }

                // No reconnect - server restarted, reset state and show lobby
                // Reset game client state (server restarted)
                boolean wasInGame = false;
                if (gameClient != null) {
                    wasInGame = (gameClient.getState() == GameClient.ClientState.IN_ROOM
                            || gameClient.getState() == GameClient.ClientState.PLAYING);
                    gameClient.resetGameState();
                    gameClient.setState(GameClient.ClientState.LOBBY);
                    gameClient.setCurrentRoomId(null);
                }

                // Start message processor now
                startMessageProcessor();

                final boolean showServerRestartMessage = wasInGame;
                Platform.runLater(() -> {
                    resetGameUI();
                    showLobby();

                    // Inform user if they were in a game (server restarted)
                    if (showServerRestartMessage) {
                        showAlert("Server Restarted",
                                "The server has restarted.\n\n" +
                                        "Your previous game has been terminated.\n" +
                                        "You have been returned to the lobby.");
                    }
                });
            } catch (InterruptedException e) {
                Logger.warning("Interrupted while waiting for reconnect detection");
            }
        }).start();
    }

    /**
     * Shows dialog for manual reconnect after automatic attempts failed
     */
    private void showReconnectDialog() {
        Alert alert = new Alert(Alert.AlertType.WARNING);
        alert.setTitle("Connection Lost");
        alert.setHeaderText("Lost connection to server");
        alert.setContentText("Automatic reconnection attempts failed.\nWould you like to try reconnecting manually?");

        ButtonType reconnectButton = new ButtonType("Reconnect");
        ButtonType cancelButton = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        alert.getButtonTypes().setAll(reconnectButton, cancelButton);

        Optional<ButtonType> result = alert.showAndWait();
        if (result.isPresent() && result.get() == reconnectButton) {
            attemptReconnect();
        } else {
            // User cancelled - treat as manual disconnect
            manualDisconnect = true;
            Platform.runLater(() -> {
                connectButton.setDisable(false);
                disconnectButton.setDisable(true);
                serverHostField.setDisable(false);
                serverPortField.setDisable(false);
                nicknameField.setDisable(false);
                lobbyPanel.setVisible(false);
                gamePanel.setVisible(false);
            });
        }
    }

    private void stopMessageReceiver() {
        running = false;

        if (messageReceiverThread != null) {
            messageReceiverThread.interrupt();
        }
        if (messageProcessorThread != null) {
            messageProcessorThread.interrupt();
        }
        if (waitForGameStartThread != null && waitForGameStartThread.isAlive()) {
            waitForGameStartThread.interrupt();
        }

        try {
            if (messageReceiverThread != null) {
                messageReceiverThread.join(2000);
            }
            if (messageProcessorThread != null) {
                messageProcessorThread.join(2000);
            }
            if (waitForGameStartThread != null) {
                waitForGameStartThread.join(2000);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void handleMessage(ProtocolMessage msg) {
        Platform.runLater(() -> {
            String cmd = msg.getCommand();

            switch (cmd) {
                case "YOUR_TURN":
                    handleYourTurn(msg);
                    break;

                case "OPPONENT_ACTION":
                    handleOpponentAction(msg);
                    break;

                case "ROUND_END":
                    handleRoundEnd(msg);
                    break;

                case "GAME_END":
                    handleGameEnd(msg);
                    break;

                case "PLAYER_DISCONNECTED":
                    String disconnectedPlayer = msg.getParameter(0);

                    Platform.runLater(() -> {
                        // Reset game state completely and stay in room
                        if (gameClient != null) {
                            gameClient.resetGameState();
                            gameClient.setState(GameClient.ClientState.IN_ROOM);
                        }

                        // Clear game display completely
                        yourCardsBox.getChildren().clear();
                        handValueLabel.setText("(Value: 0)");
                        opponentCardsBox.getChildren().clear();
                        opponentHandValueLabel.setText("(Value: 0)");
                        hitButton.setDisable(true);
                        standButton.setDisable(true);
                        waitingArea.setVisible(false);
                        roundResultArea.setVisible(false);

                        // Hide game info and show waiting for opponent area
                        gameInfoContainer.setVisible(false);
                        cardsContainer.setVisible(false);
                        gameActionsContainer.setVisible(false);
                        waitingForOpponentArea.setVisible(true);

                        updateStatus("Opponent disconnected. Waiting for opponent to reconnect or new opponent...");
                        showAlert("Opponent Disconnected",
                                 disconnectedPlayer + " has disconnected from the game.\n\n" +
                                 "The system will wait for them to reconnect.\n" +
                                 "If they don't reconnect, a new opponent may join.");

                        // Start waiting for new game in a separate thread to avoid blocking
                        waitForGameStart();
                    });
                    break;

                case "PLAYER_RECONNECTED":
                    String reconnectedPlayer = msg.getParameter(0);
                    Platform.runLater(() -> {
                        updateStatus("Opponent " + reconnectedPlayer + " has reconnected. Resuming game...");
                    });
                    break;

                case "ERROR":
                    showError("Server error: " + msg.getErrorMessage());
                    break;

                case "GAME_STATE":
                    updateGameInfo();

                    // Send ACK
                    new Thread(ProtocolMessage::ackGameState).start();
                    break;

                case "DEAL_CARDS":
                    // VALIDATION: Validate card count
                    Integer cardCount = MessageValidator.validateCardCount(msg.getParameter(0));
                    if (cardCount == null) {
                        showError("Invalid card count from server");
                        break;
                    }

                    // VALIDATION: Check we have enough parameters for all cards
                    if (!MessageValidator.validateParameterCount(msg, cardCount + 1)) {
                        showError("Not enough card parameters from server");
                        break;
                    }

                    // Store cards and update UI
                    gameClient.clearPlayerCards();
                    gameClient.clearOpponentCards();

                    // VALIDATION: Validate each card format before adding
                    boolean allCardsValid = true;
                    for (int i = 0; i < cardCount; i++) {
                        String cardStr = msg.getParameter(i + 1);
                        if (!MessageValidator.validateCardFormat(cardStr)) {
                            showError("Invalid card format from server: " + cardStr);
                            allCardsValid = false;
                            break;
                        }
                        gameClient.addPlayerCard(cardStr);
                    }

                    if (!allCardsValid) {
                        break;
                    }

                    // Opponent gets same number of cards (hidden)
                    gameClient.setOpponentCardCount(cardCount);
                    updateYourCards();
                    updateOpponentCardsWithBacks();
                    // Hide round results and show waiting area (will be hidden if YOUR_TURN arrives)
                    roundResultArea.setVisible(false);
                    waitingArea.setVisible(true);
                    updateStatus("Cards dealt!");

                    // Send ACK
                    new Thread(ProtocolMessage::ackDealCards).start();
                    break;

                case "CARD":
                    // VALIDATION: Validate card format
                    String card = msg.getParameter(0);
                    if (!MessageValidator.validateCardFormat(card)) {
                        showError("Invalid card format from server: " + card);
                        break;
                    }

                    // Store card and update UI
                    gameClient.addPlayerCard(card);
                    updateYourCards();
                    updateStatus("Received a card");
                    break;

                default:
                    break;
            }
        });
    }

    private void handleYourTurn(ProtocolMessage msg) {
        hitButton.setDisable(false);
        standButton.setDisable(false);
        waitingArea.setVisible(false);
        updateStatus("Your turn! Choose Hit or Stand");
        updateGameInfo();
    }

    private void handleOpponentAction(ProtocolMessage msg) {
        // VALIDATION: Validate action parameter
        String action = msg.getParameter(0);
        if (!MessageValidator.validateOpponentAction(action)) {
            showError("Invalid opponent action from server: " + action);
            return;
        }

        String data = msg.getParameter(1);

        // Waiting area should already be visible from previous turn
        // But ensure it's shown and buttons are disabled
        waitingArea.setVisible(true);
        hitButton.setDisable(true);
        standButton.setDisable(true);

        switch (action) {
            case "PLAYED_CARD":
                updateStatus("Opponent played: " + data);
                break;
            case "HIT":
                // Opponent drew a card, increment count and update display
                gameClient.setOpponentCardCount(gameClient.getOpponentCardCount() + 1);
                updateOpponentCardsWithBacks();
                updateStatus("Opponent took a card");
                break;
            case "STAND":
                updateStatus("Opponent stands");
                waitingArea.setVisible(false);
                break;
            case "BUSTED":
                updateStatus("Opponent busted!");
                waitingArea.setVisible(false);
                break;
        }
    }

    private void handleRoundEnd(ProtocolMessage msg) {
        // VALIDATION: Validate parameters
        if (!MessageValidator.validateParameterCount(msg, 3)) {
            showError("Invalid ROUND_END message - missing parameters");
            return;
        }

        String winner = msg.getParameter(0);
        String yourTotal = msg.getParameter(1);
        String opponentTotal = msg.getParameter(2);

        if (!MessageValidator.validateWinner(winner)) {
            showError("Invalid winner from server: " + winner);
            return;
        }

        Integer yourTotalInt = MessageValidator.validateHandValue(yourTotal);
        Integer opponentTotalInt = MessageValidator.validateHandValue(opponentTotal);

        if (yourTotalInt == null || opponentTotalInt == null) {
            showError("Invalid hand values from server");
            return;
        }


        // Uložit hodnoty do GameClient (ze serveru)
        gameClient.setPlayerHandValue(yourTotalInt);
        gameClient.setOpponentHandValue(opponentTotalInt);

        // Parse opponent cards if available (parameter 4)
        if (msg.getParameterCount() >= 5) {
            String opponentCardsStr = msg.getParameter(4);
            if (opponentCardsStr != null && !opponentCardsStr.isEmpty()) {
                List<String> opponentCards = Arrays.asList(opponentCardsStr.split(","));
                gameClient.setOpponentCards(opponentCards);
                // Reveal opponent's cards
                updateOpponentCardsRevealed();
            }
        }

        String message = "Your total: " + yourTotal + " | Opponent total: " + opponentTotal;

        String titleText;
        switch (winner) {
            case "YOU":
                titleText = "You Won This Round!";
                break;
            case "OPPONENT":
                titleText = "Round Lost";
                break;
            default:
                titleText = "Round Ended";
                break;
        }

        roundResultTitle.setText(titleText);
        roundResultMessage.setText(message);
        roundResultArea.setVisible(true);

        // Update scores without clearing cards (cards stay visible during result display)
        updateGameInfoOnly();

        // Hide waiting area for next round
        waitingArea.setVisible(false);

        // Send ACK
        new Thread(ProtocolMessage::ackRoundEnd).start();

        // Result area will be hidden when DEAL_CARDS arrives (after server delay)
    }

    private void handleGameEnd(ProtocolMessage msg) {
        String winner = msg.getParameter(0);
        String yourScore = msg.getParameter(1);
        String opponentScore = msg.getParameter(2);

        // Build result message
        String message = "Final Score: You " + yourScore + " - " + opponentScore + " Opponent";

        String titleText;
        switch (winner) {
            case "YOU":
                titleText = "YOU WON THE GAME!";
                break;
            case "OPPONENT":
                titleText = "Game Over - Opponent Won";
                break;
            case "TIE":
                titleText = "Game Over - Tied";
                break;
            default:
                titleText = "Game Over";
                break;
        }

        // Show game result in same area as round results
        roundResultTitle.setText(titleText);
        roundResultMessage.setText(message);
        roundResultArea.setVisible(true);

        // Disable game buttons
        hitButton.setDisable(true);
        standButton.setDisable(true);
        waitingArea.setVisible(false);

        updateStatus("Game ended. Returning to lobby...");

        // Send ACK
        new Thread(ProtocolMessage::ackGameEnd).start();

        // GAME_END will be handled by message processor for delay and cleanup
    }

    private void updateGameInfo() {
        if (gameClient == null) return;

        roundLabel.setText(String.valueOf(gameClient.getCurrentRound()));
        yourScoreLabel.setText(String.valueOf(gameClient.getYourScore()));
        opponentScoreLabel.setText(String.valueOf(gameClient.getOpponentScore()));
        roleLabel.setText(gameClient.getCurrentRole());
        opponentNameLabel.setText(gameClient.getOpponentNickname());

        updateYourCards();
    }

    /**
     * Updates only game info (scores, round) without clearing cards
     */
    private void updateGameInfoOnly() {
        if (gameClient == null) return;

        roundLabel.setText(String.valueOf(gameClient.getCurrentRound()));
        yourScoreLabel.setText(String.valueOf(gameClient.getYourScore()));
        opponentScoreLabel.setText(String.valueOf(gameClient.getOpponentScore()));
        roleLabel.setText(gameClient.getCurrentRole());
        opponentNameLabel.setText(gameClient.getOpponentNickname());
    }

    private void updateYourCards() {
        yourCardsBox.getChildren().clear();
        List<String> cards = gameClient.getPlayerCards();

        if (cards.isEmpty()) {
            handValueLabel.setText("(Value: 0)");
            return;
        }

        // Calculate card size based on available space
        // We want cards to scale with window size
        for (String card : cards) {
            ImageView cardView = new ImageView(CardImageLoader.getCardImage(card));

            // Bind card height to scene height for proportional scaling
            // Clamp between 100 px and 220 px
            if (yourCardsBox.getScene() != null) {
                yourCardsBox.getScene().heightProperty().addListener((obs, oldVal, newVal) -> {
                    double height = newVal.doubleValue() * 0.2;
                    // Clamp between min and max
                    cardView.setFitHeight(height);
                });
                // Set initial height
                double initialHeight = yourCardsBox.getScene().getHeight() * 0.2;
                cardView.setFitHeight(initialHeight);
            } else {
                cardView.setFitHeight(150);
            }

            cardView.setPreserveRatio(true);
            yourCardsBox.getChildren().add(cardView);
        }

        // Update hand value (calculated from cards)
        int handValue = calculateHandValue(cards);
        handValueLabel.setText("(Value: " + handValue + ")");
    }

    /**
     * Updates opponent cards display with card backs (hidden cards)
     */
    private void updateOpponentCardsWithBacks() {
        opponentCardsBox.getChildren().clear();
        int cardCount = gameClient.getOpponentCardCount();

        if (cardCount == 0) {
            opponentHandValueLabel.setText("(Value: ?)");
            return;
        }

        // Show card backs for each card opponent has
        for (int i = 0; i < cardCount; i++) {
            ImageView cardView = new ImageView(CardImageLoader.getBackImage());

            // Same sizing logic as player cards
            if (opponentCardsBox.getScene() != null) {
                opponentCardsBox.getScene().heightProperty().addListener((obs, oldVal, newVal) -> {
                    double height = newVal.doubleValue() * 0.2;
                    cardView.setFitHeight(height);
                });
                double initialHeight = opponentCardsBox.getScene().getHeight() * 0.2;
                cardView.setFitHeight(initialHeight);
            } else {
                cardView.setFitHeight(150);
            }

            cardView.setPreserveRatio(true);
            opponentCardsBox.getChildren().add(cardView);
        }

        opponentHandValueLabel.setText("(Value: ?)");
    }

    /**
     * Updates opponent cards display with actual cards (revealed after round)
     */
    private void updateOpponentCardsRevealed() {
        opponentCardsBox.getChildren().clear();
        List<String> cards = gameClient.getOpponentCards();

        if (cards.isEmpty()) {
            opponentHandValueLabel.setText("(Value: 0)");
            return;
        }

        // Show actual cards
        for (String card : cards) {
            ImageView cardView = new ImageView(CardImageLoader.getCardImage(card));

            // Same sizing logic as player cards
            if (opponentCardsBox.getScene() != null) {
                opponentCardsBox.getScene().heightProperty().addListener((obs, oldVal, newVal) -> {
                    double height = newVal.doubleValue() * 0.22;
                    height = Math.max(100, Math.min(220, height));
                    cardView.setFitHeight(height);
                });
                double initialHeight = opponentCardsBox.getScene().getHeight() * 0.22;
                initialHeight = Math.max(100, Math.min(220, initialHeight));
                cardView.setFitHeight(initialHeight);
            } else {
                cardView.setFitHeight(150);
            }

            cardView.setPreserveRatio(true);
            opponentCardsBox.getChildren().add(cardView);
        }

        // Show opponent hand value (calculated from revealed cards)
        int handValue = calculateHandValue(cards);
        opponentHandValueLabel.setText("(Value: " + handValue + ")");
    }

    /**
     * Calculates the total value of cards in hand
     */
    private int calculateHandValue(List<String> cards) {
        // Check for double ace (automatic win)
        if (hasDoubleAce(cards)) {
            return 21;
        }

        int total = 0;
        for (String card : cards) {
            total += getCardValue(card);
        }
        return total;
    }

    /**
     * Checks if the hand contains exactly two aces
     */
    private boolean hasDoubleAce(List<String> cards) {
        if (cards.size() != 2) {
            return false;
        }

        boolean hasFirstAce = false;
        for (String card : cards) {
            if (!card.endsWith("-ESO")) {
                return false;
            }

            if (hasFirstAce) {
                return true;
            }
            hasFirstAce = true;
        }

        return false;
    }

    /**
     * Gets the value of a single card
     * Card format: "BARVA-HODNOTA" (e.g., "SRDCE-KRAL")
     */
    private int getCardValue(String card) {
        if (card == null || !card.contains("-")) {
            return 0;
        }

        String[] parts = card.split("-");
        if (parts.length < 2) {
            return 0;
        }

        String rank = parts[1];

        switch (rank) {
            case "SEDM":
                return 7;
            case "OSM":
                return 8;
            case "DEVET":
                return 9;
            case "DESET":
                return 10;
            case "SPODEK":
                return 1;
            case "SVRSEK":
                return 1;
            case "KRAL":
                return 2;
            case "ESO":
                return 11;
            default:
                return 0;
        }
    }

    /**
     * Resets all game UI elements to initial state
     */
    private void resetGameUI() {
        // Clear cards
        yourCardsBox.getChildren().clear();
        handValueLabel.setText("(Value: 0)");

        // Reset labels
        roundLabel.setText("0");
        yourScoreLabel.setText("0");
        opponentScoreLabel.setText("0");
        roleLabel.setText("");
        opponentNameLabel.setText("");

        // Disable buttons
        hitButton.setDisable(true);
        standButton.setDisable(true);

        // Hide areas
        waitingArea.setVisible(false);
        roundResultArea.setVisible(false);
        gameInfoContainer.setVisible(false);
    }

    private void showLobby() {
        lobbyPanel.setVisible(true);
        gamePanel.setVisible(false);

        if (gameClient != null && gameClient.getState() == GameClient.ClientState.LOBBY) {
            handleRefreshRooms();
        }
    }

    private void showGame() {
        lobbyPanel.setVisible(false);
        gamePanel.setVisible(true);
        yourCardsBox.getChildren().clear();
        gameInfoContainer.setVisible(false);
        cardsContainer.setVisible(false);
        gameActionsContainer.setVisible(false);
        waitingArea.setVisible(false);
        waitingForOpponentArea.setVisible(false);
        hitButton.setDisable(true);
        standButton.setDisable(true);
    }

    private void updateStatus(String status) {
        statusLabel.setText(status);
    }

    private void showError(String message) {
        Logger.error(message);
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("Error");
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }

    private void showAlert(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }
}

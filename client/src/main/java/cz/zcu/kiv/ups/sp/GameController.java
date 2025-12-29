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
    private BlockingQueue<ProtocolMessage> asyncMessageQueue = new LinkedBlockingQueue<>();
    private BlockingQueue<ProtocolMessage> syncResponseQueue = new LinkedBlockingQueue<>();

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
        debug("initialize() called");
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
        debug("initialize() completed");
    }

    private void debug(String message) {
        System.out.println("[DEBUG][" + Thread.currentThread().getName() + "] " + message);
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
        debug("handleConnect() called");
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
            debug("Connect thread started, connecting to " + host + ":" + port);
            try {
                if (gameClient.connect()) {
                    debug("Connected successfully, attempting login as " + nickname);
                    Platform.runLater(() -> updateStatus("Logging in as " + nickname + "..."));

                    if (gameClient.login(nickname)) {
                        debug("Login successful");

                        // Check if server sent RECONNECT_QUERY
                        if (gameClient.hasPendingReconnectQuery()) {
                            debug("Reconnect query detected - showing dialog to user");

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
                                debug("Interrupted while waiting for user decision");
                                return;
                            }

                            boolean reconnectAccepted = userChoice[0];
                            debug("User chose to " + (reconnectAccepted ? "accept" : "decline") + " reconnect");

                            boolean reconnectResult;
                            if (reconnectAccepted) {
                                reconnectResult = gameClient.acceptReconnect();
                            } else {
                                reconnectResult = gameClient.declineReconnect();
                            }

                            if (!reconnectResult) {
                                debug("Reconnect response failed");
                                Platform.runLater(() -> {
                                    showError("Failed to process reconnect response");
                                    updateStatus("Reconnect failed");
                                    gameClient = null;
                                });
                                return;
                            }

                            // Continue based on user choice
                            if (reconnectAccepted) {
                                debug("Reconnect accepted - will restore game state");

                                // Start heartbeat and message receiver
                                gameClient.getNetworkClient().startHeartbeat(() -> {
                                    Platform.runLater(() -> {
                                        debug("Heartbeat detected server unavailability");
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
                                debug("Reconnect declined - starting fresh in lobby");
                                // Fall through to normal login flow below
                            }
                        }

                        debug("Starting message receiver");

                        // Start heartbeat to detect server unavailability
                        gameClient.getNetworkClient().startHeartbeat(() -> {
                            Platform.runLater(() -> {
                                debug("Heartbeat detected server unavailability");
                                handleServerUnavailable();
                            });
                        });

                        // Start message receiver immediately after login!
                        startMessageReceiver();

                        Platform.runLater(() -> {
                            debug("Updating UI after successful login");
                            connectionStatus.setText("Connected");
                            connectionStatus.setStyle("-fx-text-fill: #4CAF50; -fx-font-weight: bold;");
                            connectButton.setDisable(true);
                            disconnectButton.setDisable(false);
                            serverHostField.setDisable(true);
                            serverPortField.setDisable(true);
                            nicknameField.setDisable(true);
                            updateStatus("Connected as " + nickname);
                        });

                        // Wait briefly for reconnect messages (GAME_START) from server
                        // If GAME_START arrives, it's a reconnect and we won't show lobby
                        // If nothing arrives within timeout, show lobby (normal login)
                        new Thread(() -> {
                            try {
                                // Wait for potential messages indicating reconnect
                                Thread.sleep(500);

                                // Check sync queue for GAME_START
                                ProtocolMessage gameStart = syncResponseQueue.poll();

                                // Check async queue for PLAYER_DISCONNECTED (means reconnect but opponent disconnected)
                                ProtocolMessage playerDisconnected = asyncMessageQueue.poll();

                                if (gameStart != null && gameStart.getCommand().equals("GAME_START")) {
                                    debug("Reconnect detected (GAME_START in queue), processing reconnect");
                                    syncResponseQueue.offer(gameStart);

                                    // Start message processor now
                                    startMessageProcessor();

                                    Platform.runLater(() -> {
                                        debug("Reconnect - showing game panel without reset");
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
                                    debug("Reconnect detected (PLAYER_DISCONNECTED in queue, waiting for opponent)");
                                    asyncMessageQueue.offer(playerDisconnected);

                                    // Start message processor now
                                    startMessageProcessor();

                                    Platform.runLater(() -> {
                                        debug("Reconnect - showing game panel, waiting for opponent");
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

                                // No reconnect - this is normal login, show lobby
                                debug("No reconnect detected, showing lobby");

                                // Reset game client state (important after server restart)
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
                                    resetGameUI();  // Reset UI to clean state
                                    showLobby();

                                    // Inform user if they were in a game (server likely restarted)
                                    if (showServerRestartMessage) {
                                        showAlert("Server Restarted",
                                                 "The server has restarted.\n\n" +
                                                 "Your previous game has been terminated.\n" +
                                                 "You have been returned to the lobby.");
                                    }
                                });
                            } catch (InterruptedException e) {
                                debug("Interrupted while waiting for reconnect detection");
                            }
                        }).start();
                    } else {
                        debug("Login failed");
                        Platform.runLater(() -> {
                            showError("Login failed. Nickname may already be in use or server rejected the connection.");
                            updateStatus("Login failed");
                            gameClient = null;
                        });
                    }
                } else {
                    debug("Connection failed");
                    Platform.runLater(() -> {
                        showError("Connection failed. Make sure the server is running at " + host + ":" + port);
                        updateStatus("Connection failed");
                        gameClient = null;
                    });
                }
            } catch (Exception e) {
                debug("Connection error: " + e.getMessage());
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
        debug("handleDisconnect() called");
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
            debug("Disconnect thread started");
            stopMessageReceiver();
            if (gameClient != null) {
                debug("Disconnecting from server");
                gameClient.getNetworkClient().stopHeartbeat();
                gameClient.disconnect();
                gameClient = null;
            }

            Platform.runLater(() -> {
                debug("Updating UI after disconnect");
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

        debug("Server unavailable - initiating reconnect");

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
        debug("handleRefreshRooms() called");
        if (gameClient == null) return;

        // Only allow refresh when in lobby
        if (gameClient.getState() != GameClient.ClientState.LOBBY) {
            debug("Cannot refresh rooms - not in lobby, state=" + gameClient.getState());
            updateStatus("Cannot refresh rooms - not in lobby");
            return;
        }

        updateStatus("Refreshing room list...");

        new Thread(() -> {
            debug("Refresh rooms thread started");
            try {
                // Send ROOM_LIST message (don't wait synchronously)
                ProtocolMessage roomListMsg = ProtocolMessage.roomList();
                debug("Sending ROOM_LIST message");
                if (!gameClient.sendMessage(roomListMsg)) {
                    debug("Failed to send ROOM_LIST message");
                    Platform.runLater(() -> {
                        showError("Failed to send room list request");
                        updateStatus("Failed to refresh rooms");
                    });
                    return;
                }

                // Wait for ROOMS response from queue
                debug("Waiting for ROOMS response");
                ProtocolMessage roomsResponse = waitForResponse("ROOMS", 5);

                if (roomsResponse != null && roomsResponse.getCommand().equals("ERROR")) {
                    debug("Error fetching rooms: " + roomsResponse.getErrorMessage());
                    Platform.runLater(() -> {
                        showError("Cannot fetch rooms: " + roomsResponse.getErrorMessage());
                        updateStatus("Failed to refresh rooms");
                        roomListView.getItems().clear();
                    });
                    return;
                }

                if (roomsResponse == null || roomsResponse.getParameterCount() == 0) {
                    debug("No ROOMS response received");
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
                debug("Received ROOMS response, room count=" + roomCount);
                List<RoomInfo> rooms = new ArrayList<>();

                // Read each ROOM message from queue and parse to RoomInfo
                for (int i = 0; i < roomCount; i++) {
                    debug("Waiting for ROOM message " + (i + 1) + "/" + roomCount);
                    ProtocolMessage roomMsg = waitForResponse("ROOM", 5);
                    if (roomMsg != null) {
                        debug("Received ROOM: " + roomMsg.toString());
                        // Parse ROOM message to RoomInfo
                        RoomInfo room = RoomInfo.parse(roomMsg.toString());
                        if (room != null) {
                            rooms.add(room);
                        }
                    }
                }

                // Sort rooms by ID for consistent ordering
                rooms.sort((r1, r2) -> r1.getId().compareTo(r2.getId()));
                debug("Parsed " + rooms.size() + " rooms");

                // Update UI
                final List<RoomInfo> finalRooms = rooms;
                Platform.runLater(() -> {
                    debug("Updating room list UI with " + finalRooms.size() + " rooms");
                    roomListView.getItems().clear();
                    if (!finalRooms.isEmpty()) {
                        roomListView.getItems().addAll(finalRooms);
                        updateStatus("Found " + finalRooms.size() + " room(s)");
                    } else {
                        updateStatus("No rooms available");
                    }
                });

            } catch (Exception e) {
                debug("Error refreshing rooms: " + e.getMessage());
                e.printStackTrace();
                Platform.runLater(() -> {
                    showError("Error refreshing rooms: " + e.getMessage());
                    updateStatus("Failed to refresh rooms");
                });
            }
        }).start();
    }

    @FXML
    private void handleCreateRoom() {
        debug("handleCreateRoom() called");
        if (gameClient == null || gameClient.getState() != GameClient.ClientState.LOBBY) {
            debug("Cannot create room - not in lobby");
            updateStatus("Cannot create room - not in lobby");
            return;
        }

        String roomName = roomNameField.getText().trim();
        if (roomName.isEmpty()) {
            showError("Please enter a room name");
            return;
        }

        debug("Creating room: " + roomName);
        updateStatus("Creating room '" + roomName + "'...");

        new Thread(() -> {
            debug("Create room thread started");
            try {
                // Send CREATE_ROOM message (don't wait synchronously)
                ProtocolMessage createMsg = ProtocolMessage.createRoom(roomName);
                debug("Sending CREATE_ROOM message: " + roomName);
                if (!gameClient.sendMessage(createMsg)) {
                    debug("Failed to send CREATE_ROOM message");
                    Platform.runLater(() -> {
                        showError("Failed to send create room request");
                        updateStatus("Failed to create room");
                    });
                    return;
                }

                // Wait for ROOM_CREATED response from queue
                debug("Waiting for ROOM_CREATED response");
                ProtocolMessage response = waitForResponse("ROOM_CREATED", 10);

                if (response != null && response.getCommand().equals("ROOM_CREATED") && response.getParameterCount() > 0) {
                    // Successfully created room!
                    String roomId = response.getParameter(0);
                    debug("Room created successfully, roomId=" + roomId);
                    gameClient.setCurrentRoomId(roomId);
                    gameClient.setState(GameClient.ClientState.IN_ROOM);

                    Platform.runLater(() -> {
                        debug("Updating UI - showing game panel and waiting for opponent");
                        updateStatus("Room created! Waiting for opponent...");
                        showGame();
                        gameInfoContainer.setVisible(false);
                        cardsContainer.setVisible(false);
                        gameActionsContainer.setVisible(false);
                        waitingForOpponentArea.setVisible(true);
                    });

                    debug("Starting waitForGameStart()");
                    waitForGameStart();
                } else if (response != null && response.getCommand().equals("ERROR")) {
                    // Server returned error
                    debug("Failed to create room - server error: " + response.getErrorMessage());
                    Platform.runLater(() -> {
                        showError("Cannot create room: " + response.getErrorMessage());
                        updateStatus("Failed to create room");
                    });
                } else {
                    // Timeout
                    debug("Failed to create room - timeout");
                    Platform.runLater(() -> {
                        showError("Failed to create room (timeout)");
                        updateStatus("Failed to create room");
                    });
                }
            } catch (Exception e) {
                debug("Error creating room: " + e.getMessage());
                e.printStackTrace();
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
        debug("joinRoomById() called, roomId=" + roomId);
        if (gameClient == null || gameClient.getState() != GameClient.ClientState.LOBBY) {
            debug("Cannot join room - not in lobby");
            updateStatus("Cannot join room - not in lobby");
            return;
        }

        updateStatus("Joining room " + roomId + "...");

        new Thread(() -> {
            debug("Join room thread started");
            try {
                // Send JOIN_ROOM message (don't wait synchronously)
                ProtocolMessage joinMsg = ProtocolMessage.joinRoom(roomId);
                debug("Sending JOIN_ROOM message: " + roomId);
                if (!gameClient.sendMessage(joinMsg)) {
                    debug("Failed to send JOIN_ROOM message");
                    Platform.runLater(() -> {
                        showError("Failed to send join room request");
                        updateStatus("Failed to join room");
                    });
                    return;
                }

                // Wait for JOINED response from queue
                debug("Waiting for JOINED response");
                ProtocolMessage response = waitForResponse("JOINED", 10);

                if (response != null && response.getCommand().equals("JOINED")) {
                    // Successfully joined room!
                    debug("Joined room successfully, roomId=" + roomId);
                    gameClient.setCurrentRoomId(roomId);
                    gameClient.setState(GameClient.ClientState.IN_ROOM);

                    Platform.runLater(() -> {
                        debug("Updating UI - showing game panel and waiting for game to start");
                        updateStatus("Joined room! Waiting for game to start...");
                        showGame();
                        gameInfoContainer.setVisible(false);
                        cardsContainer.setVisible(false);
                        gameActionsContainer.setVisible(false);
                        waitingForOpponentArea.setVisible(true);
                    });

                    debug("Starting waitForGameStart()");
                    waitForGameStart();
                } else if (response != null && response.getCommand().equals("ERROR")) {
                    // Server returned error
                    debug("Failed to join room - server error: " + response.getErrorMessage());
                    Platform.runLater(() -> {
                        showError("Cannot join room: " + response.getErrorMessage());
                        updateStatus("Failed to join room");
                    });
                } else {
                    // Timeout
                    debug("Failed to join room - timeout");
                    Platform.runLater(() -> {
                        showError("Failed to join room (timeout)");
                        updateStatus("Failed to join room");
                    });
                }
            } catch (Exception e) {
                debug("Error joining room: " + e.getMessage());
                e.printStackTrace();
                Platform.runLater(() -> {
                    showError("Error joining room: " + e.getMessage());
                    updateStatus("Error joining room");
                });
            }
        }).start();
    }


    @FXML
    private void handleHit() {
        debug("handleHit() called");
        if (gameClient == null) return;

        // Disable buttons immediately
        hitButton.setDisable(true);
        standButton.setDisable(true);
        updateStatus("Requesting card...");

        new Thread(() -> {
            debug("Hit thread started");
            try {
                // Send HIT message
                ProtocolMessage hitMsg = ProtocolMessage.hit();
                debug("Sending HIT message");
                if (!gameClient.sendMessage(hitMsg)) {
                    debug("Failed to send HIT message");
                    Platform.runLater(() -> {
                        showError("Failed to send hit request");
                        updateStatus("Failed to hit");
                        hitButton.setDisable(false);
                        standButton.setDisable(false);
                    });
                    return;
                }

                // Wait for OK response from queue
                debug("Waiting for OK response");
                ProtocolMessage response = waitForResponse("OK", 5);

                if (response != null && response.getCommand().equals("OK")) {
                    debug("HIT confirmed by server");
                    Platform.runLater(() -> {
                        updateStatus("Card incoming...");
                    });
                } else if (response != null && response.getCommand().equals("ERROR")) {
                    debug("HIT rejected: " + response.getErrorMessage());
                    Platform.runLater(() -> {
                        showError("Server rejected HIT: " + response.getErrorMessage());
                        updateStatus("Hit rejected");
                        hitButton.setDisable(false);
                        standButton.setDisable(false);
                    });
                } else {
                    debug("Hit timeout - server did not respond");
                    Platform.runLater(() -> {
                        showError("Server did not respond to hit request");
                        updateStatus("Hit failed");
                        hitButton.setDisable(false);
                        standButton.setDisable(false);
                    });
                }
            } catch (Exception e) {
                debug("Error during hit: " + e.getMessage());
                e.printStackTrace();
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
        debug("handleStand() called");
        if (gameClient == null) return;

        // Disable buttons immediately
        hitButton.setDisable(true);
        standButton.setDisable(true);
        updateStatus("Standing...");

        new Thread(() -> {
            debug("Stand thread started");
            try {
                // Send STAND message
                ProtocolMessage standMsg = ProtocolMessage.stand();
                debug("Sending STAND message");
                if (!gameClient.sendMessage(standMsg)) {
                    debug("Failed to send STAND message");
                    Platform.runLater(() -> {
                        showError("Failed to send stand request");
                        updateStatus("Failed to stand");
                    });
                    return;
                }

                // Wait for OK response from queue
                debug("Waiting for OK response");
                ProtocolMessage response = waitForResponse("OK", 5);

                if (response != null) {
                    debug("STAND confirmed by server");
                    Platform.runLater(() -> {
                        waitingArea.setVisible(true);
                        updateStatus("Standing - waiting for opponent...");
                    });
                } else {
                    debug("Stand timeout - server did not respond");
                    Platform.runLater(() -> {
                        showError("Server did not respond to stand request");
                        updateStatus("Stand failed");
                        // Re-enable buttons on error
                        hitButton.setDisable(false);
                        standButton.setDisable(false);
                    });
                }
            } catch (Exception e) {
                debug("Error during stand: " + e.getMessage());
                e.printStackTrace();
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
        debug("handleLeaveGame() called");
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Leave Game");
        alert.setHeaderText("Are you sure you want to leave the game?");
        alert.setContentText("This will end the current game.");

        Optional<ButtonType> result = alert.showAndWait();
        if (result.isPresent() && result.get() == ButtonType.OK) {
            debug("User confirmed leaving game");
            new Thread(() -> {
                debug("Leave game thread started");
                try {
                    if (gameClient != null) {
                        // Send LEAVE_ROOM message (don't stop receiver yet!)
                        ProtocolMessage leaveMsg = ProtocolMessage.leaveRoom();
                        debug("Sending LEAVE_ROOM message");
                        if (!gameClient.sendMessage(leaveMsg)) {
                            debug("Failed to send LEAVE_ROOM message");
                            Platform.runLater(() -> {
                                showError("Failed to send leave room request");
                            });
                            return;
                        }

                        // Wait for OK response from queue
                        debug("Waiting for OK response");
                        ProtocolMessage response = waitForResponse("OK", 5);

                        if (response != null && response.getCommand().equals("OK")) {
                            // Server confirmed - update state
                            debug("Leave room confirmed by server, returning to lobby");
                            gameClient.setState(GameClient.ClientState.LOBBY);
                            gameClient.setCurrentRoomId(null);

                            Platform.runLater(this::showLobby);
                        } else if (response != null && response.getCommand().equals("ERROR")) {
                            // Error (probably not in room - server restarted)
                            debug("Leave room error: " + response.getErrorMessage() + " - resetting to lobby anyway");
                            gameClient.resetGameState();
                            gameClient.setState(GameClient.ClientState.LOBBY);
                            gameClient.setCurrentRoomId(null);

                            Platform.runLater(() -> {
                                resetGameUI();
                                showLobby();
                            });
                        } else {
                            // Timeout
                            debug("Leave room timeout - server did not respond");
                            Platform.runLater(() -> {
                                showError("Server did not respond to leave request");
                                handleDisconnect();
                            });
                        }
                    }
                } catch (Exception e) {
                    debug("Error leaving game: " + e.getMessage());
                    e.printStackTrace();
                    Platform.runLater(() -> {
                        showError("Error leaving game: " + e.getMessage());
                    });
                }
            }).start();
        } else {
            debug("User cancelled leaving game");
        }
    }

    private void waitForGameStart() {
        // Cancel any existing waitForGameStart thread
        if (waitForGameStartThread != null && waitForGameStartThread.isAlive()) {
            debug("waitForGameStart() - cancelling existing thread: " + waitForGameStartThread.getName());
            waitForGameStartThread.interrupt();
        }

        waitForGameStartThread = new Thread(() -> {
            debug("waitForGameStart() started");

            // Wait for GAME_START from queue
            debug("Waiting for GAME_START response");
            ProtocolMessage response = waitForResponse("GAME_START");

            if (response != null && response.getParameterCount() >= 2) {
                // VALIDATION: Validate role and opponent nickname
                String role = response.getParameter(0);
                String opponentNick = response.getParameter(1);

                if (!MessageValidator.validateRole(role)) {
                    debug("GAME_START failed - invalid role: " + role);
                    Platform.runLater(() -> {
                        showError("Invalid role from server: " + role);
                        stopMessageReceiver();
                        showLobby();
                    });
                    return;
                }

                if (!MessageValidator.validateNotNull(opponentNick, "opponentNickname")) {
                    debug("GAME_START failed - null opponent nickname");
                    Platform.runLater(() -> {
                        showError("Invalid opponent nickname from server");
                        stopMessageReceiver();
                        showLobby();
                    });
                    return;
                }

                debug("GAME_START received, role=" + role + ", opponent=" + opponentNick);

                gameClient.setCurrentRole(role);
                gameClient.setOpponentNickname(opponentNick);
                gameClient.setState(GameClient.ClientState.PLAYING);

                Platform.runLater(() -> {
                    debug("GAME_START UI update - hiding waitingForOpponentArea, showing game UI");
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
                debug("waitForGameStart cancelled (thread interrupted) - this is normal");
            } else {
                // Real error - no response and thread not interrupted
                debug("Game start failed - no response from server");
                Platform.runLater(() -> {
                    showError("Game did not start");
                    stopMessageReceiver();
                    showLobby();
                });
            }
        });
        waitForGameStartThread.setDaemon(true);
        waitForGameStartThread.start();
        debug("waitForGameStart() - started new thread: " + waitForGameStartThread.getName());
    }

    private void startMessageReceiver() {
        debug("startMessageReceiver() called");
        running = true;
        asyncMessageQueue.clear();
        syncResponseQueue.clear();

        messageReceiverThread = new Thread(() -> {
            debug("Message receiver thread started");
            while (running && gameClient != null && gameClient.isConnected()) {
                try {
                    ProtocolMessage msg = gameClient.receiveMessage();
                    if (msg == null) {
                        if (!running) break;
                        // Connection might be lost - but continue to next iteration
                        // If truly disconnected, isConnected() will be false
                        continue;
                    }

                    debug("Received message: " + msg.getCommand());

                    // Ignore PONG messages - they are sent by server in response to heartbeat PING
                    // but heartbeat no longer waits for them (it only checks if send() succeeds)
                    if ("PONG".equals(msg.getCommand())) {
                        debug("PONG received (heartbeat response) - ignoring");
                        continue;
                    }

                    // Route message to appropriate queue based on type
                    if (isAsyncMessage(msg)) {
                        if (!asyncMessageQueue.offer(msg)) {
                            debug("ERROR: Failed to add async message to queue!");
                        } else {
                            debug("Async message added to queue: " + msg.getCommand() + ", queue size=" + asyncMessageQueue.size());
                        }
                    } else {
                        // Synchronous response (OK, ROOM_CREATED, JOINED, ROOMS, ROOM, etc.)
                        if (!syncResponseQueue.offer(msg)) {
                            debug("ERROR: Failed to add sync response to queue!");
                        } else {
                            debug("Sync response added to queue: " + msg.getCommand() + ", queue size=" + syncResponseQueue.size());
                        }
                    }

                } catch (Exception e) {
                    if (running && !manualDisconnect) {
                        debug("Message receiver error (connection lost): " + e.getMessage());
                        e.printStackTrace();
                        // Connection lost unexpectedly - attempt reconnect
                        debug("Message receiver: triggering automatic reconnect");
                        Platform.runLater(() -> attemptReconnect());
                    }
                    break;
                }
            }

            // Check if we exited because connection was lost
            if (running && !manualDisconnect && (gameClient == null || !gameClient.isConnected())) {
                debug("Message receiver: connection lost detected, triggering reconnect");
                Platform.runLater(() -> attemptReconnect());
            }

            debug("Message receiver thread ended");
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
        debug("startMessageProcessor() called");
        messageProcessorThread = new Thread(() -> {
            debug("Message processor thread started");
            while (running) {
                try {
                    // Only read from async queue - no need to check message type or put back
                    ProtocolMessage msg = asyncMessageQueue.poll(500, TimeUnit.MILLISECONDS);

                    if (msg == null) {
                        continue;
                    }

                    debug("Processing async message from queue: " + msg.getCommand());
                    handleMessage(msg);

                    // After ROUND_END, wait before processing next message
                    // This keeps cards visible while showing round result
                    if (msg.getCommand().equals("ROUND_END")) {
                        debug("ROUND_END processed, sleeping for 5s to show result");
                        Thread.sleep(5000);

                        // After delay, hide round result
                        // Cards will be updated when DEAL_CARDS is processed
                        Platform.runLater(() -> {
                            debug("Hiding round result area after delay");
                            roundResultArea.setVisible(false);
                        });
                    }
                    // After GAME_END, wait 5s then return to lobby
                    else if (msg.getCommand().equals("GAME_END")) {
                        debug("GAME_END processed, sleeping for 5s before returning to lobby");
                        Thread.sleep(5000);

                        // After delay, reset everything and return to lobby
                        Platform.runLater(() -> {
                            debug("Returning to lobby after GAME_END delay");
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
                    debug("Message processor interrupted");
                    break;
                }
            }
            debug("Message processor thread ended");
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
            // ERROR is NOT async - it's a sync response to failed commands
    }

    /**
     * Waits for a specific response from the sync response queue
     * @param expectedCommand Expected command (e.g., "OK", "ROOM_CREATED")
     * @param timeoutSeconds Timeout in seconds
     * @return The message or null if timeout
     */
    private ProtocolMessage waitForResponse(String expectedCommand, int timeoutSeconds) {
        debug("waitForResponse() called, expecting: " + expectedCommand + ", timeout=" + timeoutSeconds + "s");
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

                debug("waitForResponse: got message " + msg.getCommand() + ", expecting " + expectedCommand);
                if (msg.getCommand().equals(expectedCommand)) {
                    debug("waitForResponse: found expected message " + expectedCommand);
                    return msg;
                }

                // Check for ERROR - this indicates the command failed
                if (msg.getCommand().equals("ERROR")) {
                    debug("waitForResponse: got ERROR instead of " + expectedCommand + ": " + msg.getErrorMessage());
                    // Return the ERROR message so caller can handle it
                    return msg;
                }

                // Not the expected message - could be a different sync response
                // This might happen if multiple threads are waiting for different responses
                // Put it back and wait a bit
                debug("waitForResponse: got unexpected sync message " + msg.getCommand() + ", putting back");
                syncResponseQueue.offer(msg);
                Thread.sleep(50);

            } catch (InterruptedException e) {
                debug("waitForResponse: interrupted while waiting for " + expectedCommand);
                Thread.currentThread().interrupt(); // Preserve interrupt status
                return null;
            }
        }

        if (Thread.currentThread().isInterrupted()) {
            debug("waitForResponse: cancelled (interrupted) while waiting for " + expectedCommand);
        } else {
            debug("waitForResponse: timeout waiting for " + expectedCommand);
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
        debug("waitForResponse() called, expecting: " + expectedCommand + " (no timeout)");

        while (!Thread.currentThread().isInterrupted()) {
            try {
                // Read from sync response queue only
                ProtocolMessage msg = syncResponseQueue.poll(1000, TimeUnit.MILLISECONDS);

                if (msg == null) {
                    continue;
                }

                debug("waitForResponse: got message " + msg.getCommand() + ", expecting " + expectedCommand);
                if (msg.getCommand().equals(expectedCommand)) {
                    debug("waitForResponse: found expected message " + expectedCommand);
                    return msg;
                }

                // Check for ERROR - this indicates the command failed
                if (msg.getCommand().equals("ERROR")) {
                    debug("waitForResponse: got ERROR instead of " + expectedCommand + ": " + msg.getErrorMessage());
                    // Return the ERROR message so caller can handle it
                    return msg;
                }

                // Not the expected message - could be a different sync response
                // This might happen if multiple threads are waiting for different responses
                // Put it back and wait a bit
                debug("waitForResponse: got unexpected sync message " + msg.getCommand() + ", putting back");
                syncResponseQueue.offer(msg);
                Thread.sleep(50);

            } catch (InterruptedException e) {
                debug("waitForResponse: interrupted while waiting for " + expectedCommand);
                Thread.currentThread().interrupt(); // Preserve interrupt status
                return null;
            }
        }

        debug("waitForResponse: cancelled (interrupted) while waiting for " + expectedCommand);
        return null;
    }

    /**
     * Attempts to reconnect to the server automatically
     */
    private void attemptReconnect() {
        if (isReconnecting || manualDisconnect) {
            debug("attemptReconnect: skipping (isReconnecting=" + isReconnecting + ", manualDisconnect=" + manualDisconnect + ")");
            return;
        }

        isReconnecting = true;
        debug("attemptReconnect: starting automatic reconnect attempts");
        showAlert("Lost connection to server",
                "Connection to the server has been lost. The application will now try to reconnect automatically.");

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
                    debug("attemptReconnect: waiting " + delay + "ms before attempt " + attempts);
                    Thread.sleep(delay);
                } catch (InterruptedException e) {
                    debug("attemptReconnect: interrupted during sleep");
                    break;
                }

                if (manualDisconnect) {
                    debug("attemptReconnect: manual disconnect detected, aborting");
                    break;
                }

                debug("attemptReconnect: attempt " + attempts + " - creating new GameClient");
                // Create new client and attempt to reconnect
                GameClient newClient = new GameClient(lastServerHost, lastServerPort);

                // Try reconnect with session ID
                String sessionIdToRestore = (gameClient != null) ? gameClient.getSessionId() : null;

                if (newClient.connect()) {
                    debug("attemptReconnect: connected, attempting login as " + lastNickname + " with session ID");
                    if (newClient.login(lastNickname, sessionIdToRestore)) {
                        debug("attemptReconnect: login successful!");
                        reconnected = true;

                        // Replace old client with new one
                        gameClient = newClient;

                        Platform.runLater(() -> {
                            showAlert("Reconnect successful",
                                    "You have been successfully reconnected to the server.");
                            updateStatus("Reconnected successfully!");
                            connectionStatus.setText("Connected");
                            connectionStatus.setStyle("-fx-text-fill: #4CAF50; -fx-font-weight: bold;");
                        });

                        // Restart message receiver
                        debug("attemptReconnect: restarting message receiver");
                        startMessageReceiver();

                        // Restart heartbeat to continue monitoring server availability
                        debug("attemptReconnect: restarting heartbeat");
                        gameClient.getNetworkClient().startHeartbeat(() -> {
                            Platform.runLater(() -> {
                                debug("Heartbeat detected server unavailability after reconnect");
                                handleServerUnavailable();
                            });
                        });

                        // Wait for server to restore state (reconnect detection)
                        debug("attemptReconnect: waiting for server to restore state");

                        // Same reconnect detection as after initial login
                        new Thread(() -> {
                            try {
                                // Wait for potential messages indicating reconnect
                                Thread.sleep(500);

                                // Check sync queue for GAME_START
                                ProtocolMessage gameStart = syncResponseQueue.poll();

                                // Check async queue for PLAYER_DISCONNECTED (means reconnect but opponent disconnected)
                                ProtocolMessage playerDisconnected = asyncMessageQueue.poll();

                                if (gameStart != null && gameStart.getCommand().equals("GAME_START")) {
                                    debug("Reconnect detected (GAME_START in queue), processing reconnect");
                                    syncResponseQueue.offer(gameStart);

                                    // Start message processor now
                                    startMessageProcessor();

                                    Platform.runLater(() -> {
                                        debug("Reconnect - showing game panel without reset");
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
                                    debug("Reconnect detected (PLAYER_DISCONNECTED in queue, waiting for opponent)");
                                    asyncMessageQueue.offer(playerDisconnected);

                                    // Start message processor now
                                    startMessageProcessor();

                                    Platform.runLater(() -> {
                                        debug("Reconnect - showing game panel, waiting for opponent");
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
                                debug("No reconnect detected after auto-reconnect, showing lobby");

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
                                debug("Interrupted while waiting for reconnect detection after auto-reconnect");
                            }
                        }).start();
                    } else {
                        debug("attemptReconnect: login failed on attempt " + attempts);
                    }
                } else {
                    debug("attemptReconnect: connection failed on attempt " + attempts);
                }
            }

            if (!reconnected && !manualDisconnect) {
                debug("attemptReconnect: all automatic attempts failed, showing manual reconnect dialog");
                // All automatic attempts failed - offer manual reconnect
                Platform.runLater(() -> {
                    updateStatus("Unable to reconnect automatically. Please reconnect manually.");
                    connectionStatus.setText("Disconnected");
                    connectionStatus.setStyle("-fx-text-fill: #f44336; -fx-font-weight: bold;");
                    showReconnectDialog();
                });
            }

            isReconnecting = false;
            debug("attemptReconnect: finished (reconnected=" + reconnected + ")");
        }).start();
    }

    /**
     * Shows dialog for manual reconnect after automatic attempts failed
     */
    private void showReconnectDialog() {
        debug("showReconnectDialog: displaying manual reconnect dialog");
        Alert alert = new Alert(Alert.AlertType.WARNING);
        alert.setTitle("Connection Lost");
        alert.setHeaderText("Lost connection to server");
        alert.setContentText("Automatic reconnection attempts failed.\nWould you like to try reconnecting manually?");

        ButtonType reconnectButton = new ButtonType("Reconnect");
        ButtonType cancelButton = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        alert.getButtonTypes().setAll(reconnectButton, cancelButton);

        Optional<ButtonType> result = alert.showAndWait();
        if (result.isPresent() && result.get() == reconnectButton) {
            debug("showReconnectDialog: user chose to reconnect manually");
            attemptReconnect();
        } else {
            debug("showReconnectDialog: user cancelled manual reconnect");
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
        debug("stopMessageReceiver() called");
        running = false;

        if (messageReceiverThread != null) {
            debug("Interrupting message receiver thread");
            messageReceiverThread.interrupt();
        }
        if (messageProcessorThread != null) {
            debug("Interrupting message processor thread");
            messageProcessorThread.interrupt();
        }
        if (waitForGameStartThread != null && waitForGameStartThread.isAlive()) {
            debug("Interrupting waitForGameStart thread");
            waitForGameStartThread.interrupt();
        }

        try {
            if (messageReceiverThread != null) {
                debug("Waiting for message receiver thread to join");
                messageReceiverThread.join(2000);
            }
            if (messageProcessorThread != null) {
                debug("Waiting for message processor thread to join");
                messageProcessorThread.join(2000);
            }
            if (waitForGameStartThread != null) {
                debug("Waiting for waitForGameStart thread to join");
                waitForGameStartThread.join(2000);
            }
        } catch (InterruptedException e) {
            debug("Interrupted while stopping message receiver");
            Thread.currentThread().interrupt();
        }
        debug("Message receiver stopped");
    }

    private void handleMessage(ProtocolMessage msg) {
        debug("handleMessage() called for: " + msg.getCommand());
        Platform.runLater(() -> {
            String cmd = msg.getCommand();
            debug("handleMessage UI update for: " + cmd);

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
                    debug("PLAYER_DISCONNECTED received: " + disconnectedPlayer);

                    Platform.runLater(() -> {
                        // Reset game state completely and stay in room
                        if (gameClient != null) {
                            gameClient.resetGameState();
                            gameClient.setState(GameClient.ClientState.IN_ROOM);
                        }

                        debug("PLAYER_DISCONNECTED - resetting UI, showing waitingForOpponentArea");
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
                        debug("PLAYER_DISCONNECTED - starting waitForGameStart");
                        waitForGameStart();
                    });
                    break;

                case "PLAYER_RECONNECTED":
                    String reconnectedPlayer = msg.getParameter(0);
                    debug("PLAYER_RECONNECTED received: " + reconnectedPlayer);
                    Platform.runLater(() -> {
                        updateStatus("Opponent " + reconnectedPlayer + " has reconnected. Resuming game...");
                    });
                    break;

                case "ERROR":
                    debug("ERROR message received: " + msg.getErrorMessage());
                    showError("Server error: " + msg.getErrorMessage());
                    break;

                case "GAME_STATE":
                    debug("GAME_STATE received, updating game info");
                    updateGameInfo();

                    // Send ACK
                    new Thread(() -> {
                        ProtocolMessage ackMsg = ProtocolMessage.ackGameState();
                        if (!gameClient.sendMessage(ackMsg)) {
                            debug("Failed to send ACK_GAME_STATE");
                        } else {
                            debug("ACK_GAME_STATE sent");
                        }
                    }).start();
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
                    debug("DEAL_CARDS received");
                    gameClient.clearPlayerCards();
                    gameClient.clearOpponentCards();
                    debug("DEAL_CARDS - dealing " + cardCount + " cards");

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
                    debug("DEAL_CARDS - waitingArea set to VISIBLE (will be hidden on YOUR_TURN)");
                    updateStatus("Cards dealt!");

                    // Send ACK
                    new Thread(() -> {
                        ProtocolMessage ackMsg = ProtocolMessage.ackDealCards();
                        if (!gameClient.sendMessage(ackMsg)) {
                            debug("Failed to send ACK_DEAL_CARDS");
                        } else {
                            debug("ACK_DEAL_CARDS sent");
                        }
                    }).start();
                    break;

                case "CARD":
                    // VALIDATION: Validate card format
                    String card = msg.getParameter(0);
                    if (!MessageValidator.validateCardFormat(card)) {
                        showError("Invalid card format from server: " + card);
                        break;
                    }

                    // Store card and update UI
                    debug("CARD received: " + card);
                    gameClient.addPlayerCard(card);
                    updateYourCards();
                    updateStatus("Received a card");
                    break;

                default:
                    debug("Unknown message type: " + cmd);
                    break;
            }
        });
    }

    private void handleYourTurn(ProtocolMessage msg) {
        debug("YOUR_TURN received - enabling buttons, hiding waitingArea");
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
        debug("OPPONENT_ACTION received: action=" + action + ", data=" + data);

        // Waiting area should already be visible from previous turn
        // But ensure it's shown and buttons are disabled
        waitingArea.setVisible(true);
        hitButton.setDisable(true);
        standButton.setDisable(true);

        switch (action) {
            case "PLAYED_CARD":
                debug("Opponent played card: " + data);
                updateStatus("Opponent played: " + data);
                break;
            case "HIT":
                // Opponent drew a card, increment count and update display
                debug("Opponent took a card");
                gameClient.setOpponentCardCount(gameClient.getOpponentCardCount() + 1);
                updateOpponentCardsWithBacks();
                updateStatus("Opponent took a card");
                break;
            case "STAND":
                debug("Opponent stands");
                updateStatus("Opponent stands");
                waitingArea.setVisible(false);
                break;
            case "BUSTED":
                debug("Opponent busted");
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

        debug("ROUND_END: winner=" + winner + ", yourTotal=" + yourTotal + ", opponentTotal=" + opponentTotal);

        // Uložit hodnoty do GameClient (ze serveru)
        gameClient.setPlayerHandValue(yourTotalInt);
        gameClient.setOpponentHandValue(opponentTotalInt);

        // Parse opponent cards if available (parameter 4)
        if (msg.getParameterCount() >= 5) {
            String opponentCardsStr = msg.getParameter(4);
            if (opponentCardsStr != null && !opponentCardsStr.isEmpty()) {
                debug("ROUND_END - revealing opponent cards: " + opponentCardsStr);
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

        debug("ROUND_END - showing result: " + titleText);
        roundResultTitle.setText(titleText);
        roundResultMessage.setText(message);
        roundResultArea.setVisible(true);

        // Update scores without clearing cards (cards stay visible during result display)
        updateGameInfoOnly();

        // Hide waiting area for next round
        waitingArea.setVisible(false);

        // Send ACK
        new Thread(() -> {
            ProtocolMessage ackMsg = ProtocolMessage.ackRoundEnd();
            if (!gameClient.sendMessage(ackMsg)) {
                debug("Failed to send ACK_ROUND_END");
            } else {
                debug("ACK_ROUND_END sent");
            }
        }).start();

        // Result area will be hidden when DEAL_CARDS arrives (after server delay)
    }

    private void handleGameEnd(ProtocolMessage msg) {
        String winner = msg.getParameter(0);
        String yourScore = msg.getParameter(1);
        String opponentScore = msg.getParameter(2);
        debug("GAME_END: winner=" + winner + ", yourScore=" + yourScore + ", opponentScore=" + opponentScore);

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

        debug("GAME_END - showing result: " + titleText);
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
        new Thread(() -> {
            ProtocolMessage ackMsg = ProtocolMessage.ackGameEnd();
            if (!gameClient.sendMessage(ackMsg)) {
                debug("Failed to send ACK_GAME_END");
            } else {
                debug("ACK_GAME_END sent");
            }
        }).start();

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
        debug("showLobby() called");
        lobbyPanel.setVisible(true);
        gamePanel.setVisible(false);

        if (gameClient != null && gameClient.getState() == GameClient.ClientState.LOBBY) {
            handleRefreshRooms();
        }
    }

    private void showGame() {
        debug("showGame() called");
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

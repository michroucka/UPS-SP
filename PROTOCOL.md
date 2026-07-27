# Network Protocol – Oko bere

A text-based protocol over TCP. Every message has the form:

```
COMMAND|param1|param2|...\n
```

- `|` separates message fields (`Protocol::DELIMITER`)
- `\n` terminates the message (`Protocol::MESSAGE_END`)
- max message length is 4096 B; disallowed characters in payload data (nickname etc.) are escaped
- the client moves through states `CONNECTED → LOBBY → IN_ROOM → PLAYING`

## Client state diagram

```mermaid
stateDiagram-v2
    [*] --> CONNECTED: TCP connection established
    CONNECTED --> LOBBY: LOGIN successful
    LOBBY --> IN_ROOM: JOIN_ROOM / CREATE_ROOM
    IN_ROOM --> PLAYING: GAME_START (2nd player joined)
    PLAYING --> IN_ROOM: GAME_END, opponent left
    IN_ROOM --> LOBBY: LEAVE_ROOM
    CONNECTED --> [*]: DISCONNECT / error
    PLAYING --> [*]: TCP connection lost (→ DisconnectedPlayerInfo)
```

## 1. Login and lobby

```mermaid
sequenceDiagram
    participant C as Client
    participant S as Server

    C->>S: LOGIN|nick
    alt nickname free and valid
        S-->>C: OK|sessionId
    else nickname taken / invalid
        S-->>C: ERROR|reason
    end

    C->>S: ROOM_LIST
    S-->>C: ROOMS|count

    alt create a room
        C->>S: CREATE_ROOM
        S-->>C: ROOM_CREATED|roomId
    else join an existing room
        C->>S: JOIN_ROOM|roomId
        S-->>C: JOINED|roomId|playerCount
    end
```

## 2. Game round

Game start, a single round (BANKER vs. player), and its end. `DEAL_CARDS` and `ROUND_END`/`GAME_END` are acknowledged via ACK messages (an application-level reliability layer on top of TCP).

```mermaid
sequenceDiagram
    participant A as Client A
    participant S as Server
    participant B as Client B

    Note over S: both players in the room - game starts
    S-->>A: GAME_START|role|opponentName
    S-->>B: GAME_START|role|opponentName

    S-->>A: DEAL_CARDS|count|card1|card2|...
    A->>S: ACK_DEAL_CARDS
    S-->>B: DEAL_CARDS|count|card1|card2|...
    B->>S: ACK_DEAL_CARDS

    S-->>A: YOUR_TURN|tableCard

    alt player hits
        A->>S: HIT
        S-->>A: OK
        S-->>B: OPPONENT_ACTION|HIT|card
    else player stands
        A->>S: STAND
        S-->>A: OK
        S-->>B: OPPONENT_ACTION|STAND|
    end

    Note over S: round is evaluated
    S-->>A: ROUND_END|winner|myScore|opponentScore|myHand|opponentHand
    A->>S: ACK_ROUND_END
    S-->>B: ROUND_END|winner|myScore|opponentScore|myHand|opponentHand
    B->>S: ACK_ROUND_END

    Note over S: game over (target score reached)
    S-->>A: GAME_END|winner|myScore|opponentScore
    A->>S: ACK_GAME_END
    S-->>B: GAME_END|winner|opponentScore|myScore
    B->>S: ACK_GAME_END
```

## 3. Disconnect and reconnect

The most interesting part of the protocol. Two cases are distinguished: a **silent reconnect** (client still has its `sessionId`, e.g. just a brief network drop) and a **prompted reconnect** (client app restarted and lost its `sessionId`, so the server recognizes it only by nickname).

```mermaid
sequenceDiagram
    participant A as Client A (disconnects)
    participant S as Server
    participant B as Client B (opponent)

    Note over A,S: A loses its TCP connection mid-game
    S-->>B: PLAYER_DISCONNECTED|nickA
    Note over S: A stored in disconnectedPlayers,<br/>30s RECONNECT_TIMEOUT running

    Note over A,S: Variant 1 - silent reconnect (sessionId preserved)
    A->>S: LOGIN|nickA|sessionId
    S-->>A: OK|sessionId
    S-->>A: GAME_START, GAME_STATE, DEAL_CARDS, (YOUR_TURN)
    S-->>B: PLAYER_RECONNECTED|nickA
    S-->>B: GAME_START, DEAL_CARDS (resync)

    Note over A,S: Variant 2 - prompted reconnect (sessionId lost)
    A->>S: LOGIN|nickA
    S-->>A: RECONNECT_QUERY|roomId|nickB
    alt player accepts
        A->>S: RECONNECT_ACCEPT
        S-->>A: OK|sessionId
        S-->>A: GAME_START, GAME_STATE, DEAL_CARDS, (YOUR_TURN)
        S-->>B: PLAYER_RECONNECTED|nickA
        S-->>B: GAME_START, DEAL_CARDS (resync)
    else player declines
        A->>S: RECONNECT_DECLINE
        S-->>B: OPPONENT_LEFT|nickA|declined
    else 30s elapse with no response
        Note over S: cleanupTimedOutDisconnectedPlayers()
        S-->>B: OPPONENT_LEFT|nickA|timeout
    end
```

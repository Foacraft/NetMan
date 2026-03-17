# NetMan Integration Testing

## Prerequisites

- Java 17
- Node.js (for mineflayer bot)
- npm dependencies installed:

```bash
npm install
```

This installs `mineflayer` and `rcon-client` used by the test script.

## Server Configuration

First run `./gradlew :plugin:runServer` to generate the server files, then modify `plugin/run/paper/server.properties`:

```properties
online-mode=false
enable-rcon=true
rcon.password=netman
```

- `online-mode=false` — allows the mineflayer bot to connect without Mojang authentication
- `enable-rcon=true` + `rcon.password=netman` — allows the test script to send console commands (e.g. `/nm start`) via RCON

## Test Plugins

The `runServer` task automatically downloads these plugins for testing packet attribution:

| Plugin           | Version | Why                                                        |
|------------------|---------|------------------------------------------------------------|
| TAB              | 5.2.1   | Sends tab list header/footer packets via its own scheduler |
| EssentialsX      | 2.20.1  | Sends player ability packets on join                       |
| DecentHolograms  | 2.8.8   | Uses Bukkit entity API (packets come from NMS, not DH)     |

## Running the Test

### Manual (two terminals)

**Terminal 1** — start the server:

```bash
./gradlew :plugin:runServer
```

**Terminal 2** — once the server is ready, run the test:

```bash
node test-netman.js
```

### Automated (single command)

```bash
./gradlew :plugin:shadowJar && \
(./gradlew :plugin:runServer &
for i in $(seq 1 60); do
  nc -z localhost 25565 2>/dev/null && nc -z localhost 25575 2>/dev/null && break
  sleep 1
done
sleep 3
node test-netman.js
pkill -f "paper.jar")
```

## What the Test Does

1. Connects to the server via RCON and runs `/nm start` to begin analysis
2. Creates a test hologram via DecentHolograms (`/dh create test_holo`)
3. Connects a mineflayer bot (`TestBot`) to generate real Netty traffic
4. The bot moves, jumps, looks around, and sends chat messages for ~5 seconds
5. Waits for the collector to accumulate snapshots
6. Fetches `/api/stats` from the web dashboard and validates:

### Required checks (must pass)

| Check                   | What it validates                                    |
|-------------------------|------------------------------------------------------|
| Service = NetMan        | API returns correct service identifier               |
| Has traffic data        | `bytesIn > 0` and `bytesOut > 0`                     |
| Has player data         | `players` array contains `TestBot`                   |
| Has packet types        | `packets` array is non-empty                         |
| Has plugin attribution  | At least one plugin detected via agent stack walking  |
| Has packet sources      | Packets have source attribution (plugin or NMS category) |
| TAB detected            | TAB plugin identified in packet attribution           |
| EssentialsX detected    | EssentialsX identified in packet attribution          |

### Informational checks (logged but don't fail the test)

| Check                    | Notes                                                   |
|--------------------------|---------------------------------------------------------|
| DecentHolograms detected | Expected to fail — DH uses Bukkit API, packets originate from NMS Entity Tracker |

7. Cleans up: disconnects bot, deletes test hologram, sends `/nm stop`, closes RCON

## Expected Output

```
========== TRAFFIC RESULTS ==========
Total bytes in:  ~3800
Total bytes out: ~1870000
Packet types:    ~425
Players:         1
Player "TestBot": in=3843 out=1874033

========== PLUGIN ATTRIBUTION ==========
  Plugin: Essentials (total packets: 2)
    Packet 0x0: 2x  class=net.minecraft.network.protocol.game.PacketPlayOutAbilities

  Plugin: TAB (total packets: 1)
    Packet 0x119b: 1x  class=net.minecraft.network.protocol.game.PacketPlayOutPlayerListHeaderFooter

========== NMS CATEGORIES ==========
  Entity Tracker: ~1400x
  World/Chunks: ~450x
  Tab List: ~55x
  Paper: ~46x
  Bundle: ~37x
  ...

========== VALIDATIONS ==========
  PASS: Service = NetMan
  PASS: Has traffic data
  PASS: Has player data
  PASS: Has packet types
  PASS: Has plugin attribution
  PASS: Has packet sources
  PASS: TAB detected
  PASS: EssentialsX detected
  FAIL: DecentHolograms detected (info only)

========== ALL TESTS PASSED ==========
```

## Ports Used

| Port  | Service              |
|-------|----------------------|
| 25565 | Minecraft server     |
| 25575 | RCON                 |
| 12345 | NetMan web dashboard |

## Notes on Plugin Attribution

- **TAB**: Detected via agent stack walking. TAB constructs `PacketPlayOutPlayerListHeaderFooter` packets; the agent captures the construction stack which includes TAB classes.
- **EssentialsX**: Detected via agent stack walking. Essentials sends `PacketPlayOutAbilities` on player join.
- **DecentHolograms**: Uses Bukkit's entity API to spawn armor stands with custom names. The actual network packets are constructed by NMS Entity Tracker, not by DecentHolograms' code. This is expected behavior — the agent correctly attributes these packets to NMS rather than to DH.

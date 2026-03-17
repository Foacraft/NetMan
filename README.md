# NetMan

Real-time network traffic analysis and packet attribution for Paper servers.

> **Only supports Paper 1.20.1**

## Setup

1. Build:
   ```bash
   ./gradlew build
   ```

2. Place `agent/build/libs/agent-1.0.0.jar` on the server, and `plugin/build/libs/plugin-1.0.0.jar` in `plugins/`.

3. Start the server with the agent:
   ```
   java -javaagent:agent-1.0.0.jar -jar paper.jar
   ```

## Usage

```
/nm start [port]    Start analysis (default port: 12345)
/nm stop            Stop analysis
/nm status          Show current status
```

Aliases: `/netman`, `/nman`, `/nm`

Open `http://<server-ip>:<port>` in a browser to view the live dashboard.

## Without the Agent

The plugin works without `-javaagent`, but plugin attribution will only detect plugins that use custom packet wrapper classes. NMS category labelling always works.

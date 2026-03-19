# NetMan

Real-time network traffic analysis and packet attribution for Paper servers.

> **Only supports Paper 1.20.1**

![NetMan Dashboard](docs/images/screenshot.png)

## Setup

1. Build:
   ```bash
   ./gradlew build
   ```

2. 构建产物位于 `build/` 目录：`netman-<version>.jar`（插件）和 `netman-agent-<version>.jar`（Agent）。

3. 将 `netman-agent-<version>.jar` 放在服务器目录，`netman-<version>.jar` 放入 `plugins/`。

4. 启动服务器：
   ```
   java -javaagent:netman-agent-1.0.0.jar -jar paper.jar
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

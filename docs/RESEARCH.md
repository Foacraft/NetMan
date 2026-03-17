# NetMan — Research & Architecture Notes

## Background

NetMan is a Bukkit plugin that migrates and extends the `NetworkAnalyze` Artifex script.
The core goal is real-time per-player, per-packet-type network traffic analysis with
**plugin-level attribution** — knowing which plugin is responsible for each outbound packet.

---

## The Attribution Problem

### Why it's hard

Netty processes packets on a dedicated IO thread. By the time a `ChannelDuplexHandler.write()`
fires (after encoding), the call stack contains only:
- Netty internals (`io.netty.channel.*`)
- JDK internals (`java.lang.Thread.run`)

The plugin that originally called `player.sendPacket(...)` or equivalent is **gone from the stack**.
This is the fundamental reason the original script could only attribute packets whose Java class
was loaded by a `PluginClassLoader` (e.g. ModelEngine's `ProtectedPacket`).

### What the script could attribute

1. **Plugin-owned wrapper classes**: If a plugin defines its own packet wrapper (e.g.
   `com.ticxo.modelengine.mark.packet.wrapper.ProtectedPacket`), it is loaded by the plugin's
   `PluginClassLoader`. We detect this via `cls.classLoader instanceof PluginClassLoader` and
   use `JavaPlugin.getProvidingPlugin(Class)` to get the plugin name.

2. **NMS category labelling**: Keyword-matching against `cls.simpleName` maps raw NMS packet
   classes to human-readable subsystem names (Entity Tracker, BossBar, etc.). These are
   prefixed without `\u0001` to distinguish them from real plugin names.

### What the script could NOT attribute

Plugins that send raw NMS packets (e.g. `PacketPlayOutEntityTeleport`) using Paper's packet
API. These classes are loaded by the server's app classloader, not a plugin classloader, so
path 1 fails. And at Netty write time, the plugin's stack is gone.

---

## The Agent Solution

Reference implementation studied: `network-tracker-bukkit-1.0.2-all.jar`

### Approach

1. **Java Agent** loaded via `-javaagent:netman-agent.jar` on the JVM command line.
2. **ASM `ClassFileTransformer`** intercepts every class load.
3. For classes matching `net/minecraft/network/` or containing `PacketPlay` (the NMS packet
   class families), the transformer injects a static call at the end of every constructor:
   ```java
   PacketAttachmentStore.capture(this);
   ```
4. `PacketAttachmentStore.capture()` captures `Thread.currentThread().getStackTrace()` and
   stores it in a `WeakHashMap<Object, StackTraceElement[]>` (weakly keyed so packets that
   are never sent are GC'd without leaking).
5. In the plugin's `PacketSourceHandler.write()`, before the packet is handed off to the
   encoder, we call `PacketAttachmentStore.get(packet)` to retrieve the saved stack.
6. We walk the saved stack, skipping frames from server internals, to find the first frame
   that belongs to a plugin class — resolved via `JavaPlugin.getProvidingPlugin(Class)`.

### Why this works

The packet constructor runs **on the game thread** (the thread where the plugin called
the send API). At that moment the plugin's full call stack is present. By saving it
immediately, we preserve the attribution information even though the actual network write
happens later on the IO thread.

### Stack walk filtering

Skip frames with these prefixes:
- `java.`, `javax.`, `sun.`, `com.sun.`
- `io.netty.`
- `net.minecraft.`, `org.bukkit.`, `org.spigotmc.`, `io.papermc.`, `com.destroystokyo.`, `com.mojang.`
- `com.foacraft.netman.` (ourselves)

Also skip "library plugins" that proxy packets for other plugins:
- ProtocolLib, ViaVersion, ViaBackwards, ViaRewind, ProtocolSupport, Geyser-Spigot

Class resolution for stack frames uses:
1. Thread context classloader first (may have plugin visibility on game thread)
2. Check if resolved class loader is a `PluginClassLoader`
3. Cache results in `ConcurrentHashMap<String, String>` to avoid repeated `Class.forName`

---

## Source Key Format

All packet sources are stored in `TrafficCollector.packetSources` as:
```
Map<Int packetId, Map<String sourceKey, AtomicLong count>>
```

**sourceKey format:**
- Real plugin: `"\u0001" + pluginName + "\u001f" + fullClassName`
- NMS category: `categoryName + "\u001f" + fullClassName`

The `\u0001` prefix (SOH control character) is the sentinel distinguishing real plugins
from NMS category labels. The `\u001f` (Unit Separator) is the delimiter between the
label and the class name.

This format allows:
- The JSON `sources[]` array to carry a `"plugin": true/false` field per entry
- The `plugins[]` JSON section to filter exclusively to `\u0001`-prefixed entries
- Efficient prefix-check without a separate boolean field in the map key

---

## Netty Pipeline Layout

After injection, each player's pipeline looks like:

```
[HEAD]
  splitter           ← Paper/Netty: splits incoming ByteStream into packets
  netman_traffic     ← TrafficHandler (addAfter "splitter")
  decoder            ← NMS packet decoder
  ...
  encoder            ← NMS packet encoder
  prepender          ← adds length VarInt prefix
  netman_source      ← PacketSourceHandler (addLast, before TAIL)
[TAIL]
```

**Inbound**: `TrafficHandler.channelRead()` sees ByteBuf = `[packetId_varint][data]`.
**Outbound**:
1. `PacketSourceHandler.write()` sees raw Packet object → sets `PACKET_SOURCE` ThreadLocal
2. Packet is encoded → `prepender` adds length prefix → ByteBuf = `[length][packetId][data]`
3. `TrafficHandler.write()` sees the encoded ByteBuf → reads packetId from position 1 (after
   the length VarInt) → records traffic → reads `PACKET_SOURCE` → records source attribution

---

## NMS Class Name Conventions

Paper 1.20.1 uses **Mojang-mapped** class names at runtime (introduced in Paper 1.18).
However, this server also uses some Bukkit-remapped names that appear as `PacketPlay*`.

Keyword patterns in `PacketSourceTracker.categorizeNms()` cover both:
- Mojang: `TeleportEntity`, `MoveEntity`, `RotateHead`, `SetPassengers`
- Bukkit: `EntityTeleport`, `RelEntity`, `HeadRotat`, `PlayOutMount`

---

## Netty Backpressure Tracking

`PressureEntry` per player tracks:
- `pendingBytes` / `peakPendingBytes`: sampled from `Channel.unsafe().outboundBuffer().totalPendingWriteBytes()`
- `isWritable` / `unwritableEvents`: via `channelWritabilityChanged()` callback
- EventLoop task queue depth: `SingleThreadEventExecutor.pendingTasks()` across all unique event loops

These indicate when the server is producing packets faster than the client/network can consume them.

---

## Build & Deployment

```
NetMan/
├── agent/          — Java module, produces netman-agent.jar (with bundled ASM)
│   └── Manifest: Premain-Class = com.foacraft.netman.agent.NetManAgent
└── plugin/         — Kotlin module, produces netman-plugin.jar (shadow jar with relocated kotlin)
```

### Server setup

1. Build: `./gradlew build`
2. Place `agent/build/libs/agent-1.0.0.jar` somewhere accessible
3. Place `plugin/build/libs/plugin-1.0.0.jar` in `plugins/`
4. Add to server start script:
   ```
   java -javaagent:/path/to/netman-agent.jar -jar paper.jar
   ```
5. In-game: `/nm start [port]` (default 12345), then visit `http://<server-ip>:<port>`

### Without the agent

The plugin works without the `-javaagent` flag, but plugin attribution falls back to
classloader-only detection (only plugins with custom packet wrapper classes will be shown
in the Plugins tab). NMS category labelling always works regardless.

---

## Known Limitations

1. **Spigot remapped class names**: On Spigot (non-Paper) 1.20.1, NMS classes may have
   obfuscated names that don't match keyword patterns. The reference implementation had a
   `NewClassLoaderUnremapperFactory` for this. Paper uses Mojang mappings and is unaffected.

2. **Async packet sends**: If a plugin sends packets from an async thread, the saved
   stack will show async scheduler frames. Resolution may still find the plugin's class
   if it's in the stack before the scheduler frame.

3. **WeakHashMap threading**: `Collections.synchronizedMap(new WeakHashMap<>())` is used.
   Under extreme load (many packets/second), this synchronized wrapper is a contention point.
   Consider `ConcurrentHashMap` with `WeakReference` values if this becomes an issue.

4. **Agent classloading order**: The agent must be loaded before any NMS packet classes
   are loaded. Since `-javaagent` is processed at JVM startup before the server main class
   runs, this is guaranteed in normal operation.

package com.foacraft.netman

import com.foacraft.netman.collector.PressureEntry
import com.foacraft.netman.collector.TrafficCollector
import com.foacraft.netman.handler.PacketSourceHandler
import com.foacraft.netman.handler.TrafficHandler
import com.foacraft.netman.http.NetworkWebServer
import io.netty.channel.Channel
import io.netty.channel.EventLoop
import io.netty.util.concurrent.SingleThreadEventExecutor
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.java.JavaPlugin
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class NetManPlugin : JavaPlugin(), Listener {

    private val collector        = TrafficCollector()
    private var webServer: NetworkWebServer? = null
    private var scheduler: ScheduledExecutorService? = null
    private val playerChannels   = ConcurrentHashMap<String, Channel>()
    private val handlerName      = "netman_traffic"
    private val sourceHandlerName = "netman_source"

    @Volatile private var pendingStart: Pair<Int, org.bukkit.command.CommandSender>? = null

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onEnable() {
        server.pluginManager.registerEvents(this, this)

        val cmd = getCommand("netman") ?: return
        val handler = object : org.bukkit.command.CommandExecutor {
            override fun onCommand(
                sender: org.bukkit.command.CommandSender,
                command: org.bukkit.command.Command,
                label: String, args: Array<String>
            ): Boolean {
                handleCommand(sender, args)
                return true
            }
        }
        cmd.setExecutor(handler)
        cmd.tabCompleter = object : org.bukkit.command.TabCompleter {
            override fun onTabComplete(
                sender: org.bukkit.command.CommandSender,
                command: org.bukkit.command.Command,
                alias: String, args: Array<String>
            ): List<String> {
                if (args.size == 1) return listOf("start", "stop", "status", "confirm", "cancel")
                    .filter { it.startsWith(args[0], ignoreCase = true) }
                return emptyList()
            }
        }

        logger.info("NetMan enabled.")
    }

    override fun onDisable() {
        server.onlinePlayers.forEach { removeHandler(it) }
        playerChannels.clear()
        webServer?.stop()
        scheduler?.shutdown()
        webServer = null
        scheduler = null
        logger.info("NetMan disabled.")
    }

    // ── Player events ─────────────────────────────────────────────────────────

    @EventHandler
    fun onJoin(e: PlayerJoinEvent) {
        collector.onlinePlayers.add(e.player.name)
        if (webServer != null) injectHandler(e.player)
    }

    @EventHandler
    fun onQuit(e: PlayerQuitEvent) {
        collector.onlinePlayers.remove(e.player.name)
        removeHandler(e.player)
    }

    // ── Command handling ──────────────────────────────────────────────────────

    private fun handleCommand(sender: org.bukkit.command.CommandSender, args: Array<String>) {
        if (!sender.hasPermission("netman.admin")) {
            sender.sendMessage("§cNo permission.")
            return
        }
        when (args.getOrNull(0)?.lowercase()) {
            "start" -> {
                val port = args.getOrNull(1)?.toIntOrNull() ?: 12345
                if (port !in 1024..65535) {
                    sender.sendMessage("§c[NetMan] Invalid port (1024-65535)")
                    return
                }
                startAnalysis(port, sender)
            }
            "stop"    -> stopAnalysis(sender)
            "confirm" -> {
                val pending = pendingStart ?: run {
                    sender.sendMessage("§7[NetMan] Nothing to confirm.")
                    return
                }
                pendingStart = null
                val (port, origSender) = pending
                origSender.sendMessage("§e[NetMan] §fTaking over port §e$port§f...")
                server.scheduler.runTaskAsynchronously(this, Runnable {
                    stopRemoteServer(port)
                    if (!waitForPort(port)) {
                        server.scheduler.runTask(this, Runnable {
                            origSender.sendMessage("§c[NetMan] Takeover failed: port not freed in time.")
                        })
                        return@Runnable
                    }
                    server.scheduler.runTask(this, Runnable { doStartAnalysis(port, origSender) })
                })
            }
            "cancel" -> {
                if (pendingStart != null) { pendingStart = null; sender.sendMessage("§7[NetMan] Cancelled.") }
                else sender.sendMessage("§7[NetMan] Nothing to cancel.")
            }
            "status" -> {
                val ws = webServer
                if (ws != null)
                    sender.sendMessage("§a[NetMan] §fRunning on port §e${ws.port}§f, monitoring §e${collector.onlinePlayers.size}§f player(s)")
                else
                    sender.sendMessage("§7[NetMan] Not running. Use §f/nm start [port]§7 to start.")
            }
            else -> sender.sendMessage("§7Usage: /nm <start|stop|status|confirm|cancel>")
        }
    }

    // ── Port helpers ──────────────────────────────────────────────────────────

    private fun isPortAvailable(port: Int): Boolean =
        try { java.net.ServerSocket(port).use { true } } catch (_: Exception) { false }

    private fun isNetManServer(port: Int): Boolean {
        return try {
            val conn = java.net.URL("http://localhost:$port/api/stats")
                .openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 800; conn.readTimeout = 800; conn.requestMethod = "GET"
            if (conn.responseCode != 200) return false
            conn.inputStream.bufferedReader().readText().contains("\"service\":\"NetMan\"")
        } catch (_: Exception) { false }
    }

    private fun stopRemoteServer(port: Int) {
        try {
            val conn = java.net.URL("http://localhost:$port/api/stop")
                .openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 800; conn.readTimeout = 1500; conn.requestMethod = "POST"
            conn.responseCode
        } catch (_: Exception) {}
    }

    private fun waitForPort(port: Int, timeoutMs: Long = 3000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (isPortAvailable(port)) return true
            Thread.sleep(150)
        }
        return false
    }

    // ── Start / Stop ──────────────────────────────────────────────────────────

    private fun startAnalysis(port: Int, sender: org.bukkit.command.CommandSender) {
        if (isPortAvailable(port)) { doStartAnalysis(port, sender); return }
        if (isNetManServer(port)) {
            pendingStart = Pair(port, sender)
            sender.sendMessage("§e[NetMan] §fPort §e$port §fis in use by another NetMan instance.")
            sender.sendMessage("§eType §f/nm confirm §eto take over, §f/nm cancel §eto abort.")
        } else {
            sender.sendMessage("§c[NetMan] Port §e$port §cis in use by another process. Choose a different port.")
        }
    }

    private fun doStartAnalysis(port: Int, sender: org.bukkit.command.CommandSender) {
        webServer?.stop()
        scheduler?.shutdown()
        webServer = null; scheduler = null

        val htmlBytes = getResource("index.html")?.readBytes() ?: run {
            sender.sendMessage("§c[NetMan] Missing index.html resource.")
            return
        }

        val ws = NetworkWebServer(collector, port, htmlBytes) {
            server.scheduler.runTask(this, Runnable { stopAnalysis(null) })
        }
        try { ws.start() } catch (e: Exception) {
            sender.sendMessage("§c[NetMan] Failed to start HTTP server: ${e.message}")
            return
        }
        webServer = ws

        scheduler = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "NetMan-Scheduler").also { it.isDaemon = true }
        }.also {
            it.scheduleAtFixedRate({
                try {
                    sampleNettyPressure()
                    collector.snapshot()
                    server.scheduler.runTask(this, Runnable {
                        server.onlinePlayers.forEach { p -> collector.playerPings[p.name] = p.ping }
                    })
                } catch (_: Exception) {}
            }, 1, 1, TimeUnit.SECONDS)
        }

        server.scheduler.runTask(this, Runnable {
            server.onlinePlayers.forEach { collector.onlinePlayers.add(it.name); injectHandler(it) }
        })
        sender.sendMessage("§a[NetMan] §fStarted. Visit §ehttp://<ip>:$port§f for live stats.")
    }

    private fun stopAnalysis(sender: org.bukkit.command.CommandSender?) {
        if (webServer == null) { sender?.sendMessage("§7[NetMan] Not running."); return }
        server.scheduler.runTask(this, Runnable { server.onlinePlayers.forEach { removeHandler(it) } })
        webServer?.stop()
        scheduler?.shutdown()
        webServer = null; scheduler = null
        sender?.sendMessage("§c[NetMan] §fStopped.")
    }

    // ── Netty pressure sampling ───────────────────────────────────────────────

    private fun sampleNettyPressure() {
        playerChannels.forEach { (name, ch) ->
            try {
                val pending = ch.unsafe().outboundBuffer()?.totalPendingWriteBytes() ?: 0L
                collector.playerPressure.getOrPut(name) { PressureEntry() }.updatePending(pending)
            } catch (_: Exception) {}
        }
        val seenLoops = hashSetOf<EventLoop>()
        playerChannels.values.forEach { seenLoops.add(it.eventLoop()) }
        var maxQueue = 0
        seenLoops.forEach { loop ->
            try {
                val n = (loop as? SingleThreadEventExecutor)?.pendingTasks() ?: 0
                if (n > maxQueue) maxQueue = n
            } catch (_: Exception) {}
        }
        collector.eventLoopMaxQueueSize = maxQueue
    }

    // ── Netty injection ───────────────────────────────────────────────────────

    private fun injectHandler(player: Player) {
        val channel = getChannel(player) ?: return
        playerChannels[player.name] = channel
        channel.eventLoop().execute {
            try {
                if (channel.pipeline().get("splitter") != null) {
                    if (channel.pipeline().get(handlerName) == null)
                        channel.pipeline().addAfter("splitter", handlerName, TrafficHandler(collector, player.name))
                    if (channel.pipeline().get(sourceHandlerName) == null)
                        channel.pipeline().addLast(sourceHandlerName, PacketSourceHandler())
                }
            } catch (_: Exception) {}
        }
    }

    private fun removeHandler(player: Player) {
        playerChannels.remove(player.name)
        val channel = getChannel(player) ?: return
        channel.eventLoop().execute {
            try {
                if (channel.pipeline().get(handlerName) != null) channel.pipeline().remove(handlerName)
                if (channel.pipeline().get(sourceHandlerName) != null) channel.pipeline().remove(sourceHandlerName)
            } catch (_: Exception) {}
        }
    }

    private fun getChannel(player: Player): Channel? {
        return try {
            val handle = player.javaClass.getMethod("getHandle").invoke(player)
            // Field names are obfuscated in Spigot mappings, so search by type
            val playerConn = getFieldByType(handle, "net.minecraft.server.network.PlayerConnection")
                ?: getFieldByType(handle, "net.minecraft.server.network.ServerGamePacketListenerImpl")
                ?: return null
            val networkManager = getFieldByType(playerConn, "net.minecraft.network.NetworkManager")
                ?: getFieldByType(playerConn, "net.minecraft.network.Connection")
                ?: return null
            getFieldByType(networkManager, Channel::class.java) as? Channel
        } catch (_: Exception) { null }
    }

    private fun getFieldByType(obj: Any, typeName: String): Any? {
        var cls: Class<*>? = obj.javaClass
        while (cls != null) {
            for (f in cls.declaredFields) {
                if (f.type.name == typeName) {
                    f.isAccessible = true
                    return f.get(obj)
                }
            }
            cls = cls.superclass
        }
        return null
    }

    private fun getFieldByType(obj: Any, type: Class<*>): Any? {
        var cls: Class<*>? = obj.javaClass
        while (cls != null) {
            for (f in cls.declaredFields) {
                if (type.isAssignableFrom(f.type)) {
                    f.isAccessible = true
                    return f.get(obj)
                }
            }
            cls = cls.superclass
        }
        return null
    }
}

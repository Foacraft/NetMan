package com.foacraft.netman.handler

import com.foacraft.netman.agent.PacketAttachmentStore
import java.util.concurrent.ConcurrentHashMap

/**
 * Handles packet → (plugin/category) attribution.
 *
 * Source key format (stored in TrafficCollector.packetSources):
 *   "\u0001pluginName\u001fclassName"  — real plugin (identified via class loader or agent stack)
 *   "categoryName\u001fclassName"      — NMS/Paper subsystem (keyword-matched)
 *
 * Two attribution paths:
 *   1. FAST (classloader): packet class was loaded by PluginClassLoader → plugin owns it.
 *      Works for ModelEngine ProtectedPacket, PacketEvents wrappers, etc.
 *   2. AGENT (stack walk): packet is a raw NMS class but the agent captured the
 *      construction-time stack. Walk saved stack skipping server/NMS frames.
 *      Works for plugins that send raw NMS packets (Essentials, WorldEdit, etc.).
 *   3. FALLBACK: keyword-based NMS category labelling (Entity Tracker, BossBar, etc.).
 */
object PacketSourceTracker {

    /** Carries the source label from PacketSourceHandler to TrafficHandler in the pipeline. */
    val PACKET_SOURCE = ThreadLocal<String?>()

    private val PLUGIN_CLASS_LOADER_CLASS: Class<*>? = try {
        Class.forName("org.bukkit.plugin.java.PluginClassLoader")
    } catch (_: Exception) { null }

    private val GET_PROVIDING_PLUGIN: java.lang.reflect.Method? = try {
        Class.forName("org.bukkit.plugin.java.JavaPlugin")
            .getMethod("getProvidingPlugin", Class::class.java)
    } catch (_: Exception) { null }

    /** Cache className → plugin name to avoid repeated Class.forName calls */
    private val classToPluginCache = ConcurrentHashMap<String, String>()

    // Frames to skip when walking the agent-captured stack
    private val SKIP_PREFIXES = listOf(
        "java.", "javax.", "sun.", "com.sun.",
        "io.netty.",
        "net.minecraft.", "org.bukkit.", "org.spigotmc.",
        "io.papermc.", "com.destroystokyo.", "com.mojang.",
        "com.foacraft.netman."   // skip ourselves
    )

    // Library plugins that forward packets for other plugins — skip them so we
    // attribute to the actual initiating plugin further up the stack.
    private val LIBRARY_PLUGINS = setOf(
        "ProtocolLib", "ViaVersion", "ViaBackwards", "ViaRewind",
        "ProtocolSupport", "Geyser-Spigot"
    )

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Returns a source key for the given packet object.
     * Checks classloader, then agent stack, then keyword fallback.
     */
    fun identifySource(packet: Any): String {
        val cls = packet.javaClass
        val loader = cls.classLoader

        // Path 1: plugin-loaded class
        if (PLUGIN_CLASS_LOADER_CLASS != null && PLUGIN_CLASS_LOADER_CLASS.isInstance(loader)) {
            val pluginName = resolvePluginFromApi(cls) ?: resolvePluginFromLoader(loader!!) ?: "Unknown Plugin"
            return "\u0001$pluginName\u001f${cls.name}"
        }

        // Path 2: agent captured stack for this packet
        val stack = try { PacketAttachmentStore.get(packet) } catch (_: Throwable) { null }
        if (stack != null) {
            val pluginName = walkStackForPlugin(stack)
            PacketAttachmentStore.remove(packet)
            if (pluginName != null) {
                return "\u0001$pluginName\u001f${cls.name}"
            }
        }

        // Path 3: keyword-based NMS category
        val category = categorizeNms(cls.simpleName)
        return "$category\u001f${cls.name}"
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    private fun resolvePluginFromApi(cls: Class<*>): String? = try {
        val plugin = GET_PROVIDING_PLUGIN?.invoke(null, cls) ?: null
        if (plugin == null) null
        else plugin.javaClass.getMethod("getName").invoke(plugin) as? String
    } catch (_: Exception) { null }

    private fun resolvePluginFromLoader(loader: ClassLoader): String? {
        try {
            val plugin = loader.javaClass.getMethod("getPlugin").invoke(loader)
            val name = plugin?.javaClass?.getMethod("getName")?.invoke(plugin) as? String
            if (!name.isNullOrEmpty()) return name
        } catch (_: Exception) {}
        try {
            val f = PLUGIN_CLASS_LOADER_CLASS!!.getDeclaredField("plugin").also { it.isAccessible = true }
            val plugin = f.get(loader)
            val name = plugin?.javaClass?.getMethod("getName")?.invoke(plugin) as? String
            if (!name.isNullOrEmpty()) return name
        } catch (_: Exception) {}
        try {
            val desc = loader.javaClass.getMethod("getPluginDescription").invoke(loader)
            val name = desc?.javaClass?.getMethod("getName")?.invoke(desc) as? String
            if (!name.isNullOrEmpty()) return name
        } catch (_: Exception) {}
        return null
    }

    private fun walkStackForPlugin(stack: Array<StackTraceElement>): String? {
        for (frame in stack) {
            val className = frame.className
            if (SKIP_PREFIXES.any { className.startsWith(it) }) continue
            val cached = classToPluginCache[className]
            if (cached != null) {
                return if (cached == "") null else cached
            }
            val pluginName = resolveClassToPlugin(className)
            classToPluginCache[className] = pluginName ?: ""
            if (pluginName != null && pluginName !in LIBRARY_PLUGINS) return pluginName
        }
        return null
    }

    private fun resolveClassToPlugin(className: String): String? {
        return try {
            // Use the plugin classloader first — Paper's shared classloader system lets plugins see
            // each other's classes. The Netty IO thread's contextClassLoader may be the system
            // classloader which can't find plugin classes.
            val cl = PacketSourceTracker::class.java.classLoader
                ?: Thread.currentThread().contextClassLoader
            val cls = Class.forName(className, false, cl)
            resolvePluginFromApi(cls) ?: run {
                val loader = cls.classLoader ?: return null
                if (PLUGIN_CLASS_LOADER_CLASS?.isInstance(loader) == true) resolvePluginFromLoader(loader)
                else null
            }
        } catch (_: Exception) { null }
    }

    private fun categorizeNms(n: String): String = when {
        "TeleportEntity"   in n || "EntityTeleport"   in n -> "Entity Tracker"
        "MoveEntity"       in n || "RelEntity"        in n -> "Entity Tracker"
        "AddEntity"        in n || "SpawnEntity"      in n -> "Entity Tracker"
        "NamedEntitySpawn" in n                           -> "Entity Tracker"
        "RemoveEntit"      in n || "EntityDestroy"    in n -> "Entity Tracker"
        "EntityMetaD"      in n || "EntityMetad"      in n -> "Entity Tracker"
        "EntityVelocity"   in n || "EntityLink"       in n -> "Entity Tracker"
        "AttachEntity"     in n                           -> "Entity Tracker"
        "Equipment"        in n || "RotateHead"       in n -> "Entity Tracker"
        "HeadRotat"        in n                           -> "Entity Tracker"
        "EntityLook"       in n                           -> "Entity Tracker"
        "EntityAnim"       in n || "EntityEvent"      in n -> "Entity Tracker"
        "EntityStatus"     in n                           -> "Entity Tracker"
        "UpdateAttrib"     in n                           -> "Entity Tracker"
        "PlayOutMount"     in n || "SetPassenger"     in n -> "Entity Tracker"
        "MobEffect"        in n || "EntityEffect"     in n -> "Effects"
        "Score"            in n || "Objective"        in n -> "Scoreboard"
        "PlayerTeam"       in n || "SetPlayerTeam"    in n -> "Teams"
        "ScoreboardTeam"   in n                           -> "Teams"
        "PlayerInfo"       in n                           -> "Player List"
        "Chat"             in n                           -> "Chat"
        "Chunk"            in n || "ForgetLevel"      in n -> "World/Chunks"
        "CacheCenter"      in n || "CacheRadius"      in n -> "World/Chunks"
        "SimulationDist"   in n                           -> "World/Chunks"
        "SectionBlocks"    in n || "BlockUpdate"      in n -> "World/Blocks"
        "BlockEntity"      in n || "BlockAction"      in n -> "World/Blocks"
        "LevelEvent"       in n || "GameEvent"        in n -> "World/Events"
        "Sound"            in n                           -> "Sound"
        "Particle"         in n                           -> "Particles"
        "KeepAlive"        in n || "Ping"             in n -> "Connection"
        "BossEvent"        in n || "PlayOutBoss"      in n -> "BossBar"
        "TabList"          in n || "ListHeader"       in n -> "Tab List"
        "Title"            in n || "Subtitle"         in n -> "Title"
        "ActionBar"        in n                           -> "Title"
        "Container"        in n || "OpenScreen"       in n -> "Inventory"
        "SetSlot"          in n || "WindowItem"       in n -> "Inventory"
        "CarriedItem"      in n                           -> "Inventory"
        "Light"            in n                           -> "World/Light"
        "Bundle"           in n                           -> "Bundle"
        "CustomPayload"    in n                           -> "Plugin Channel"
        "ResourcePack"     in n                           -> "Resource Pack"
        "Login"            in n || "Respawn"          in n -> "World/Join"
        "PlayerPosition"   in n || "PlayerAbilit"     in n -> "Player State"
        "PlayOutAbilities" in n || "PlayOutAbil"      in n -> "Player State"
        "UpdateTime"       in n                           -> "World/Events"
        "Health"           in n || "Experience"       in n -> "Player Stats"
        "Damage"           in n || "HurtAnim"         in n -> "Combat"
        "Explode"          in n                           -> "Combat"
        else -> "Paper"
    }
}

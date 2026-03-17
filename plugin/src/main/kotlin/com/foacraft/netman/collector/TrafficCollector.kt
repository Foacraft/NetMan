package com.foacraft.netman.collector

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class TrafficCollector {
    val globalTotal   = PacketCounter()
    val globalPackets = ConcurrentHashMap<Int, PacketCounter>()
    val playerTotals  = ConcurrentHashMap<String, PacketCounter>()
    val playerPackets = ConcurrentHashMap<String, ConcurrentHashMap<Int, PacketCounter>>()
    val onlinePlayers     = ConcurrentHashMap.newKeySet<String>()
    val playerPings       = ConcurrentHashMap<String, Int>()
    val playerPressure    = ConcurrentHashMap<String, PressureEntry>()
    /** packetId → (sourceKey → count); sourceKey format documented in PacketSourceTracker */
    val packetSources     = ConcurrentHashMap<Int, ConcurrentHashMap<String, AtomicLong>>()
    @Volatile var eventLoopMaxQueueSize = 0

    private fun globalPkt(id: Int)              = globalPackets.getOrPut(id)  { PacketCounter() }
    private fun playerTotal(name: String)        = playerTotals.getOrPut(name) { PacketCounter() }
    private fun playerPkt(name: String, id: Int) =
        playerPackets.getOrPut(name) { ConcurrentHashMap() }.getOrPut(id) { PacketCounter() }

    fun recordIn(playerName: String?, packetId: Int, bytes: Int) {
        val b = bytes.toLong()
        globalTotal.bytesIn.addAndGet(b); globalTotal.countIn.incrementAndGet()
        globalPkt(packetId).also { it.bytesIn.addAndGet(b); it.countIn.incrementAndGet() }
        if (playerName != null) {
            playerTotal(playerName).also { it.bytesIn.addAndGet(b); it.countIn.incrementAndGet() }
            playerPkt(playerName, packetId).also { it.bytesIn.addAndGet(b); it.countIn.incrementAndGet() }
        }
    }

    fun recordOut(playerName: String?, packetId: Int, bytes: Int) {
        val b = bytes.toLong()
        globalTotal.bytesOut.addAndGet(b); globalTotal.countOut.incrementAndGet()
        globalPkt(packetId).also { it.bytesOut.addAndGet(b); it.countOut.incrementAndGet() }
        if (playerName != null) {
            playerTotal(playerName).also { it.bytesOut.addAndGet(b); it.countOut.incrementAndGet() }
            playerPkt(playerName, packetId).also { it.bytesOut.addAndGet(b); it.countOut.incrementAndGet() }
        }
    }

    fun snapshot() {
        globalTotal.snapshot()
        globalPackets.values.forEach { it.snapshot() }
        playerTotals.values.forEach { it.snapshot() }
        playerPackets.values.forEach { m -> m.values.forEach { it.snapshot() } }
        playerPressure.values.forEach { it.snapshot() }
    }

    fun toJson(): String {
        val sb = StringBuilder()
        sb.append("{\"service\":\"NetMan\",")
        sb.append("\"eventLoopMaxQueue\":").append(eventLoopMaxQueueSize).append(",")
        sb.append("\"total\":{")
        sb.append("\"bytesIn\":").append(globalTotal.bytesIn.get()).append(",")
        sb.append("\"bytesOut\":").append(globalTotal.bytesOut.get()).append(",")
        sb.append("\"bpsIn\":").append(globalTotal.bpsIn).append(",")
        sb.append("\"bpsOut\":").append(globalTotal.bpsOut)
        sb.append("},\"packets\":[")
        var first = true
        globalPackets.entries.sortedBy { it.key }.forEach { (id, c) ->
            if (!first) sb.append(",")
            first = false
            appendPacket(sb, id, c, packetSources[id])
        }
        sb.append("],\"players\":[")
        first = true
        playerTotals.entries.sortedBy { it.key }.forEach { (name, tot) ->
            if (!first) sb.append(",")
            first = false
            sb.append("{\"name\":\"").append(name.replace("\"", "\\\"")).append("\",")
            sb.append("\"online\":").append(onlinePlayers.contains(name)).append(",")
            sb.append("\"ping\":").append(playerPings.getOrDefault(name, -1)).append(",")
            val pr = playerPressure[name]
            sb.append("\"pressure\":{")
            sb.append("\"pendingBytes\":").append(pr?.pendingBytes ?: 0L).append(",")
            sb.append("\"peakBytes\":").append(pr?.snapshotPeakBytes ?: 0L).append(",")
            sb.append("\"isWritable\":").append(pr?.isWritable ?: true).append(",")
            sb.append("\"unwritableEvents\":").append(pr?.snapshotUnwritable ?: 0)
            sb.append("},")
            sb.append("\"bytesIn\":").append(tot.bytesIn.get()).append(",")
            sb.append("\"bytesOut\":").append(tot.bytesOut.get()).append(",")
            sb.append("\"bpsIn\":").append(tot.bpsIn).append(",")
            sb.append("\"bpsOut\":").append(tot.bpsOut).append(",")
            sb.append("\"packets\":[")
            var pf = true
            playerPackets[name]?.entries?.sortedBy { it.key }?.forEach { (id, c) ->
                if (!pf) sb.append(",")
                pf = false
                appendPacket(sb, id, c)
            }
            sb.append("]}")
        }
        sb.append("]")

        // Plugins section — only \u0001-prefixed sources are real plugins
        val pluginPacketList = linkedMapOf<String, MutableList<Triple<Int, String, Long>>>()
        packetSources.forEach { (packetId, sourceMap) ->
            sourceMap.forEach srcLoop@{ (sourceKey, cnt) ->
                if (!sourceKey.startsWith('\u0001')) return@srcLoop
                val rawKey = sourceKey.substring(1)
                val sep = rawKey.indexOf('\u001f')
                val pluginName = if (sep >= 0) rawKey.substring(0, sep) else rawKey
                val clsName    = if (sep >= 0) rawKey.substring(sep + 1) else ""
                pluginPacketList.getOrPut(pluginName) { mutableListOf() }
                    .add(Triple(packetId, clsName, cnt.get()))
            }
        }
        sb.append(",\"plugins\":[")
        var plgFirst = true
        val pluginsSorted = pluginPacketList.entries.toList().sortedByDescending { e ->
            var t = 0L; e.value.forEach { t += it.third }; t
        }
        pluginsSorted.forEach { (pluginName, packets) ->
            if (!plgFirst) sb.append(",")
            plgFirst = false
            var total = 0L; packets.forEach { total += it.third }
            sb.append("{\"name\":\"").append(pluginName.replace("\"", "\\\"")).append("\",")
            sb.append("\"totalCount\":").append(total).append(",")
            sb.append("\"packets\":[")
            var pkFirst = true
            packets.sortedByDescending { it.third }.forEach { triple ->
                if (!pkFirst) sb.append(",")
                pkFirst = false
                sb.append("{\"id\":").append(triple.first).append(",")
                sb.append("\"cls\":\"").append(triple.second.replace("\"", "\\\"")).append("\",")
                sb.append("\"count\":").append(triple.third).append("}")
            }
            sb.append("]}")
        }
        sb.append("]}")
        return sb.toString()
    }

    private fun appendPacket(sb: StringBuilder, id: Int, c: PacketCounter,
                             sources: ConcurrentHashMap<String, AtomicLong>? = null) {
        sb.append("{\"id\":").append(id).append(",")
        sb.append("\"bytesIn\":").append(c.bytesIn.get()).append(",")
        sb.append("\"bytesOut\":").append(c.bytesOut.get()).append(",")
        sb.append("\"countIn\":").append(c.countIn.get()).append(",")
        sb.append("\"countOut\":").append(c.countOut.get()).append(",")
        sb.append("\"bpsIn\":").append(c.bpsIn).append(",")
        sb.append("\"bpsOut\":").append(c.bpsOut)
        if (sources != null && sources.isNotEmpty()) {
            sb.append(",\"sources\":[")
            var sf = true
            sources.entries.sortedByDescending { it.value.get() }.take(12).forEach { (key, cnt) ->
                if (!sf) sb.append(",")
                sf = false
                val isPlugin = key.startsWith('\u0001')
                val rawKey = if (isPlugin) key.substring(1) else key
                val sep = rawKey.indexOf('\u001f')
                val cat = if (sep >= 0) rawKey.substring(0, sep) else rawKey
                val cls = if (sep >= 0) rawKey.substring(sep + 1) else ""
                sb.append("{\"cat\":\"").append(cat.replace("\"", "\\\"")).append("\",")
                sb.append("\"cls\":\"").append(cls.replace("\"", "\\\"")).append("\",")
                sb.append("\"plugin\":").append(isPlugin).append(",")
                sb.append("\"count\":").append(cnt.get()).append("}")
            }
            sb.append("]")
        }
        sb.append("}")
    }
}

package com.foacraft.netman.handler

import com.foacraft.netman.collector.PressureEntry
import com.foacraft.netman.collector.TrafficCollector
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelDuplexHandler
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelPromise
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Sits after "splitter" in the Netty pipeline.
 *
 * Inbound (channelRead): ByteBuf = [packetId_varint][data]
 * Outbound (write):      ByteBuf = [length_varint][packetId_varint][data]  (after prepender)
 *
 * Reads PACKET_SOURCE ThreadLocal set by PacketSourceHandler to attribute outbound packets.
 */
class TrafficHandler(
    private val collector: TrafficCollector,
    private val playerName: String
) : ChannelDuplexHandler() {

    /** Read a VarInt without advancing readerIndex. Returns (value, bytesRead) or null. */
    private fun peekVarInt(buf: ByteBuf, startIndex: Int): Pair<Int, Int>? {
        var value = 0; var shift = 0; var i = 0
        while (i < 5) {
            if (startIndex + i >= buf.writerIndex()) return null
            val b = buf.getByte(startIndex + i).toInt()
            value = value or (b and 0x7F shl shift)
            i++
            if (b and 0x80 == 0) return Pair(value, i)
            shift += 7
        }
        return null
    }

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        try {
            if (msg is ByteBuf && msg.refCnt() > 0 && msg.isReadable) {
                val result = peekVarInt(msg, msg.readerIndex())
                if (result != null) collector.recordIn(playerName, result.first, msg.readableBytes())
            }
        } catch (_: Exception) {}
        super.channelRead(ctx, msg)
    }

    override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
        try {
            if (msg is ByteBuf && msg.refCnt() > 0 && msg.isReadable) {
                val lenResult = peekVarInt(msg, msg.readerIndex())
                if (lenResult != null) {
                    val idResult = peekVarInt(msg, msg.readerIndex() + lenResult.second)
                    if (idResult != null) {
                        collector.recordOut(playerName, idResult.first, msg.readableBytes())
                        val src = PacketSourceTracker.PACKET_SOURCE.get()
                        PacketSourceTracker.PACKET_SOURCE.remove()
                        if (src != null) {
                            collector.packetSources
                                .getOrPut(idResult.first) { ConcurrentHashMap() }
                                .getOrPut(src) { AtomicLong() }
                                .incrementAndGet()
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        super.write(ctx, msg, promise)
    }

    override fun channelWritabilityChanged(ctx: ChannelHandlerContext) {
        val entry = collector.playerPressure.getOrPut(playerName) { PressureEntry() }
        if (ctx.channel().isWritable) entry.markWritable() else entry.markUnwritable()
        super.channelWritabilityChanged(ctx)
    }
}

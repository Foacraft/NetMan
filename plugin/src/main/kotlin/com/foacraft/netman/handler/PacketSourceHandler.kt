package com.foacraft.netman.handler

import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelOutboundHandlerAdapter
import io.netty.channel.ChannelPromise

/**
 * Sits at the very end of the Netty pipeline (addLast), so it sees raw Packet objects
 * before they are encoded to ByteBuf.
 *
 * For each outbound write:
 *   1. Identifies the packet source via PacketSourceTracker (classloader → agent stack → NMS keyword)
 *   2. Sets PACKET_SOURCE ThreadLocal so the upstream TrafficHandler can attribute this write.
 *
 * The TrafficHandler reads and clears PACKET_SOURCE after recording the packet ID.
 */
class PacketSourceHandler : ChannelOutboundHandlerAdapter() {

    override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
        try {
            if (msg !is ByteBuf) {
                val source = PacketSourceTracker.identifySource(msg)
                PacketSourceTracker.PACKET_SOURCE.set(source)
            }
        } catch (_: Exception) {}
        ctx.write(msg, promise)
    }
}

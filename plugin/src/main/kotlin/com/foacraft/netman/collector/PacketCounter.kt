package com.foacraft.netman.collector

import java.util.concurrent.atomic.AtomicLong

class PacketCounter {
    val bytesIn  = AtomicLong(0)
    val bytesOut = AtomicLong(0)
    val countIn  = AtomicLong(0)
    val countOut = AtomicLong(0)
    @Volatile var prevBytesIn  = 0L
    @Volatile var prevBytesOut = 0L
    @Volatile var bpsIn  = 0L
    @Volatile var bpsOut = 0L

    fun snapshot() {
        val ci = bytesIn.get()
        val co = bytesOut.get()
        bpsIn  = ci - prevBytesIn;  prevBytesIn  = ci
        bpsOut = co - prevBytesOut; prevBytesOut = co
    }
}

package com.foacraft.netman.collector

class PressureEntry {
    @Volatile var pendingBytes      = 0L
    @Volatile var peakPendingBytes  = 0L
    @Volatile var snapshotPeakBytes = 0L
    @Volatile var isWritable        = true
    @Volatile var unwritableEvents  = 0
    @Volatile var snapshotUnwritable= 0

    fun updatePending(bytes: Long) {
        pendingBytes = bytes
        if (bytes > peakPendingBytes) peakPendingBytes = bytes
    }

    fun markUnwritable() { isWritable = false; unwritableEvents++ }
    fun markWritable()   { isWritable = true  }

    fun snapshot() {
        snapshotPeakBytes  = peakPendingBytes
        snapshotUnwritable = unwritableEvents
        peakPendingBytes   = pendingBytes
        unwritableEvents   = 0
    }
}

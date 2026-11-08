package com.flexingg.goprohero9.data.stream

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Thread-safe, lock-free in-memory pipe connecting BoundUdpStreamRelay directly to Media3 ExoPlayer.
 *
 * Completely eliminates local UDP loopback sockets (127.0.0.1:8555), preventing:
 * 1. Android bindProcessToNetwork routing conflicts (where loopback lo interface is dropped).
 * 2. EADDRINUSE port collisions.
 * 3. Kernel socket buffer overflows and dropped UDP packets.
 */
@OptIn(UnstableApi::class)
class LiveStreamDataPipe(private val maxQueueSize: Int = 800) {
    companion object {
        private const val TAG = "LiveStreamDataPipe"
    }

    private val packetQueue = LinkedBlockingQueue<ByteArray>(maxQueueSize)
    private var currentPacket: ByteArray? = null
    private var currentPacketOffset: Int = 0

    @Volatile
    var isRunning: Boolean = false
        private set

    fun open() {
        packetQueue.clear()
        currentPacket = null
        currentPacketOffset = 0
        isRunning = true
        Log.d(TAG, "Stream data pipe opened")
    }

    fun write(data: ByteArray, length: Int) {
        if (!isRunning || length <= 0) return
        val chunk = data.copyOf(length)
        // If queue exceeds max capacity (e.g. during initial keyframe search),
        // drop oldest packets to maintain low live latency
        while (!packetQueue.offer(chunk)) {
            packetQueue.poll()
        }
    }

    fun read(target: ByteArray, targetOffset: Int, requestedLength: Int): Int {
        if (!isRunning && packetQueue.isEmpty() && currentPacket == null) {
            return C.RESULT_END_OF_INPUT
        }

        while (currentPacket == null || currentPacketOffset >= currentPacket!!.size) {
            if (!isRunning) return C.RESULT_END_OF_INPUT
            // Block up to 100ms waiting for the next UDP packet from GoPro
            currentPacket = packetQueue.poll(100, TimeUnit.MILLISECONDS)
            currentPacketOffset = 0
            if (currentPacket == null && !isRunning) {
                return C.RESULT_END_OF_INPUT
            }
        }

        val packet = currentPacket ?: return C.RESULT_END_OF_INPUT
        val available = packet.size - currentPacketOffset
        val bytesToCopy = minOf(available, requestedLength)
        System.arraycopy(packet, currentPacketOffset, target, targetOffset, bytesToCopy)
        currentPacketOffset += bytesToCopy

        if (currentPacketOffset >= packet.size) {
            currentPacket = null
            currentPacketOffset = 0
        }

        return bytesToCopy
    }

    fun close() {
        isRunning = false
        packetQueue.clear()
        currentPacket = null
        currentPacketOffset = 0
        Log.d(TAG, "Stream data pipe closed")
    }
}

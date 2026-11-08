package com.flexingg.goprohero9.buffer

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.util.ArrayDeque

data class TsPacket(
    val data: ByteArray,
    val timestampMs: Long
)

/**
 * Circular rolling buffer capturing MPEG-TS packets over UDP.
 * Provides instant 0ms retrospective clip dumps to local disk while the camera's
 * 4K master uncompressed file is retrieved asynchronously via Turbo Transfer.
 */
class CircularClipBuffer(
    private val context: Context,
    private val scope: CoroutineScope,
    private val udpPort: Int = 8554
) {
    companion object {
        private const val TAG = "CircularClipBuffer"
        private const val PACKET_BUFFER_SIZE = 2048
    }

    private val deque = ArrayDeque<TsPacket>()
    private var bufferDurationSeconds = 30
    private var captureJob: Job? = null
    private var socket: DatagramSocket? = null

    fun setBufferDuration(seconds: Int) {
        bufferDurationSeconds = seconds
        trimBuffer()
    }

    fun startCapture() {
        stopCapture()
        captureJob = scope.launch(Dispatchers.IO) {
            try {
                Log.d(TAG, "Starting UDP packet interceptor on port $udpPort...")
                val sock = DatagramSocket(null)
                sock.reuseAddress = true
                sock.bind(java.net.InetSocketAddress(udpPort))
                socket = sock

                val receiveBuffer = ByteArray(PACKET_BUFFER_SIZE)
                val packet = DatagramPacket(receiveBuffer, receiveBuffer.size)

                while (isActive) {
                    sock.receive(packet)
                    val copy = packet.data.copyOf(packet.length)
                    val now = System.currentTimeMillis()

                    synchronized(deque) {
                        deque.addLast(TsPacket(copy, now))
                        val cutoff = now - (bufferDurationSeconds * 1000L)
                        while (deque.isNotEmpty() && deque.first.timestampMs < cutoff) {
                            deque.removeFirst()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Circular buffer capture error (port may be in use by player): ${e.message}")
            }
        }
    }

    fun stopCapture() {
        captureJob?.cancel()
        captureJob = null
        try {
            socket?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing UDP socket: ${e.message}")
        }
        socket = null
        synchronized(deque) {
            deque.clear()
        }
    }

    private fun trimBuffer() {
        val cutoff = System.currentTimeMillis() - (bufferDurationSeconds * 1000L)
        synchronized(deque) {
            while (deque.isNotEmpty() && deque.first.timestampMs < cutoff) {
                deque.removeFirst()
            }
        }
    }

    /**
     * Dumps the current rolling buffer to a local .ts file.
     */
    suspend fun dumpBufferToFile(outFile: File): Boolean = withContext(Dispatchers.IO) {
        try {
            val packetsToDump: List<TsPacket>
            synchronized(deque) {
                packetsToDump = deque.toList()
            }

            if (packetsToDump.isEmpty()) {
                Log.w(TAG, "Circular buffer is empty; nothing to dump.")
                return@withContext false
            }

            FileOutputStream(outFile).use { fos ->
                for (p in packetsToDump) {
                    fos.write(p.data)
                }
                fos.flush()
            }
            Log.d(TAG, "Instant retrospective clip saved to: ${outFile.absolutePath} (${packetsToDump.size} packets)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to dump circular buffer: ${e.message}")
            false
        }
    }
}

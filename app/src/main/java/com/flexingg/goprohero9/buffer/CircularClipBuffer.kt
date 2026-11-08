package com.flexingg.goprohero9.buffer

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.ArrayDeque

data class TsPacket(
    val data: ByteArray,
    val timestampMs: Long
)

/**
 * Circular rolling buffer capturing MPEG-TS packets directly from the unified UDP stream relay.
 * Provides instant retrospective clip dumps to local disk while the camera's
 * 4K master uncompressed file is retrieved asynchronously via Turbo Transfer.
 */
class CircularClipBuffer(
    private val context: Context,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "CircularClipBuffer"
    }

    private val deque = ArrayDeque<TsPacket>()
    private var bufferDurationSeconds = 30
    private var isCapturing = true

    fun setBufferDuration(seconds: Int) {
        bufferDurationSeconds = seconds
        trimBuffer()
    }

    fun startCapture() {
        isCapturing = true
    }

    fun stopCapture() {
        isCapturing = false
        synchronized(deque) {
            deque.clear()
        }
    }

    /**
     * Ingests a raw MPEG-TS packet received from BoundUdpStreamRelay.
     */
    fun ingestPacket(data: ByteArray, length: Int) {
        if (!isCapturing || length <= 0) return
        val copy = data.copyOf(length)
        val now = System.currentTimeMillis()

        synchronized(deque) {
            deque.addLast(TsPacket(copy, now))
            val cutoff = now - (bufferDurationSeconds * 1000L)
            while (deque.isNotEmpty() && deque.first.timestampMs < cutoff) {
                deque.removeFirst()
            }
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

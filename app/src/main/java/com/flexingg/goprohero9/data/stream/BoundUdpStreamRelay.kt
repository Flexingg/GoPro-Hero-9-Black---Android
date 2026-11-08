package com.flexingg.goprohero9.data.stream

import android.net.Network
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.BindException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

data class StreamRelayStats(
    val isRunning: Boolean = false,
    val isBoundToNetwork: Boolean = false,
    val listenPort: Int = 8554,
    val forwardPort: Int = 8555,
    val packetsReceived: Long = 0L,
    val bytesReceived: Long = 0L,
    val bitrateKbps: Double = 0.0,
    val lastPacketTimestamp: Long = 0L,
    val lastPacketSizeBytes: Int = 0,
    val hasValidTsSyncByte: Boolean = false,
    val lastError: String = "",
    val statusText: String = "Idle"
)

/**
 * Dedicated Network-Bound UDP Stream Receiver & Loopback Relay.
 *
 * Prevents EADDRINUSE through synchronized stop/start lifecycle and retry backoff.
 * Binds explicitly to the GoPro Wi-Fi interface via `network.bindSocket(socket)`.
 */
class BoundUdpStreamRelay(
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "BoundUdpStreamRelay"
        const val DEFAULT_LISTEN_PORT = 8554
        const val DEFAULT_FORWARD_PORT = 8555
        private const val BUFFER_SIZE = 65535
        private const val MPEG_TS_SYNC_BYTE: Byte = 0x47
    }

    val dataPipe = LiveStreamDataPipe()

    private val _stats = MutableStateFlow(StreamRelayStats())
    val stats: StateFlow<StreamRelayStats> = _stats.asStateFlow()

    private val lifecycleMutex = Mutex()
    private var ingestSocket: DatagramSocket? = null
    private var forwardSocket: DatagramSocket? = null
    private var relayJob: Job? = null
    private var heartbeatJob: Job? = null

    var onPacketReceived: ((data: ByteArray, length: Int) -> Unit)? = null

    fun start(
        network: Network?,
        cameraIp: String = "10.5.5.9",
        listenPort: Int = DEFAULT_LISTEN_PORT,
        forwardPort: Int = DEFAULT_FORWARD_PORT
    ) {
        val targetIp = if (cameraIp.startsWith("10.5.5.")) cameraIp else "10.5.5.9"

        scope.launch(Dispatchers.IO) {
            lifecycleMutex.withLock {
                stopInternal()
                dataPipe.open()

                _stats.value = StreamRelayStats(
                    isRunning = true,
                    listenPort = listenPort,
                    forwardPort = forwardPort,
                    statusText = "Binding socket to GoPro Wi-Fi on port $listenPort..."
                )

                relayJob = scope.launch(Dispatchers.IO) {
                    var totalPackets = 0L
                    var totalBytes = 0L
                    var lastCalculatedTime = System.currentTimeMillis()
                    var bytesSinceLastCalc = 0L

                    try {
                        // 1. Create unbound DatagramSocket with SO_REUSEADDR
                        val sock = DatagramSocket(null)
                        sock.reuseAddress = true

                        var boundToNet = false
                        if (network != null) {
                            try {
                                network.bindSocket(sock)
                                boundToNet = true
                                Log.d(TAG, "Bound DatagramSocket to GoPro Wi-Fi network: $network")
                            } catch (e: Exception) {
                                Log.w(TAG, "network.bindSocket warning: ${e.message}")
                            }
                        }

                        // 2. Resilient port binding with retry backoff to eliminate EADDRINUSE
                        var bound = false
                        var attempts = 0
                        var effectivePort = listenPort

                        while (!bound && attempts < 5) {
                            attempts++
                            try {
                                sock.bind(InetSocketAddress("0.0.0.0", effectivePort))
                                bound = true
                                Log.d(TAG, "Successfully bound UDP ingest socket on port $effectivePort")
                            } catch (e: BindException) {
                                if (attempts >= 5) {
                                    // If port 8554 is still held by OS, try fallback port 8556
                                    try {
                                        effectivePort = 8556
                                        sock.bind(InetSocketAddress("0.0.0.0", effectivePort))
                                        bound = true
                                        Log.w(TAG, "Fell back to alternative port $effectivePort")
                                    } catch (ex: Exception) {
                                        throw e
                                    }
                                } else {
                                    Log.w(TAG, "Port $effectivePort busy (attempt $attempts). Waiting 200ms...")
                                    delay(200)
                                }
                            }
                        }

                        sock.soTimeout = 1000
                        ingestSocket = sock

                        // 3. Create loopback forwarder targeting localhost
                        val fwd = DatagramSocket()
                        forwardSocket = fwd
                        val loopbackDest = InetSocketAddress(InetAddress.getByName("127.0.0.1"), forwardPort)

                        _stats.value = _stats.value.copy(
                            isRunning = true,
                            isBoundToNetwork = boundToNet,
                            listenPort = effectivePort,
                            statusText = "Listening on port $effectivePort (Relaying to 127.0.0.1:$forwardPort)..."
                        )

                        // 4. Start heartbeat loop directly through this bound socket
                        startHeartbeat(sock, targetIp, effectivePort)

                        val buffer = ByteArray(BUFFER_SIZE)
                        val packet = DatagramPacket(buffer, buffer.size)

                        Log.d(TAG, "UDP Ingest loop running on port $effectivePort...")

                        while (isActive && !sock.isClosed) {
                            try {
                                sock.receive(packet)

                                val length = packet.length
                                if (length > 0) {
                                    totalPackets++
                                    totalBytes += length
                                    bytesSinceLastCalc += length

                                    val firstByte = buffer[0]
                                    val isTs = firstByte == MPEG_TS_SYNC_BYTE

                                    val now = System.currentTimeMillis()
                                    val timeDiff = now - lastCalculatedTime
                                    var bitrate = _stats.value.bitrateKbps
                                    if (timeDiff >= 1000) {
                                        bitrate = (bytesSinceLastCalc * 8.0) / (timeDiff)
                                        bytesSinceLastCalc = 0L
                                        lastCalculatedTime = now
                                    }

                                    _stats.value = _stats.value.copy(
                                        packetsReceived = totalPackets,
                                        bytesReceived = totalBytes,
                                        bitrateKbps = bitrate,
                                        lastPacketTimestamp = now,
                                        lastPacketSizeBytes = length,
                                        hasValidTsSyncByte = isTs,
                                        statusText = "Streaming: $totalPackets pkts (${(bitrate / 1000.0).let { "%.2f".format(it) }} Mbps)"
                                    )

                                    // 1. In-memory handoff directly to Media3 ExoPlayer (immune to Android network routing issues)
                                    dataPipe.write(buffer, length)

                                    // 2. Loopback forwarder fallback
                                    try {
                                        val fwdPacket = DatagramPacket(buffer, 0, length, loopbackDest)
                                        fwd.send(fwdPacket)
                                    } catch (_: Exception) {}

                                    // 3. Pass to circular retrospective clip buffer
                                    onPacketReceived?.invoke(buffer.copyOf(length), length)
                                }
                            } catch (e: SocketTimeoutException) {
                                // Expected timeout, loop to check isActive
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "UDP relay error: ${e.message}", e)
                        _stats.value = _stats.value.copy(
                            isRunning = false,
                            lastError = "${e.javaClass.simpleName}: ${e.message}",
                            statusText = "Relay error: ${e.message}"
                        )
                    } finally {
                        cleanupSockets()
                    }
                }
            }
        }
    }

    private fun startHeartbeat(sock: DatagramSocket, cameraIp: String, targetPort: Int) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch(Dispatchers.IO) {
            val targetAddr = InetSocketAddress(InetAddress.getByName(cameraIp), targetPort)
            val hero9Payload = "_GPHD_:1:0:2:0.000000\n".toByteArray(Charsets.UTF_8)
            val legacyPayload = "_GPHD_:0:0:2:0.000000\n".toByteArray(Charsets.UTF_8)

            while (isActive && !sock.isClosed) {
                try {
                    sock.send(DatagramPacket(hero9Payload, hero9Payload.size, targetAddr))
                    sock.send(DatagramPacket(legacyPayload, legacyPayload.size, targetAddr))
                } catch (e: Exception) {
                    Log.w(TAG, "Heartbeat ping note: ${e.message}")
                }
                delay(1500)
            }
        }
    }

    fun sendImmediateHeartbeat(cameraIp: String = "10.5.5.9", targetPort: Int = DEFAULT_LISTEN_PORT) {
        val targetIp = if (cameraIp.startsWith("10.5.5.")) cameraIp else "10.5.5.9"
        scope.launch(Dispatchers.IO) {
            val sock = ingestSocket
            if (sock != null && !sock.isClosed) {
                try {
                    val targetAddr = InetSocketAddress(InetAddress.getByName(targetIp), targetPort)
                    val payload = "_GPHD_:1:0:2:0.000000\n".toByteArray(Charsets.UTF_8)
                    sock.send(DatagramPacket(payload, payload.size, targetAddr))
                    Log.d(TAG, "Sent immediate heartbeat ping to $targetIp:$targetPort")
                } catch (e: Exception) {
                    Log.w(TAG, "Error sending immediate heartbeat: ${e.message}")
                }
            }
        }
    }

    fun stop() {
        scope.launch(Dispatchers.IO) {
            lifecycleMutex.withLock {
                stopInternal()
            }
        }
    }

    private fun stopInternal() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        relayJob?.cancel()
        relayJob = null
        dataPipe.close()
        cleanupSockets()
        _stats.value = _stats.value.copy(
            isRunning = false,
            statusText = "Stopped"
        )
    }

    private fun cleanupSockets() {
        try {
            ingestSocket?.close()
        } catch (_: Exception) {}
        ingestSocket = null

        try {
            forwardSocket?.close()
        } catch (_: Exception) {}
        forwardSocket = null
    }
}

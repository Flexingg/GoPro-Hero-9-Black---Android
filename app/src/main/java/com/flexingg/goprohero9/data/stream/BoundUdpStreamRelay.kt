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
 * Why this is required on Android 10+:
 * 1. Android devices with active mobile data (5G/LTE) drop incoming UDP packets from non-default
 *    Wi-Fi networks unless the socket is explicitly bound via `network.bindSocket(socket)`.
 * 2. Media3 ExoPlayer's `UdpDataSource` has no knowledge of Android `Network` objects and creates
 *    unbound sockets that get routed over cellular or dropped.
 * 3. GoPro AP firewall / NAT requires bidirectional traffic: sending heartbeat packets from the
 *    SAME bound socket that listens on 8554 establishes router NAT pinholes.
 * 4. This relay receives packets on the bound Wi-Fi interface and re-transmits them to localhost
 *    (`127.0.0.1:8555`), where ExoPlayer consumes them with zero routing ambiguity or cellular interference.
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

    private val _stats = MutableStateFlow(StreamRelayStats())
    val stats: StateFlow<StreamRelayStats> = _stats.asStateFlow()

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
        stop()

        _stats.value = StreamRelayStats(
            isRunning = true,
            listenPort = listenPort,
            forwardPort = forwardPort,
            statusText = "Binding socket to Wi-Fi on port $listenPort..."
        )

        relayJob = scope.launch(Dispatchers.IO) {
            var totalPackets = 0L
            var totalBytes = 0L
            var lastCalculatedTime = System.currentTimeMillis()
            var bytesSinceLastCalc = 0L

            try {
                // 1. Create ingest socket and explicitly bind to Android Wi-Fi Network
                val sock = DatagramSocket(null)
                sock.reuseAddress = true

                var boundToNet = false
                if (network != null) {
                    try {
                        network.bindSocket(sock)
                        boundToNet = true
                        Log.d(TAG, "Successfully bound DatagramSocket to Wi-Fi network: $network")
                    } catch (e: Exception) {
                        Log.w(TAG, "network.bindSocket warning: ${e.message}")
                    }
                } else {
                    Log.w(TAG, "No Wi-Fi Network provided to bindSocket; relying on default routing")
                }

                sock.bind(InetSocketAddress("0.0.0.0", listenPort))
                sock.soTimeout = 1000 // 1-second timeout to allow cooperative coroutine cancellation
                ingestSocket = sock

                // 2. Create forwarder socket targeting localhost loopback
                val fwd = DatagramSocket()
                forwardSocket = fwd
                val loopbackDest = InetSocketAddress(InetAddress.getByName("127.0.0.1"), forwardPort)

                _stats.value = _stats.value.copy(
                    isRunning = true,
                    isBoundToNetwork = boundToNet,
                    statusText = "Listening on port $listenPort (Relaying to 127.0.0.1:$forwardPort)..."
                )

                // 3. Launch heartbeat job using this exact bound socket
                startHeartbeat(sock, cameraIp, listenPort)

                val buffer = ByteArray(BUFFER_SIZE)
                val packet = DatagramPacket(buffer, buffer.size)

                Log.d(TAG, "UDP Ingest loop running on port $listenPort...")

                while (isActive) {
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
                                bitrate = (bytesSinceLastCalc * 8.0) / (timeDiff) // in kbps
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

                            // Forward to ExoPlayer on localhost loopback
                            try {
                                val fwdPacket = DatagramPacket(buffer, 0, length, loopbackDest)
                                fwd.send(fwdPacket)
                            } catch (e: Exception) {
                                Log.w(TAG, "Loopback forward warning: ${e.message}")
                            }

                            // Pass to circular clip buffer
                            onPacketReceived?.invoke(buffer.copyOf(length), length)
                        }
                    } catch (e: SocketTimeoutException) {
                        // Normal timeout every 1s when waiting for packets, loop again
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in UDP relay loop: ${e.message}", e)
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

    /**
     * Sends periodic keepalive pings directly through the bound ingest socket to 10.5.5.9:8554.
     * HERO9 uses `_GPHD_:1:0:2:0.000000\n`.
     */
    private fun startHeartbeat(sock: DatagramSocket, cameraIp: String, targetPort: Int) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch(Dispatchers.IO) {
            val targetAddr = InetSocketAddress(InetAddress.getByName(cameraIp), targetPort)
            val hero9Payload = "_GPHD_:1:0:2:0.000000\n".toByteArray(Charsets.UTF_8)
            val legacyPayload = "_GPHD_:0:0:2:0.000000\n".toByteArray(Charsets.UTF_8)

            while (isActive && !sock.isClosed) {
                try {
                    // Send Hero9 format
                    val p1 = DatagramPacket(hero9Payload, hero9Payload.size, targetAddr)
                    sock.send(p1)

                    // Also send legacy format for maximum compatibility across firmwares
                    val p2 = DatagramPacket(legacyPayload, legacyPayload.size, targetAddr)
                    sock.send(p2)
                } catch (e: Exception) {
                    Log.w(TAG, "Error sending UDP heartbeat ping: ${e.message}")
                }
                delay(1500)
            }
        }
    }

    fun sendImmediateHeartbeat(cameraIp: String = "10.5.5.9", targetPort: Int = DEFAULT_LISTEN_PORT) {
        scope.launch(Dispatchers.IO) {
            val sock = ingestSocket
            if (sock != null && !sock.isClosed) {
                try {
                    val targetAddr = InetSocketAddress(InetAddress.getByName(cameraIp), targetPort)
                    val payload = "_GPHD_:1:0:2:0.000000\n".toByteArray(Charsets.UTF_8)
                    sock.send(DatagramPacket(payload, payload.size, targetAddr))
                    Log.d(TAG, "Sent immediate heartbeat ping to $cameraIp:$targetPort")
                } catch (e: Exception) {
                    Log.w(TAG, "Error sending immediate heartbeat: ${e.message}")
                }
            }
        }
    }

    fun stop() {
        relayJob?.cancel()
        relayJob = null
        heartbeatJob?.cancel()
        heartbeatJob = null
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

package com.flexingg.goprohero9.viewmodel

import android.app.Application
import android.bluetooth.BluetoothManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Network
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.flexingg.goprohero9.buffer.CircularClipBuffer
import com.flexingg.goprohero9.data.api.GoProHttpClient
import com.flexingg.goprohero9.data.ble.BleConnectionState
import com.flexingg.goprohero9.data.ble.Hero9BleManager
import com.flexingg.goprohero9.data.stream.BoundUdpStreamRelay
import com.flexingg.goprohero9.data.wifi.GoProWifiManager
import com.flexingg.goprohero9.data.wifi.WifiConnectionState
import com.flexingg.goprohero9.player.PlayerState
import com.flexingg.goprohero9.player.StreamPlayerManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class SavedClip(
    val title: String,
    val localPath: String,
    val isFullResMaster: Boolean,
    val fileSizeFormatted: String,
    val timestamp: Long
)

data class DiagnosticsState(
    val isRelayRunning: Boolean = false,
    val isRelayBound: Boolean = false,
    val packetsReceived: Long = 0L,
    val bytesReceived: Long = 0L,
    val bitrateKbps: Double = 0.0,
    val hasTsSyncByte: Boolean = false,
    val lastPacketSizeBytes: Int = 0,
    val relayStatus: String = "Idle",
    val lastHttpLog: String = "None",
    val lastPlayerError: String = "",
    val gatewayIp: String = "10.5.5.9",
    val boundNetworkInfo: String = "None",
    val isDirectUdpMode: Boolean = false,
    val isOnHomeWifi: Boolean = false,
    val pipeBytesWritten: Long = 0L,
    val pipeBytesRead: Long = 0L,
    val playerPlaybackState: String = "IDLE",
    val detectedTracks: String = "None"
)

data class UiState(
    val cameraName: String = "GoPro HERO9 Black",
    val batteryPercent: Int = 92,
    val isBleConnected: Boolean = false,
    val isWifiConnected: Boolean = false,
    val wifiApSsid: String = "",
    val wifiApPassword: String = "",
    val isStreaming: Boolean = false,
    val isRecording: Boolean = false,
    val isClipping: Boolean = false,
    val clipDownloadProgress: Float = 0f,
    val selectedClipDuration: Int = 30, // seconds
    val streamFps: Int = 60,
    val streamResolution: String = "",
    val isFirstFrameRendered: Boolean = false,
    val statusMessage: String = "Ready to connect",
    val savedClips: List<SavedClip> = emptyList(),
    val diagnostics: DiagnosticsState = DiagnosticsState()
)

class GoProViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        private const val TAG = "GoProViewModel"
    }

    private val context = application.applicationContext
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager

    val bleManager = Hero9BleManager(context, bluetoothManager?.adapter, viewModelScope)
    val wifiManager = GoProWifiManager(context, viewModelScope)
    val httpClient = GoProHttpClient(viewModelScope)
    val playerManager = StreamPlayerManager(context)
    val streamRelay = BoundUdpStreamRelay(viewModelScope)
    val clipBuffer = CircularClipBuffer(context, viewModelScope)

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var isStartingStream = false

    init {
        // Wire packet feed directly from bound relay to circular retrospective buffer
        streamRelay.onPacketReceived = { data, length ->
            clipBuffer.ingestPacket(data, length)
        }

        wifiManager.onWifiNetworkBound = { network ->
            httpClient.updateNetwork(network)
            updateBoundNetworkInfo(network)
        }

        observeBle()
        observeWifi()
        observePlayer()
        observeStreamRelay()
        observeHttpClient()
        loadLocalClips()
    }

    private fun updateBoundNetworkInfo(network: Network?) {
        val netInfo = network?.toString() ?: "None"
        _uiState.value = _uiState.value.copy(
            diagnostics = _uiState.value.diagnostics.copy(boundNetworkInfo = netInfo)
        )
    }

    private fun observeStreamRelay() {
        viewModelScope.launch {
            streamRelay.stats.collect { stats ->
                _uiState.value = _uiState.value.copy(
                    diagnostics = _uiState.value.diagnostics.copy(
                        isRelayRunning = stats.isRunning,
                        isRelayBound = stats.isBoundToNetwork,
                        packetsReceived = stats.packetsReceived,
                        bytesReceived = stats.bytesReceived,
                        bitrateKbps = stats.bitrateKbps,
                        hasTsSyncByte = stats.hasValidTsSyncByte,
                        lastPacketSizeBytes = stats.lastPacketSizeBytes,
                        relayStatus = stats.statusText
                    )
                )

                if (stats.packetsReceived > 0 && !_uiState.value.isFirstFrameRendered) {
                    val rate = (stats.bitrateKbps / 1000.0).let { "%.1f".format(it) }
                    _uiState.value = _uiState.value.copy(
                        statusMessage = "Receiving UDP: ${stats.packetsReceived} pkts ($rate Mbps). Decoding TS..."
                    )
                }
            }
        }

        viewModelScope.launch {
            streamRelay.dataPipe.totalBytesWritten.collect { written ->
                _uiState.value = _uiState.value.copy(
                    diagnostics = _uiState.value.diagnostics.copy(pipeBytesWritten = written)
                )
            }
        }

        viewModelScope.launch {
            streamRelay.dataPipe.totalBytesRead.collect { readBytes ->
                _uiState.value = _uiState.value.copy(
                    diagnostics = _uiState.value.diagnostics.copy(pipeBytesRead = readBytes)
                )
            }
        }
    }

    private fun observeHttpClient() {
        viewModelScope.launch {
            httpClient.lastHttpLog.collect { log ->
                _uiState.value = _uiState.value.copy(
                    diagnostics = _uiState.value.diagnostics.copy(lastHttpLog = log)
                )
            }
        }
    }

    private fun observeBle() {
        viewModelScope.launch {
            bleManager.connectionState.collect { bleState ->
                when (bleState) {
                    is BleConnectionState.Disconnected -> {
                        _uiState.value = _uiState.value.copy(
                            isBleConnected = false,
                            statusMessage = "BLE Disconnected"
                        )
                    }
                    is BleConnectionState.Scanning -> {
                        _uiState.value = _uiState.value.copy(
                            statusMessage = "Scanning for GoPro HERO9..."
                        )
                    }
                    is BleConnectionState.Connecting -> {
                        _uiState.value = _uiState.value.copy(
                            statusMessage = "Connecting to ${bleState.deviceName}..."
                        )
                    }
                    is BleConnectionState.Connected -> {
                        _uiState.value = _uiState.value.copy(
                            cameraName = bleState.deviceName,
                            isBleConnected = true,
                            statusMessage = "Connected to ${bleState.deviceName}. Discovering services..."
                        )
                    }
                    is BleConnectionState.Pairing -> {
                        _uiState.value = _uiState.value.copy(
                            cameraName = bleState.deviceName,
                            isBleConnected = true,
                            statusMessage = "Pairing with ${bleState.deviceName} & reading Wi-Fi AP..."
                        )
                    }
                    is BleConnectionState.WifiApInfo -> {
                        _uiState.value = _uiState.value.copy(
                            wifiApSsid = bleState.ssid,
                            wifiApPassword = bleState.password,
                            statusMessage = "Wi-Fi AP: ${bleState.ssid} (Pass: ${bleState.password}). Connecting..."
                        )
                        wifiManager.connectToAccessPoint(bleState.ssid, bleState.password)
                    }
                    is BleConnectionState.Error -> {
                        _uiState.value = _uiState.value.copy(
                            statusMessage = "BLE: ${bleState.message}"
                        )
                    }
                }
            }
        }
    }

    private fun observeWifi() {
        viewModelScope.launch {
            wifiManager.wifiState.collect { wifiState ->
                when (wifiState) {
                    is WifiConnectionState.Disconnected -> {
                        _uiState.value = _uiState.value.copy(
                            isWifiConnected = false,
                            statusMessage = if (_uiState.value.wifiApSsid.isNotEmpty())
                                "Wi-Fi Disconnected. Waiting for ${_uiState.value.wifiApSsid}..."
                            else "Wi-Fi Disconnected"
                        )
                    }
                    is WifiConnectionState.Connecting -> {
                        _uiState.value = _uiState.value.copy(
                            statusMessage = "Connecting to GoPro Wi-Fi: ${wifiState.ssid}..."
                        )
                    }
                    is WifiConnectionState.OnHomeWifi -> {
                        val expected = wifiState.expectedGoProSsid.ifEmpty { _uiState.value.wifiApSsid }
                        _uiState.value = _uiState.value.copy(
                            isWifiConnected = false,
                            isStreaming = false,
                            diagnostics = _uiState.value.diagnostics.copy(
                                gatewayIp = wifiState.homeGatewayIp,
                                isOnHomeWifi = true
                            ),
                            statusMessage = "⚠️ Phone is on Home Wi-Fi (${wifiState.homeGatewayIp}). Please switch to GoPro Wi-Fi (${expected.ifEmpty { "GP..." }})."
                        )
                    }
                    is WifiConnectionState.Connected -> {
                        _uiState.value = _uiState.value.copy(
                            isWifiConnected = true,
                            diagnostics = _uiState.value.diagnostics.copy(
                                gatewayIp = wifiState.gatewayIp,
                                isOnHomeWifi = false
                            ),
                            statusMessage = "GoPro Wi-Fi Connected (${wifiState.gatewayIp}). Starting Live Stream..."
                        )
                        startLiveStream()
                    }
                    is WifiConnectionState.Failed -> {
                        _uiState.value = _uiState.value.copy(
                            statusMessage = "Wi-Fi Notice: ${wifiState.error}"
                        )
                    }
                }
            }
        }
    }

    private fun observePlayer() {
        viewModelScope.launch {
            playerManager.playerState.collect { pState ->
                when (pState) {
                    is PlayerState.Playing -> {
                        val res = playerManager.videoResolution.value
                        val msg = if (res.isNotEmpty()) "Live Stream active ($res 60fps)" else "Live Stream active (720p60 UDP)"
                        _uiState.value = _uiState.value.copy(
                            isStreaming = true,
                            statusMessage = msg
                        )
                    }
                    is PlayerState.Buffering -> {
                        _uiState.value = _uiState.value.copy(
                            statusMessage = "Buffering video stream..."
                        )
                    }
                    is PlayerState.Idle -> {
                        _uiState.value = _uiState.value.copy(
                            isStreaming = false,
                            isFirstFrameRendered = false
                        )
                    }
                    is PlayerState.Error -> {
                        _uiState.value = _uiState.value.copy(
                            isStreaming = false,
                            isFirstFrameRendered = false,
                            statusMessage = "Player Error: ${pState.message}",
                            diagnostics = _uiState.value.diagnostics.copy(lastPlayerError = pState.message)
                        )
                    }
                }
            }
        }

        viewModelScope.launch {
            playerManager.isFirstFrameRendered.collect { rendered ->
                _uiState.value = _uiState.value.copy(isFirstFrameRendered = rendered)
                if (rendered) {
                    val res = _uiState.value.streamResolution
                    _uiState.value = _uiState.value.copy(
                        statusMessage = if (res.isNotEmpty()) "Viewfinder active ($res 60fps)" else "Viewfinder active"
                    )
                }
            }
        }

        viewModelScope.launch {
            playerManager.videoResolution.collect { res ->
                if (res.isNotEmpty()) {
                    _uiState.value = _uiState.value.copy(
                        streamResolution = res,
                        statusMessage = "Viewfinder active ($res 60fps)"
                    )
                }
            }
        }

        viewModelScope.launch {
            playerManager.lastPlayerError.collect { err ->
                _uiState.value = _uiState.value.copy(
                    diagnostics = _uiState.value.diagnostics.copy(lastPlayerError = err)
                )
            }
        }

        viewModelScope.launch {
            playerManager.playbackStateLabel.collect { label ->
                _uiState.value = _uiState.value.copy(
                    diagnostics = _uiState.value.diagnostics.copy(playerPlaybackState = label)
                )
            }
        }

        viewModelScope.launch {
            playerManager.detectedTracksInfo.collect { tracks ->
                _uiState.value = _uiState.value.copy(
                    diagnostics = _uiState.value.diagnostics.copy(detectedTracks = tracks)
                )
            }
        }
    }

    fun startDiscovery() {
        bleManager.startScan()
    }

    fun retryWifiConnect() {
        val ssid = _uiState.value.wifiApSsid
        val pass = _uiState.value.wifiApPassword
        if (ssid.isNotEmpty() && pass.isNotEmpty()) {
            wifiManager.connectToAccessPoint(ssid, pass)
        }
    }

    fun setClipDuration(seconds: Int) {
        _uiState.value = _uiState.value.copy(selectedClipDuration = seconds)
        clipBuffer.setBufferDuration(seconds)
    }

    fun onResume() {
        val wifiNet = wifiManager.bindToActiveWifi()
        if (wifiNet != null) {
            httpClient.updateNetwork(wifiNet)
            updateBoundNetworkInfo(wifiNet)
            wifiManager.checkCameraReachability()
        }
    }

    /**
     * Starts live preview stream:
     * 1. Confirms connection to GoPro Wi-Fi (10.5.5.x); warns if still on home Wi-Fi.
     * 2. Binds process to GoPro Wi-Fi interface.
     * 3. Starts BoundUdpStreamRelay with explicit network socket binding.
     * 4. Starts in-memory CircularClipBuffer capture.
     * 5. Commands GoPro camera over HTTP to wake encoder and start streaming.
     * 6. Launches ExoPlayer on 127.0.0.1:8555 (loopback).
     */
    fun startLiveStream(useDirectUdp: Boolean = false) {
        if (isStartingStream) {
            Log.d(TAG, "Stream start already in progress; skipping duplicate trigger")
            return
        }
        isStartingStream = true

        viewModelScope.launch {
            try {
                // Verify we are actually on GoPro Wi-Fi (10.5.5.x)
                val isGoPro = wifiManager.isConnectedToGoProNetwork()
                if (!isGoPro) {
                    val homeGw = wifiManager.getHomeWifiGateway()
                    val targetSsid = _uiState.value.wifiApSsid
                    _uiState.value = _uiState.value.copy(
                        isStreaming = false,
                        isWifiConnected = false,
                        diagnostics = _uiState.value.diagnostics.copy(
                            gatewayIp = if (homeGw.isNotEmpty()) homeGw else "10.5.5.9",
                            isOnHomeWifi = true,
                            relayStatus = "Waiting for connection to GoPro Wi-Fi ($targetSsid)"
                        ),
                        statusMessage = "⚠️ Phone is on Home Wi-Fi ($homeGw). Please connect to GoPro Wi-Fi (${targetSsid.ifEmpty { "GP..." }}) in Settings."
                    )
                    return@launch
                }

                _uiState.value = _uiState.value.copy(
                    statusMessage = "Binding to GoPro Wi-Fi network interface...",
                    diagnostics = _uiState.value.diagnostics.copy(isDirectUdpMode = useDirectUdp, isOnHomeWifi = false)
                )

                val wifiNet = wifiManager.bindToActiveWifi()
                httpClient.updateNetwork(wifiNet)
                updateBoundNetworkInfo(wifiNet)

                val gw = wifiManager.getGatewayIp() // Strictly 10.5.5.9
                httpClient.baseIp = gw
                _uiState.value = _uiState.value.copy(
                    isWifiConnected = true,
                    diagnostics = _uiState.value.diagnostics.copy(gatewayIp = gw)
                )

                clipBuffer.startCapture()

                if (!useDirectUdp) {
                    streamRelay.start(
                        network = wifiNet,
                        cameraIp = gw,
                        listenPort = 8554,
                        forwardPort = 8555
                    )
                } else {
                    streamRelay.stop()
                }

                _uiState.value = _uiState.value.copy(statusMessage = "Sending Stream Start command to GoPro ($gw)...")
                val (started, msg) = httpClient.startStream()

                if (useDirectUdp) {
                    playerManager.startPlaying(StreamPlayerManager.DIRECT_STREAM_URI)
                } else {
                    playerManager.startPlaying(streamRelay.dataPipe)
                }

                _uiState.value = _uiState.value.copy(
                    isStreaming = true,
                    isWifiConnected = true,
                    statusMessage = if (started) "Stream requested: $msg. Ingesting UDP..." else "Notice: $msg"
                )
            } finally {
                isStartingStream = false
            }
        }
    }

    fun stopLiveStream() {
        viewModelScope.launch {
            httpClient.stopStream()
            streamRelay.stop()
            clipBuffer.stopCapture()
            playerManager.stop()
            _uiState.value = _uiState.value.copy(
                isStreaming = false,
                statusMessage = "Live Stream paused"
            )
        }
    }

    fun sendImmediateHeartbeat() {
        val gw = _uiState.value.diagnostics.gatewayIp
        streamRelay.sendImmediateHeartbeat(gw, 8554)
    }

    fun toggleUdpMode() {
        val currentDirect = _uiState.value.diagnostics.isDirectUdpMode
        startLiveStream(useDirectUdp = !currentDirect)
    }

    /**
     * Retrospectively clips the last X seconds (15s, 30s, 60s) in full uncompressed resolution.
     */
    fun triggerRetrospectiveClip() {
        val duration = _uiState.value.selectedClipDuration
        _uiState.value = _uiState.value.copy(
            isClipping = true,
            statusMessage = "Flushing HindSight 4K buffer for last ${duration}s..."
        )

        viewModelScope.launch(Dispatchers.IO) {
            val moviesDir = getClipsDirectory()

            // 1. Concurrently dump instant local MPEG-TS circular buffer & remux to seekable MP4
            val nowTs = System.currentTimeMillis()
            val instantTsFile = File(moviesDir, "GoPro_Instant_${nowTs}.ts")
            val instantMp4File = File(moviesDir, "GoPro_Clip_${nowTs}.mp4")
            val dumped = clipBuffer.dumpBufferToFile(instantTsFile)
            var finalInstantFile = instantTsFile
            if (dumped && instantTsFile.exists() && instantTsFile.length() > 0) {
                val remuxed = com.flexingg.goprohero9.buffer.TsToMp4Remuxer.remux(instantTsFile, instantMp4File)
                if (remuxed && instantMp4File.exists() && instantMp4File.length() > 0) {
                    instantTsFile.delete()
                    finalInstantFile = instantMp4File
                }
                val instantClip = SavedClip(
                    title = "Stream Clip (${duration}s)",
                    localPath = finalInstantFile.absolutePath,
                    isFullResMaster = false,
                    fileSizeFormatted = formatFileSize(finalInstantFile.length()),
                    timestamp = System.currentTimeMillis()
                )
                addClip(instantClip)
                _uiState.value = _uiState.value.copy(
                    statusMessage = "Instant clip saved (${instantClip.fileSizeFormatted})! Checking camera for 4K master..."
                )
            }

            // 2. Camera HindSight full 4K/5K retrospective master trigger
            val hindSightOk = httpClient.triggerHindSightClip(duration)
            if (hindSightOk) {
                _uiState.value = _uiState.value.copy(
                    statusMessage = "HindSight committed! Syncing 4K master..."
                )
                delay(1000)

                val latestMedia = httpClient.getLatestMediaFile()
                if (latestMedia != null) {
                    val masterFile = File(moviesDir, "GoPro_Master_${latestMedia.filename}")
                    val downloadOk = httpClient.downloadMediaFile(latestMedia, masterFile) { progress ->
                        _uiState.value = _uiState.value.copy(
                            clipDownloadProgress = progress,
                            statusMessage = "Downloading 4K Master: ${(progress * 100).toInt()}%"
                        )
                    }

                    if (downloadOk && masterFile.exists() && masterFile.length() > 0) {
                        val masterClip = SavedClip(
                            title = "4K Master (${latestMedia.filename})",
                            localPath = masterFile.absolutePath,
                            isFullResMaster = true,
                            fileSizeFormatted = formatFileSize(masterFile.length()),
                            timestamp = System.currentTimeMillis()
                        )
                        addClip(masterClip)
                        _uiState.value = _uiState.value.copy(
                            statusMessage = "Full-resolution 4K Master saved (${masterClip.fileSizeFormatted})!"
                        )
                    } else {
                        _uiState.value = _uiState.value.copy(
                            statusMessage = if (dumped) "Saved instant stream clip (4K download unavailable)" else "Clip failed"
                        )
                    }
                } else {
                    _uiState.value = _uiState.value.copy(
                        statusMessage = if (dumped) "Saved instant stream clip (no new SD file found)" else "Clip failed"
                    )
                }
            } else {
                _uiState.value = _uiState.value.copy(
                    statusMessage = if (dumped)
                        "Saved instant stream clip (${formatFileSize(instantTsFile.length())})"
                    else
                        "No stream packets buffered yet to clip"
                )
            }

            _uiState.value = _uiState.value.copy(
                isClipping = false,
                clipDownloadProgress = 0f
            )
        }
    }

    fun exportClipToDownloads(clip: SavedClip) {
        viewModelScope.launch(Dispatchers.IO) {
            val sourceFile = File(clip.localPath)
            if (!sourceFile.exists()) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Clip file not found on disk", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }

            try {
                val isMp4 = sourceFile.extension.equals("mp4", ignoreCase = true)
                val mimeType = if (isMp4) "video/mp4" else "video/mp2t"
                val fileName = sourceFile.name

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val resolver = context.contentResolver
                    val collection = if (isMp4) {
                        MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    } else {
                        MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    }
                    val relativeDir = if (isMp4) {
                        "${Environment.DIRECTORY_MOVIES}/GoProClips"
                    } else {
                        "${Environment.DIRECTORY_DOWNLOADS}/GoProClips"
                    }

                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                        put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                        put(MediaStore.MediaColumns.RELATIVE_PATH, relativeDir)
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }

                    val uri = resolver.insert(collection, values)
                        ?: throw IllegalStateException("MediaStore insert returned null")

                    resolver.openOutputStream(uri)?.use { out ->
                        sourceFile.inputStream().use { input ->
                            input.copyTo(out)
                        }
                    } ?: throw IllegalStateException("Failed to open output stream")

                    values.clear()
                    values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)

                    withContext(Dispatchers.Main) {
                        val destLabel = if (isMp4) "Movies/GoProClips" else "Downloads/GoProClips"
                        Toast.makeText(context, "Saved to $destLabel/$fileName", Toast.LENGTH_LONG).show()
                    }
                } else {
                    @Suppress("DEPRECATION")
                    val downloadsDir = File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                        "GoProClips"
                    )
                    if (!downloadsDir.exists()) downloadsDir.mkdirs()
                    val destFile = File(downloadsDir, fileName)
                    sourceFile.copyTo(destFile, overwrite = true)

                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Saved to Downloads/GoProClips/$fileName", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to export clip: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    fun shareClip(activityContext: Context, clip: SavedClip) {
        val file = File(clip.localPath)
        if (!file.exists()) {
            Toast.makeText(activityContext, "Clip file not found", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val uri = FileProvider.getUriForFile(
                activityContext,
                "${activityContext.packageName}.fileprovider",
                file
            )
            val mimeType = if (file.extension.equals("mp4", ignoreCase = true)) "video/mp4" else "video/mp2t"
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activityContext.startActivity(Intent.createChooser(intent, "Share GoPro Clip"))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to share clip: ${e.message}", e)
            Toast.makeText(activityContext, "Share failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    fun deleteClip(clip: SavedClip) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val file = File(clip.localPath)
                if (file.exists()) file.delete()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to delete clip file: ${e.message}")
            }
            _uiState.value = _uiState.value.copy(
                savedClips = _uiState.value.savedClips.filterNot { it.localPath == clip.localPath }
            )
        }
    }

    private fun formatFileSize(bytes: Long): String {
        if (bytes < 1024L * 1024L) {
            val kb = (bytes / 1024L).coerceAtLeast(1L)
            return "$kb KB"
        }
        val mb = bytes / (1024.0 * 1024.0)
        return "%.1f MB".format(mb)
    }

    private fun addClip(clip: SavedClip) {
        val updated = listOf(clip) + _uiState.value.savedClips.filterNot { it.localPath == clip.localPath }
        _uiState.value = _uiState.value.copy(savedClips = updated)
    }

    private fun getClipsDirectory(): File {
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "GoProClips")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun loadLocalClips() {
        viewModelScope.launch(Dispatchers.IO) {
            val dir = getClipsDirectory()
            val files = dir.listFiles()?.filter { it.extension in listOf("mp4", "ts") && it.length() > 0 } ?: emptyList()
            val clips = files.sortedByDescending { it.lastModified() }.map { file ->
                SavedClip(
                    title = file.name,
                    localPath = file.absolutePath,
                    isFullResMaster = file.name.contains("Master", ignoreCase = true),
                    fileSizeFormatted = formatFileSize(file.length()),
                    timestamp = file.lastModified()
                )
            }
            _uiState.value = _uiState.value.copy(savedClips = clips)
        }
    }

    override fun onCleared() {
        super.onCleared()
        httpClient.stopKeepAlive()
        streamRelay.stop()
        clipBuffer.stopCapture()
        playerManager.release()
        bleManager.disconnect()
        wifiManager.disconnect()
    }
}

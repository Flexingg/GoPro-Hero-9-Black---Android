package com.flexingg.goprohero9.viewmodel

import android.app.Application
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Environment
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.flexingg.goprohero9.buffer.CircularClipBuffer
import com.flexingg.goprohero9.data.api.GoProHttpClient
import com.flexingg.goprohero9.data.ble.BleConnectionState
import com.flexingg.goprohero9.data.ble.Hero9BleManager
import com.flexingg.goprohero9.data.wifi.GoProWifiManager
import com.flexingg.goprohero9.data.wifi.WifiConnectionState
import com.flexingg.goprohero9.player.PlayerState
import com.flexingg.goprohero9.player.StreamPlayerManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

data class SavedClip(
    val title: String,
    val localPath: String,
    val isFullResMaster: Boolean,
    val fileSizeFormatted: String,
    val timestamp: Long
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
    val statusMessage: String = "Ready to connect",
    val savedClips: List<SavedClip> = emptyList()
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
    val clipBuffer = CircularClipBuffer(context, viewModelScope)

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        wifiManager.onWifiNetworkBound = { network ->
            httpClient.updateNetwork(network)
        }
        observeBle()
        observeWifi()
        observePlayer()
        loadLocalClips()
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
                        // Request connection to GoPro's Wi-Fi network
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
                                "Wi-Fi Disconnected. Reconnecting to ${_uiState.value.wifiApSsid}..."
                            else "Wi-Fi Disconnected"
                        )
                    }
                    is WifiConnectionState.Connecting -> {
                        _uiState.value = _uiState.value.copy(
                            statusMessage = "Connecting to GoPro Wi-Fi: ${wifiState.ssid}..."
                        )
                    }
                    is WifiConnectionState.Connected -> {
                        _uiState.value = _uiState.value.copy(
                            isWifiConnected = true,
                            statusMessage = "Wi-Fi Connected (${wifiState.gatewayIp}). Starting Live Stream..."
                        )
                        // Auto-start stream once Wi-Fi socket is established
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
                        _uiState.value = _uiState.value.copy(
                            isStreaming = true,
                            statusMessage = "Live Stream active (720p60 UDP)"
                        )
                    }
                    is PlayerState.Buffering -> {
                        _uiState.value = _uiState.value.copy(
                            statusMessage = "Buffering video stream..."
                        )
                    }
                    is PlayerState.Idle -> {
                        _uiState.value = _uiState.value.copy(isStreaming = false)
                    }
                    is PlayerState.Error -> {
                        _uiState.value = _uiState.value.copy(
                            isStreaming = false,
                            statusMessage = "Player Error: ${pState.message}"
                        )
                    }
                }
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
    }

    fun onResume() {
        val wifiNet = wifiManager.bindToActiveWifi()
        if (wifiNet != null) {
            httpClient.updateNetwork(wifiNet)
            wifiManager.checkCameraReachability()
        }
    }

    fun startLiveStream() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(statusMessage = "Binding to GoPro Wi-Fi interface...")
            val wifiNet = wifiManager.bindToActiveWifi()
            httpClient.updateNetwork(wifiNet)
            val gw = wifiManager.getGatewayIp()
            httpClient.baseIp = gw

            _uiState.value = _uiState.value.copy(statusMessage = "Starting camera live preview stream at $gw...")
            val started = httpClient.startStream()
            if (started) {
                playerManager.startPlaying("udp://0.0.0.0:8554")
                _uiState.value = _uiState.value.copy(
                    isStreaming = true,
                    isWifiConnected = true,
                    statusMessage = "Live Stream active (720p60 UDP)"
                )
            } else {
                _uiState.value = _uiState.value.copy(
                    statusMessage = "Could not reach camera stream at $gw. Ensure Wi-Fi is connected."
                )
            }
        }
    }

    fun stopLiveStream() {
        viewModelScope.launch {
            httpClient.stopStream()
            playerManager.stop()
            _uiState.value = _uiState.value.copy(
                isStreaming = false,
                statusMessage = "Live Stream paused"
            )
        }
    }

    /**
     * Retrospectively clips the last X seconds (15s, 30s, 60s) in full uncompressed resolution:
     * 1. Triggers camera HindSight buffer flush to commit 4K master uncompressed clip.
     * 2. Queries camera media list and downloads 4K master file via Turbo Transfer.
     */
    fun triggerRetrospectiveClip() {
        val duration = _uiState.value.selectedClipDuration
        _uiState.value = _uiState.value.copy(
            isClipping = true,
            statusMessage = "Flushing HindSight 4K buffer for last ${duration}s..."
        )

        viewModelScope.launch(Dispatchers.IO) {
            val moviesDir = getClipsDirectory()

            // 1. Camera HindSight full 4K/5K retrospective trigger
            val hindSightOk = httpClient.triggerHindSightClip(duration)
            if (hindSightOk) {
                _uiState.value = _uiState.value.copy(
                    statusMessage = "HindSight committed to SD card! Syncing 4K master..."
                )

                // 2. Query latest recorded file from camera
                val latestMedia = httpClient.getLatestMediaFile()
                if (latestMedia != null) {
                    val masterFile = File(moviesDir, "GoPro_Master_${latestMedia.filename}")
                    val downloadOk = httpClient.downloadMediaFile(latestMedia, masterFile) { progress ->
                        _uiState.value = _uiState.value.copy(
                            clipDownloadProgress = progress,
                            statusMessage = "Downloading 4K Master: ${(progress * 100).toInt()}%"
                        )
                    }

                    if (downloadOk) {
                        val masterClip = SavedClip(
                            title = "4K Master (${latestMedia.filename})",
                            localPath = masterFile.absolutePath,
                            isFullResMaster = true,
                            fileSizeFormatted = "${masterFile.length() / (1024 * 1024)} MB",
                            timestamp = System.currentTimeMillis()
                        )
                        addClip(masterClip)
                        _uiState.value = _uiState.value.copy(
                            statusMessage = "Full-resolution 4K Master saved!"
                        )
                    }
                }
            } else {
                _uiState.value = _uiState.value.copy(
                    statusMessage = "Failed to trigger HindSight clip on camera"
                )
            }

            _uiState.value = _uiState.value.copy(
                isClipping = false,
                clipDownloadProgress = 0f
            )
        }
    }

    private fun addClip(clip: SavedClip) {
        val updated = listOf(clip) + _uiState.value.savedClips
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
            val files = dir.listFiles()?.filter { it.extension in listOf("mp4", "ts") } ?: emptyList()
            val clips = files.sortedByDescending { it.lastModified() }.map { file ->
                SavedClip(
                    title = file.name,
                    localPath = file.absolutePath,
                    isFullResMaster = file.name.contains("Master", ignoreCase = true),
                    fileSizeFormatted = "${file.length() / (1024 * 1024)} MB",
                    timestamp = file.lastModified()
                )
            }
            _uiState.value = _uiState.value.copy(savedClips = clips)
        }
    }

    override fun onCleared() {
        super.onCleared()
        httpClient.stopKeepAlive()
        playerManager.release()
        bleManager.disconnect()
        wifiManager.disconnect()
    }
}

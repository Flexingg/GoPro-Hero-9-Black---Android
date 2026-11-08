package com.flexingg.goprohero9.data.wifi

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
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
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

sealed class WifiConnectionState {
    object Disconnected : WifiConnectionState()
    data class Connecting(val ssid: String) : WifiConnectionState()
    data class Connected(val ssid: String, val gatewayIp: String) : WifiConnectionState()
    data class Failed(val error: String) : WifiConnectionState()
}

class GoProWifiManager(
    private val context: Context,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "GoProWifiManager"
        const val GOPRO_DEFAULT_IP = "10.5.5.9"
        const val GOPRO_PORT = 8080
    }

    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val _wifiState = MutableStateFlow<WifiConnectionState>(WifiConnectionState.Disconnected)
    val wifiState: StateFlow<WifiConnectionState> = _wifiState.asStateFlow()

    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var reachabilityPollingJob: Job? = null

    private val probeClient = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .build()

    fun connectToAccessPoint(ssid: String, wpa2Passphrase: String) {
        _wifiState.value = WifiConnectionState.Connecting(ssid)
        Log.d(TAG, "Requesting connection to GoPro Wi-Fi AP: SSID='$ssid', Pass='$wpa2Passphrase'")

        startReachabilityPolling(ssid)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val specifier = WifiNetworkSpecifier.Builder()
                .setSsid(ssid)
                .setWpa2Passphrase(wpa2Passphrase)
                .build()

            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(specifier)
                .build()

            releaseCallback()

            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    Log.d(TAG, "NetworkCallback: Successfully connected to GoPro Wi-Fi: $ssid")
                    connectivityManager.bindProcessToNetwork(network)
                    _wifiState.value = WifiConnectionState.Connected(ssid, GOPRO_DEFAULT_IP)
                }

                override fun onLost(network: Network) {
                    Log.w(TAG, "NetworkCallback: Lost connection to GoPro Wi-Fi network: $ssid")
                    connectivityManager.bindProcessToNetwork(null)
                    _wifiState.value = WifiConnectionState.Disconnected
                }

                override fun onUnavailable() {
                    Log.e(TAG, "NetworkCallback: GoPro Wi-Fi unavailable or user dismissed dialog: $ssid")
                    // Do not immediately fail if manual connection might still work
                }
            }

            try {
                connectivityManager.requestNetwork(request, networkCallback!!)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to request network with specifier: ${e.message}")
            }
        }
    }

    private fun startReachabilityPolling(ssid: String) {
        reachabilityPollingJob?.cancel()
        reachabilityPollingJob = scope.launch(Dispatchers.IO) {
            val probeUrl = "http://$GOPRO_DEFAULT_IP:$GOPRO_PORT/gopro/camera/state"
            var attempts = 0
            while (isActive && attempts < 40) {
                attempts++
                try {
                    val request = Request.Builder().url(probeUrl).get().build()
                    probeClient.newCall(request).execute().use { response ->
                        if (response.isSuccessful || response.code in 200..499) {
                            Log.d(TAG, "Camera HTTP API reachable at $probeUrl! Mark Wi-Fi connected.")
                            _wifiState.value = WifiConnectionState.Connected(ssid, GOPRO_DEFAULT_IP)
                            return@launch
                        }
                    }
                } catch (e: Exception) {
                    // Waiting for Wi-Fi association and DHCP
                }
                delay(1500)
            }
        }
    }

    fun disconnect() {
        reachabilityPollingJob?.cancel()
        reachabilityPollingJob = null
        releaseCallback()
        connectivityManager.bindProcessToNetwork(null)
        _wifiState.value = WifiConnectionState.Disconnected
    }

    private fun releaseCallback() {
        networkCallback?.let {
            try {
                connectivityManager.unregisterNetworkCallback(it)
            } catch (e: Exception) {
                Log.w(TAG, "Error unregistering callback: ${e.message}")
            }
            networkCallback = null
        }
    }
}

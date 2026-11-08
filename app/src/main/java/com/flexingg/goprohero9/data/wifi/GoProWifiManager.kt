package com.flexingg.goprohero9.data.wifi

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed class WifiConnectionState {
    object Disconnected : WifiConnectionState()
    data class Connecting(val ssid: String) : WifiConnectionState()
    data class Connected(val ssid: String, val gatewayIp: String) : WifiConnectionState()
    data class Failed(val error: String) : WifiConnectionState()
}

class GoProWifiManager(private val context: Context) {
    companion object {
        private const val TAG = "GoProWifiManager"
        const val GOPRO_DEFAULT_IP = "10.5.5.9"
        const val GOPRO_PORT = 8080
    }

    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val _wifiState = MutableStateFlow<WifiConnectionState>(WifiConnectionState.Disconnected)
    val wifiState: StateFlow<WifiConnectionState> = _wifiState.asStateFlow()

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    fun connectToAccessPoint(ssid: String, wpa2Passphrase: String) {
        _wifiState.value = WifiConnectionState.Connecting(ssid)
        Log.d(TAG, "Requesting connection to GoPro Wi-Fi AP: $ssid")

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
                    Log.d(TAG, "Successfully connected to GoPro Wi-Fi network: $ssid")
                    // Bind this app's process to the GoPro Wi-Fi network so all sockets (UDP/HTTP) route to 10.5.5.9
                    connectivityManager.bindProcessToNetwork(network)
                    _wifiState.value = WifiConnectionState.Connected(ssid, GOPRO_DEFAULT_IP)
                }

                override fun onLost(network: Network) {
                    Log.w(TAG, "Lost connection to GoPro Wi-Fi network: $ssid")
                    connectivityManager.bindProcessToNetwork(null)
                    _wifiState.value = WifiConnectionState.Disconnected
                }

                override fun onUnavailable() {
                    Log.e(TAG, "GoPro Wi-Fi network unavailable: $ssid")
                    _wifiState.value = WifiConnectionState.Failed("Wi-Fi network unavailable or rejected by user")
                }
            }

            connectivityManager.requestNetwork(request, networkCallback!!)
        } else {
            _wifiState.value = WifiConnectionState.Failed("Android 10+ required for WifiNetworkSpecifier")
        }
    }

    fun disconnect() {
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

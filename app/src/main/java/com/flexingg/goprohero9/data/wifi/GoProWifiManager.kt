package com.flexingg.goprohero9.data.wifi

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.text.format.Formatter
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
import kotlinx.coroutines.withContext
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
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    private val _wifiState = MutableStateFlow<WifiConnectionState>(WifiConnectionState.Disconnected)
    val wifiState: StateFlow<WifiConnectionState> = _wifiState.asStateFlow()

    private var specifierCallback: ConnectivityManager.NetworkCallback? = null
    private var reachabilityPollingJob: Job? = null
    private var targetSsid: String = ""
    private var activeWifiNetwork: Network? = null
    var onWifiNetworkBound: ((Network) -> Unit)? = null

    init {
        registerSystemWifiCallback()
    }

    private fun getProbeClient(network: Network? = activeWifiNetwork): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(2500, TimeUnit.MILLISECONDS)
            .readTimeout(2500, TimeUnit.MILLISECONDS)
        if (network != null) {
            builder.socketFactory(network.socketFactory)
        }
        return builder.build()
    }

    /**
     * Listens for ANY Wi-Fi network becoming available (whether through WifiNetworkSpecifier
     * or because the user connected to GoPro Wi-Fi in Android Settings).
     * Crucially removes NET_CAPABILITY_INTERNET since GoPro AP never provides internet.
     * Binds this app's process to the Wi-Fi network immediately so mobile data (5G/LTE)
     * cannot block or hijack traffic destined for 10.5.5.9.
     */
    private fun registerSystemWifiCallback() {
        try {
            val wifiRequest = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()

            connectivityManager.registerNetworkCallback(wifiRequest, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    Log.d(TAG, "System Wi-Fi network available: $network. Binding process to bypass 5G cellular...")
                    activeWifiNetwork = network
                    connectivityManager.bindProcessToNetwork(network)
                    onWifiNetworkBound?.invoke(network)
                    checkCameraReachability(targetSsid)
                }

                override fun onLost(network: Network) {
                    Log.d(TAG, "Wi-Fi network lost: $network")
                    if (activeWifiNetwork == network) {
                        activeWifiNetwork = null
                    }
                    if (connectivityManager.boundNetworkForProcess == network) {
                        connectivityManager.bindProcessToNetwork(null)
                    }
                }
            })
        } catch (e: Exception) {
            Log.w(TAG, "Could not register default Wi-Fi callback: ${e.message}")
        }
    }

    /**
     * Explicitly finds any active Wi-Fi interface on the device and binds the process to it.
     */
    fun getActiveWifiNetwork(): Network? {
        if (activeWifiNetwork != null) return activeWifiNetwork
        for (network in connectivityManager.allNetworks) {
            val caps = connectivityManager.getNetworkCapabilities(network)
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                return network
            }
        }
        return null
    }

    fun bindToActiveWifi(): Network? {
        val network = getActiveWifiNetwork()
        if (network != null) {
            Log.d(TAG, "Found active Wi-Fi network: $network. Binding process...")
            activeWifiNetwork = network
            connectivityManager.bindProcessToNetwork(network)
            onWifiNetworkBound?.invoke(network)
            return network
        }
        Log.w(TAG, "No active Wi-Fi network found in connectivityManager.allNetworks")
        return null
    }

    fun getGatewayIp(): String {
        val net = activeWifiNetwork ?: getActiveWifiNetwork()
        if (net != null) {
            val linkProps = connectivityManager.getLinkProperties(net)
            if (linkProps != null) {
                for (route in linkProps.routes) {
                    if (route.isDefaultRoute && route.gateway != null) {
                        val gwHost = route.gateway?.hostAddress
                        if (!gwHost.isNullOrEmpty() && gwHost != "0.0.0.0") {
                            Log.d(TAG, "Detected route gateway IP: $gwHost")
                            return gwHost
                        }
                    }
                }
            }
        }
        val dhcp = wifiManager?.dhcpInfo
        if (dhcp != null && dhcp.gateway != 0) {
            val ip = Formatter.formatIpAddress(dhcp.gateway)
            if (ip != "0.0.0.0") {
                Log.d(TAG, "Detected DHCP Gateway IP: $ip")
                return ip
            }
        }
        return GOPRO_DEFAULT_IP
    }

    fun connectToAccessPoint(ssid: String, wpa2Passphrase: String) {
        targetSsid = ssid
        _wifiState.value = WifiConnectionState.Connecting(ssid)
        Log.d(TAG, "Connecting to GoPro Wi-Fi AP: SSID='$ssid'")

        // 1. Immediately bind any already-associated Wi-Fi network
        bindToActiveWifi()

        // 2. Start active probe loop to detect camera response as soon as Wi-Fi establishes
        startReachabilityPolling(ssid)

        // 3. Also trigger system WifiNetworkSpecifier prompt
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

            releaseSpecifierCallback()

            specifierCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    Log.d(TAG, "SpecifierCallback onAvailable: Successfully connected to $ssid")
                    activeWifiNetwork = network
                    connectivityManager.bindProcessToNetwork(network)
                    onWifiNetworkBound?.invoke(network)
                    val gw = getGatewayIp()
                    _wifiState.value = WifiConnectionState.Connected(ssid, gw)
                }

                override fun onLost(network: Network) {
                    Log.w(TAG, "SpecifierCallback onLost: Lost $ssid")
                    if (activeWifiNetwork == network) {
                        activeWifiNetwork = null
                    }
                    connectivityManager.bindProcessToNetwork(null)
                    _wifiState.value = WifiConnectionState.Disconnected
                }

                override fun onUnavailable() {
                    Log.w(TAG, "SpecifierCallback onUnavailable: $ssid (user may connect via Settings)")
                }
            }

            try {
                connectivityManager.requestNetwork(request, specifierCallback!!)
            } catch (e: Exception) {
                Log.e(TAG, "Error requesting network with specifier: ${e.message}")
            }
        }
    }

    fun checkCameraReachability(ssid: String = targetSsid) {
        scope.launch(Dispatchers.IO) {
            val net = bindToActiveWifi()
            val client = getProbeClient(net)
            val gw = getGatewayIp()
            val ipsToTest = linkedSetOf(GOPRO_DEFAULT_IP, gw)

            for (ip in ipsToTest) {
                val probeUrl = "http://$ip:$GOPRO_PORT/gopro/camera/state"
                try {
                    val request = Request.Builder().url(probeUrl).get().build()
                    client.newCall(request).execute().use { response ->
                        if (response.isSuccessful || response.code in 200..499) {
                            Log.d(TAG, "Camera probe succeeded on $probeUrl! Connected.")
                            _wifiState.value = WifiConnectionState.Connected(ssid.ifEmpty { "GoPro AP" }, ip)
                            return@launch
                        }
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Probe on $probeUrl: ${e.message}")
                }
            }
        }
    }

    private fun startReachabilityPolling(ssid: String) {
        reachabilityPollingJob?.cancel()
        reachabilityPollingJob = scope.launch(Dispatchers.IO) {
            var attempts = 0
            while (isActive && attempts < 60) {
                attempts++
                val net = bindToActiveWifi()
                val client = getProbeClient(net)
                val gw = getGatewayIp()
                val ipsToTest = linkedSetOf(GOPRO_DEFAULT_IP, gw)

                for (ip in ipsToTest) {
                    val probeUrl = "http://$ip:$GOPRO_PORT/gopro/camera/state"
                    try {
                        val request = Request.Builder().url(probeUrl).get().build()
                        client.newCall(request).execute().use { response ->
                            if (response.isSuccessful || response.code in 200..499) {
                                Log.d(TAG, "Poll attempt $attempts: Camera reachable at $probeUrl! Connected.")
                                _wifiState.value = WifiConnectionState.Connected(ssid, ip)
                                return@launch
                            }
                        }
                    } catch (e: Exception) {
                        // Still negotiating association / DHCP
                    }
                }
                delay(1200)
            }
        }
    }

    suspend fun directProbe(): Boolean = withContext(Dispatchers.IO) {
        val net = bindToActiveWifi()
        val client = getProbeClient(net)
        val gw = getGatewayIp()
        val ipsToTest = linkedSetOf(GOPRO_DEFAULT_IP, gw)

        for (ip in ipsToTest) {
            val probeUrl = "http://$ip:$GOPRO_PORT/gopro/camera/state"
            try {
                val request = Request.Builder().url(probeUrl).get().build()
                client.newCall(request).execute().use { response ->
                    val ok = response.isSuccessful || response.code in 200..499
                    if (ok) {
                        _wifiState.value = WifiConnectionState.Connected(targetSsid.ifEmpty { "GoPro AP" }, ip)
                        return@withContext true
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Direct probe failed on $probeUrl: ${e.message}")
            }
        }
        false
    }

    fun disconnect() {
        reachabilityPollingJob?.cancel()
        reachabilityPollingJob = null
        releaseSpecifierCallback()
        connectivityManager.bindProcessToNetwork(null)
        activeWifiNetwork = null
        _wifiState.value = WifiConnectionState.Disconnected
    }

    private fun releaseSpecifierCallback() {
        specifierCallback?.let {
            try {
                connectivityManager.unregisterNetworkCallback(it)
            } catch (e: Exception) {
                Log.w(TAG, "Error unregistering specifier callback: ${e.message}")
            }
            specifierCallback = null
        }
    }
}

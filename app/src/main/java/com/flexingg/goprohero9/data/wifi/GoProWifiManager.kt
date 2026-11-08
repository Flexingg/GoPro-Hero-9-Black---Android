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
    data class OnHomeWifi(val homeGatewayIp: String, val expectedGoProSsid: String) : WifiConnectionState()
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
     * Determines whether a given network interface is genuinely the GoPro Wi-Fi AP.
     * GoPro AP always operates on subnet 10.5.5.0/24 with camera at 10.5.5.9 and client at 10.5.5.100+.
     */
    fun isGoProNetwork(network: Network): Boolean {
        val linkProps = connectivityManager.getLinkProperties(network) ?: return false
        for (addr in linkProps.linkAddresses) {
            val host = addr.address.hostAddress
            if (host != null && host.startsWith("10.5.5.")) {
                return true
            }
        }
        for (route in linkProps.routes) {
            val gw = route.gateway?.hostAddress
            if (gw != null && gw.startsWith("10.5.5.")) {
                return true
            }
        }
        return false
    }

    fun isConnectedToGoProNetwork(): Boolean {
        val net = activeWifiNetwork ?: getActiveWifiNetwork() ?: return false
        return isGoProNetwork(net)
    }

    /**
     * Returns home router gateway IP if currently on home Wi-Fi (e.g. 192.168.1.254).
     */
    fun getHomeWifiGateway(): String {
        for (net in connectivityManager.allNetworks) {
            val caps = connectivityManager.getNetworkCapabilities(net)
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                val linkProps = connectivityManager.getLinkProperties(net)
                if (linkProps != null) {
                    for (route in linkProps.routes) {
                        if (route.isDefaultRoute && route.gateway != null) {
                            val gw = route.gateway?.hostAddress ?: ""
                            if (!gw.startsWith("10.5.5.")) return gw
                        }
                    }
                }
            }
        }
        val dhcp = wifiManager?.dhcpInfo
        if (dhcp != null && dhcp.gateway != 0) {
            val ip = Formatter.formatIpAddress(dhcp.gateway)
            if (!ip.startsWith("10.5.5.")) return ip
        }
        return ""
    }

    /**
     * Listens for Wi-Fi network transitions.
     * Crucially: ONLY binds the process when the network is verified as the GoPro AP (10.5.5.x)!
     * Never binds the app to home Wi-Fi (192.168.1.x), preventing route poisoning.
     */
    private fun registerSystemWifiCallback() {
        try {
            val wifiRequest = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()

            connectivityManager.registerNetworkCallback(wifiRequest, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    Log.d(TAG, "Wi-Fi network detected: $network")
                    if (isGoProNetwork(network)) {
                        Log.d(TAG, "Verified GoPro Wi-Fi network ($network)! Binding process...")
                        activeWifiNetwork = network
                        connectivityManager.bindProcessToNetwork(network)
                        onWifiNetworkBound?.invoke(network)
                        _wifiState.value = WifiConnectionState.Connected(targetSsid.ifEmpty { "GoPro AP" }, GOPRO_DEFAULT_IP)
                        checkCameraReachability(targetSsid)
                    } else {
                        val homeGw = getHomeWifiGateway()
                        Log.d(TAG, "Non-GoPro Wi-Fi network detected (Gateway: $homeGw). Not binding process.")
                        if (!isConnectedToGoProNetwork()) {
                            _wifiState.value = WifiConnectionState.OnHomeWifi(homeGw, targetSsid)
                        }
                    }
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
     * Explicitly searches for an active GoPro Wi-Fi interface (10.5.5.x).
     */
    fun getActiveWifiNetwork(): Network? {
        if (activeWifiNetwork != null && isGoProNetwork(activeWifiNetwork!!)) {
            return activeWifiNetwork
        }
        for (network in connectivityManager.allNetworks) {
            val caps = connectivityManager.getNetworkCapabilities(network)
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                if (isGoProNetwork(network)) {
                    activeWifiNetwork = network
                    return network
                }
            }
        }
        return null
    }

    fun bindToActiveWifi(): Network? {
        val network = getActiveWifiNetwork()
        if (network != null) {
            Log.d(TAG, "Found active GoPro Wi-Fi network: $network. Binding process...")
            activeWifiNetwork = network
            connectivityManager.bindProcessToNetwork(network)
            onWifiNetworkBound?.invoke(network)
            return network
        }
        Log.d(TAG, "No active GoPro Wi-Fi network (10.5.5.x) found in system.")
        return null
    }

    /**
     * Returns camera gateway IP. In AP mode, this is ALWAYS 10.5.5.9.
     * Never returns a home router gateway (192.168.x.x).
     */
    fun getGatewayIp(): String {
        val net = getActiveWifiNetwork()
        if (net != null) {
            val linkProps = connectivityManager.getLinkProperties(net)
            if (linkProps != null) {
                for (route in linkProps.routes) {
                    val gwHost = route.gateway?.hostAddress
                    if (!gwHost.isNullOrEmpty() && gwHost.startsWith("10.5.5.")) {
                        return gwHost
                    }
                }
            }
        }
        return GOPRO_DEFAULT_IP
    }

    fun connectToAccessPoint(ssid: String, wpa2Passphrase: String) {
        targetSsid = ssid
        _wifiState.value = WifiConnectionState.Connecting(ssid)
        Log.d(TAG, "Connecting to GoPro Wi-Fi AP: SSID='$ssid'")

        // 1. Check if already associated with GoPro Wi-Fi
        val existingGoPro = bindToActiveWifi()
        if (existingGoPro != null) {
            _wifiState.value = WifiConnectionState.Connected(ssid, GOPRO_DEFAULT_IP)
            return
        }

        // 2. Start active probe loop to detect camera response once connected
        startReachabilityPolling(ssid)

        // 3. Trigger Android WifiNetworkSpecifier prompt
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
                    _wifiState.value = WifiConnectionState.Connected(ssid, GOPRO_DEFAULT_IP)
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
                    Log.w(TAG, "SpecifierCallback onUnavailable: $ssid (user can connect via Settings)")
                    val homeGw = getHomeWifiGateway()
                    if (homeGw.isNotEmpty()) {
                        _wifiState.value = WifiConnectionState.OnHomeWifi(homeGw, ssid)
                    }
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
            val probeUrl = "http://$GOPRO_DEFAULT_IP:$GOPRO_PORT/gopro/camera/state"
            try {
                val request = Request.Builder().url(probeUrl).get().build()
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful || response.code in 200..499) {
                        Log.d(TAG, "Camera probe succeeded on $probeUrl! Connected.")
                        _wifiState.value = WifiConnectionState.Connected(ssid.ifEmpty { "GoPro AP" }, GOPRO_DEFAULT_IP)
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "Probe on $probeUrl note: ${e.message}")
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
                if (net != null) {
                    val client = getProbeClient(net)
                    val probeUrl = "http://$GOPRO_DEFAULT_IP:$GOPRO_PORT/gopro/camera/state"
                    try {
                        val request = Request.Builder().url(probeUrl).get().build()
                        client.newCall(request).execute().use { response ->
                            if (response.isSuccessful || response.code in 200..499) {
                                Log.d(TAG, "Poll attempt $attempts: Camera reachable at $probeUrl! Connected.")
                                _wifiState.value = WifiConnectionState.Connected(ssid, GOPRO_DEFAULT_IP)
                                return@launch
                            }
                        }
                    } catch (_: Exception) {}
                }
                delay(1200)
            }
        }
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

package com.flexingg.goprohero9.data.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

sealed class BleConnectionState {
    object Disconnected : BleConnectionState()
    object Scanning : BleConnectionState()
    data class Connecting(val deviceName: String) : BleConnectionState()
    data class Connected(val deviceName: String) : BleConnectionState()
    data class WifiApInfo(val ssid: String, val password: String) : BleConnectionState()
    data class Error(val message: String) : BleConnectionState()
}

@SuppressLint("MissingPermission")
class Hero9BleManager(
    private val context: Context,
    private val bluetoothAdapter: BluetoothAdapter?,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "Hero9BleManager"

        // OpenGoPro Standard UUIDs for HERO9 Black
        val GOPRO_SERVICE_UUID: UUID = UUID.fromString("0000fea6-0000-1000-8000-00805f9b34fb")
        val COMMAND_REQ_UUID: UUID = UUID.fromString("b5f90072-aa8d-11e3-9046-0002a5d5c51b")
        val COMMAND_RSP_UUID: UUID = UUID.fromString("b5f90073-aa8d-11e3-9046-0002a5d5c51b")
        val WIFI_AP_SSID_UUID: UUID = UUID.fromString("b5f90002-aa8d-11e3-9046-0002a5d5c51b")
        val WIFI_AP_PASSWORD_UUID: UUID = UUID.fromString("b5f90003-aa8d-11e3-9046-0002a5d5c51b")

        // Command payload to enable camera Wi-Fi AP: length=3, ID=0x17 (WIFI_AP), param_len=1, value=1 (ENABLE)
        val CMD_ENABLE_WIFI = byteArrayOf(0x03, 0x17, 0x01, 0x01)
    }

    private val _connectionState = MutableStateFlow<BleConnectionState>(BleConnectionState.Disconnected)
    val connectionState: StateFlow<BleConnectionState> = _connectionState.asStateFlow()

    private var bluetoothGatt: BluetoothGatt? = null
    private var targetDevice: BluetoothDevice? = null
    private var goproService: BluetoothGattService? = null

    private var discoveredSsid: String? = null
    private var discoveredPassword: String? = null

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result?.device?.let { device ->
                val name = device.name ?: "Unknown GoPro"
                if (name.contains("GoPro", ignoreCase = true) || name.contains("HERO", ignoreCase = true)) {
                    Log.d(TAG, "Found GoPro HERO9: $name [${device.address}]")
                    stopScan()
                    connectToDevice(device)
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed with error code: $errorCode")
            _connectionState.value = BleConnectionState.Error("BLE scan failed (code $errorCode)")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d(TAG, "GATT connected, requesting MTU and discovering services...")
                gatt?.requestMtu(512)
                gatt?.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d(TAG, "GATT disconnected")
                _connectionState.value = BleConnectionState.Disconnected
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                goproService = gatt?.getService(GOPRO_SERVICE_UUID)
                val deviceName = targetDevice?.name ?: "GoPro HERO9"
                Log.d(TAG, "GoPro Service discovered on $deviceName")
                _connectionState.value = BleConnectionState.Connected(deviceName)

                // Read Wi-Fi SSID first, then read password, then enable Wi-Fi AP
                readWifiSsid()
            } else {
                Log.e(TAG, "Service discovery failed: $status")
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS && characteristic != null) {
                when (characteristic.uuid) {
                    WIFI_AP_SSID_UUID -> {
                        val ssid = String(characteristic.value, Charsets.UTF_8).trim()
                        Log.d(TAG, "Read GoPro Wi-Fi SSID: $ssid")
                        discoveredSsid = ssid
                        readWifiPassword()
                    }
                    WIFI_AP_PASSWORD_UUID -> {
                        val pass = String(characteristic.value, Charsets.UTF_8).trim()
                        Log.d(TAG, "Read GoPro Wi-Fi Password: $pass")
                        discoveredPassword = pass

                        // Now send command to turn on Wi-Fi AP
                        enableWifiAp()
                    }
                }
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?,
            status: Int
        ) {
            if (characteristic?.uuid == COMMAND_REQ_UUID) {
                Log.d(TAG, "Wi-Fi AP enable command written. Camera AP is active.")
                if (discoveredSsid != null && discoveredPassword != null) {
                    _connectionState.value = BleConnectionState.WifiApInfo(
                        ssid = discoveredSsid!!,
                        password = discoveredPassword!!
                    )
                }
            }
        }
    }

    fun startScan() {
        val scanner = bluetoothAdapter?.bluetoothLeScanner
        if (scanner == null) {
            _connectionState.value = BleConnectionState.Error("Bluetooth LE is not available")
            return
        }

        _connectionState.value = BleConnectionState.Scanning

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(GOPRO_SERVICE_UUID))
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            // Also scan unfiltered in case advertisement doesn't carry full UUID in broadcast
            scanner.startScan(listOf(filter), settings, scanCallback)
        } catch (e: Exception) {
            Log.w(TAG, "Filtered scan failed, attempting fallback generic scan: ${e.message}")
            try {
                scanner.startScan(scanCallback)
            } catch (e2: Exception) {
                _connectionState.value = BleConnectionState.Error("Could not start BLE scan: ${e2.message}")
            }
        }
    }

    fun stopScan() {
        try {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping scan: ${e.message}")
        }
    }

    fun connectToDevice(device: BluetoothDevice) {
        targetDevice = device
        _connectionState.value = BleConnectionState.Connecting(device.name ?: "GoPro HERO9")
        bluetoothGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    fun readWifiSsid() {
        val characteristic = goproService?.getCharacteristic(WIFI_AP_SSID_UUID)
        if (characteristic != null) {
            bluetoothGatt?.readCharacteristic(characteristic)
        }
    }

    fun readWifiPassword() {
        val characteristic = goproService?.getCharacteristic(WIFI_AP_PASSWORD_UUID)
        if (characteristic != null) {
            bluetoothGatt?.readCharacteristic(characteristic)
        }
    }

    fun enableWifiAp() {
        val commandChar = goproService?.getCharacteristic(COMMAND_REQ_UUID)
        if (commandChar != null) {
            commandChar.value = CMD_ENABLE_WIFI
            bluetoothGatt?.writeCharacteristic(commandChar)
        }
    }

    fun disconnect() {
        stopScan()
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        _connectionState.value = BleConnectionState.Disconnected
    }
}

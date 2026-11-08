package com.flexingg.goprohero9.data.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
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
import kotlinx.coroutines.delay
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
    data class Pairing(val deviceName: String) : BleConnectionState()
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
        val WIFI_ACCESS_POINT_SERVICE_UUID: UUID = UUID.fromString("b5f90001-aa8d-11e3-9046-0002a5d5c51b")

        val CQ_COMMAND_UUID: UUID = UUID.fromString("b5f90072-aa8d-11e3-9046-0002a5d5c51b")
        val CQ_COMMAND_RSP_UUID: UUID = UUID.fromString("b5f90073-aa8d-11e3-9046-0002a5d5c51b")
        val WIFI_AP_SSID_UUID: UUID = UUID.fromString("b5f90002-aa8d-11e3-9046-0002a5d5c51b")
        val WIFI_AP_PASSWORD_UUID: UUID = UUID.fromString("b5f90003-aa8d-11e3-9046-0002a5d5c51b")
        val CLIENT_CONFIG_DESCRIPTOR_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        // Command payload to enable camera Wi-Fi AP: length=3, ID=0x17 (WIFI_AP), param_len=1, value=1 (ENABLE)
        val CMD_ENABLE_WIFI = byteArrayOf(0x03, 0x17, 0x01, 0x01)
    }

    private val _connectionState = MutableStateFlow<BleConnectionState>(BleConnectionState.Disconnected)
    val connectionState: StateFlow<BleConnectionState> = _connectionState.asStateFlow()

    private var bluetoothGatt: BluetoothGatt? = null
    private var targetDevice: BluetoothDevice? = null

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

    private fun findCharacteristic(uuid: UUID): BluetoothGattCharacteristic? {
        val services = bluetoothGatt?.services ?: return null
        for (service in services) {
            val char = service.getCharacteristic(uuid)
            if (char != null) return char
        }
        return null
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            Log.d(TAG, "onConnectionStateChange: status=$status newState=$newState")
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                val devName = targetDevice?.name ?: "GoPro HERO9"
                _connectionState.value = BleConnectionState.Connecting(devName)
                Log.d(TAG, "GATT connected, requesting MTU 512...")
                gatt?.requestMtu(512)
                // Small delay to allow MTU negotiation before service discovery
                scope.launch(Dispatchers.IO) {
                    delay(300)
                    gatt?.discoverServices()
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d(TAG, "GATT disconnected (status=$status)")
                _connectionState.value = BleConnectionState.Disconnected
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && gatt != null) {
                val deviceName = targetDevice?.name ?: "GoPro HERO9"
                Log.d(TAG, "Services discovered successfully for $deviceName:")
                gatt.services.forEach { service ->
                    Log.d(TAG, "  Service: ${service.uuid}")
                    service.characteristics.forEach { char ->
                        Log.d(TAG, "    Char: ${char.uuid} (props=${char.properties})")
                    }
                }

                _connectionState.value = BleConnectionState.Pairing(deviceName)

                // Sequence the initialization steps cleanly
                scope.launch(Dispatchers.IO) {
                    delay(200)

                    // Step 1: Enable notifications on CQ_COMMAND_RSP
                    val rspChar = findCharacteristic(CQ_COMMAND_RSP_UUID)
                    if (rspChar != null) {
                        Log.d(TAG, "Enabling notification on CQ_COMMAND_RSP...")
                        gatt.setCharacteristicNotification(rspChar, true)
                        val desc = rspChar.getDescriptor(CLIENT_CONFIG_DESCRIPTOR_UUID)
                        if (desc != null) {
                            desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            gatt.writeDescriptor(desc)
                            delay(200)
                        }
                    }

                    // Step 2: Read WIFI_AP_PASSWORD (this triggers Android BLE bonding prompt if needed)
                    val passChar = findCharacteristic(WIFI_AP_PASSWORD_UUID)
                    if (passChar != null) {
                        Log.d(TAG, "Reading WIFI_AP_PASSWORD characteristic...")
                        gatt.readCharacteristic(passChar)
                    } else {
                        Log.w(TAG, "WIFI_AP_PASSWORD characteristic not found! Trying SSID...")
                        val ssidChar = findCharacteristic(WIFI_AP_SSID_UUID)
                        if (ssidChar != null) {
                            gatt.readCharacteristic(ssidChar)
                        } else {
                            // Fallback if characteristics not present
                            triggerWifiEnableDirectly()
                        }
                    }
                }
            } else {
                Log.e(TAG, "Service discovery failed with status $status")
                _connectionState.value = BleConnectionState.Error("Service discovery failed (status $status)")
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?,
            status: Int
        ) {
            Log.d(TAG, "onCharacteristicRead: uuid=${characteristic?.uuid} status=$status")
            if (status == BluetoothGatt.GATT_SUCCESS && characteristic != null) {
                when (characteristic.uuid) {
                    WIFI_AP_PASSWORD_UUID -> {
                        val pass = String(characteristic.value, Charsets.UTF_8).trim()
                        Log.d(TAG, "Read GoPro Wi-Fi Password: '$pass'")
                        discoveredPassword = pass

                        // Now read SSID
                        scope.launch(Dispatchers.IO) {
                            delay(150)
                            val ssidChar = findCharacteristic(WIFI_AP_SSID_UUID)
                            if (ssidChar != null) {
                                gatt?.readCharacteristic(ssidChar)
                            } else {
                                triggerWifiEnableDirectly()
                            }
                        }
                    }
                    WIFI_AP_SSID_UUID -> {
                        val ssid = String(characteristic.value, Charsets.UTF_8).trim()
                        Log.d(TAG, "Read GoPro Wi-Fi SSID: '$ssid'")
                        discoveredSsid = ssid

                        // Now send command to turn on Wi-Fi AP
                        scope.launch(Dispatchers.IO) {
                            delay(150)
                            triggerWifiEnableDirectly()
                        }
                    }
                }
            } else {
                Log.w(TAG, "Characteristic read failed for ${characteristic?.uuid} with status $status")
                // If read failed (e.g. requires bonding first), proceed with fallback
                triggerWifiEnableDirectly()
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?,
            status: Int
        ) {
            Log.d(TAG, "onCharacteristicWrite: uuid=${characteristic?.uuid} status=$status")
            if (characteristic?.uuid == CQ_COMMAND_UUID && status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Wi-Fi AP enable command successfully written! Camera AP is activating...")
                notifyWifiApReady()
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?
        ) {
            Log.d(TAG, "onCharacteristicChanged: uuid=${characteristic?.uuid} value=${characteristic?.value?.joinToString { "0x%02x".format(it) }}")
            if (characteristic?.uuid == CQ_COMMAND_RSP_UUID) {
                notifyWifiApReady()
            }
        }
    }

    private fun triggerWifiEnableDirectly() {
        val commandChar = findCharacteristic(CQ_COMMAND_UUID)
        if (commandChar != null) {
            Log.d(TAG, "Sending CMD_ENABLE_WIFI [0x03, 0x17, 0x01, 0x01] to CQ_COMMAND...")
            commandChar.value = CMD_ENABLE_WIFI
            bluetoothGatt?.writeCharacteristic(commandChar)
            // Also schedule fallback emission in case write callback doesn't arrive
            scope.launch(Dispatchers.IO) {
                delay(1000)
                notifyWifiApReady()
            }
        } else {
            Log.e(TAG, "CQ_COMMAND characteristic not found!")
            notifyWifiApReady()
        }
    }

    private fun notifyWifiApReady() {
        val finalSsid = discoveredSsid?.takeIf { it.isNotBlank() }
            ?: targetDevice?.name?.takeIf { it.isNotBlank() }
            ?: "GoPro HERO9"
        val finalPassword = discoveredPassword?.takeIf { it.isNotBlank() } ?: "goprohero"

        Log.d(TAG, "Wi-Fi AP Info Ready: SSID='$finalSsid', Password='$finalPassword'")
        _connectionState.value = BleConnectionState.WifiApInfo(
            ssid = finalSsid,
            password = finalPassword
        )
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
            // First try scanning with service filter
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
        Log.d(TAG, "Connecting to GATT on ${device.name} [${device.address}]...")

        // Initiate bond if not bonded yet
        if (device.bondState == BluetoothDevice.BOND_NONE) {
            Log.d(TAG, "Initiating device.createBond()...")
            device.createBond()
        }

        bluetoothGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        stopScan()
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        _connectionState.value = BleConnectionState.Disconnected
    }
}

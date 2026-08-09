package com.group17.liquidsensor.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import com.group17.liquidsensor.data.Packet

/**
 * Manages the BLE connection to the RN4871 module, using its factory-default
 * "Transparent UART" GATT service. Incoming data is currently the firmware's
 * plain-text debug output — see [TextLineParser]. Once Frederick's firmware
 * implements the real binary packet protocol, swap [TextLineParser] back to
 * [PacketParser] here.
 *
 * Call [tryConnect] only after [hasRequiredPermissions] is true; the Activity
 * owns the runtime permission request (see [requiredPermissions]) since that
 * needs an Activity context.
 */
class BleSerialManager(
    private val context: Context,
    private val onPacketReceived: (Packet) -> Unit,
    private val onConnectionChanged: (Boolean) -> Unit
) {
    private val TAG = "BleSerialManager"

    private val bluetoothManager by lazy { context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager }
    private val textParser = TextLineParser()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var gatt: BluetoothGatt? = null
    private var txCharacteristic: BluetoothGattCharacteristic? = null
    private var scanning = false

    // ── Public API ────────────────────────────────────────────────────────────

    /** Set when the user explicitly disconnects; suppresses auto-reconnect. */
    private var userDisconnected = false

    private val reconnectRunnable = Runnable {
        if (!userDisconnected && gatt == null) {
            Log.i(TAG, "Auto-reconnect attempt")
            tryConnect()
        }
    }

    // A connectGatt attempt can hang forever without any callback; if it
    // hasn't fully established (services + notifications) in time, tear it
    // down so the gatt != null guard can't deadlock the retry loop.
    @SuppressLint("MissingPermission")
    private val connectTimeoutRunnable = Runnable {
        if (gatt != null && txCharacteristic == null) {
            Log.w(TAG, "Connect attempt timed out — tearing down and retrying")
            try { gatt?.disconnect() } catch (_: Exception) {}
            try { gatt?.close() } catch (_: Exception) {}
            gatt = null
            onConnectionChanged(false)
            scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        if (userDisconnected) return
        mainHandler.removeCallbacks(reconnectRunnable)
        mainHandler.postDelayed(reconnectRunnable, RECONNECT_DELAY_MS)
    }

    @SuppressLint("MissingPermission")
    fun tryConnect() {
        userDisconnected = false
        // Already connected or connecting: never stack a second GATT session.
        // Each stacked session delivers its own copy of every notification,
        // producing duplicated measurement records.
        if (gatt != null) return
        val adapter = bluetoothManager.adapter
        if (adapter == null || !adapter.isEnabled) {
            Log.w(TAG, "Bluetooth adapter unavailable or disabled")
            onConnectionChanged(false)
            scheduleReconnect()
            return
        }
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            Log.w(TAG, "BLE scanner unavailable")
            onConnectionChanged(false)
            scheduleReconnect()
            return
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            scanning = true
            // Unfiltered scan; matching happens in the callback. A hardware
            // filter on the service UUID misses RN4871 modules that don't
            // include it in their advertisement, so we also accept the
            // module's default name prefix.
            scanner.startScan(null, settings, scanCallback)
            mainHandler.postDelayed({
                stopScan(scanner)
                // Scan window ended without a connection: try again later.
                if (gatt == null) scheduleReconnect()
            }, BleConstants.SCAN_TIMEOUT_MS)
        } catch (e: SecurityException) {
            Log.e(TAG, "Missing BLE permission: ${e.message}")
            scanning = false
            onConnectionChanged(false)
        }
    }

    /** Send a raw byte array to the device. Use [PacketParser.buildCommand] to build it. */
    fun send(bytes: ByteArray) {
        val g    = gatt ?: return
        val char = txCharacteristic ?: return
        try {
            writeCharacteristicCompat(g, char, bytes)
        } catch (e: SecurityException) {
            Log.e(TAG, "Write failed (permission): ${e.message}")
        }
    }

    /**
     * Tear down a link that Android still reports as connected but that has
     * stopped delivering notifications (wedged subscription), then reconnect.
     * Reconnecting rebuilds the GATT session and re-enables notifications.
     */
    @SuppressLint("MissingPermission")
    fun forceReconnect() {
        Log.w(TAG, "Force reconnect: link reported connected but data has stalled")
        mainHandler.removeCallbacks(reconnectRunnable)
        try { gatt?.disconnect() } catch (_: Exception) {}
        try { gatt?.close() } catch (_: Exception) {}
        gatt = null
        txCharacteristic = null
        userDisconnected = false
        onConnectionChanged(false)
        scheduleReconnect()
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        userDisconnected = true
        mainHandler.removeCallbacks(reconnectRunnable)
        try { gatt?.disconnect() } catch (_: Exception) {}
        try { gatt?.close() } catch (_: Exception) {}
        gatt = null
        txCharacteristic = null
        onConnectionChanged(false)
        Log.i(TAG, "BLE device disconnected")
    }

    // ── Scanning ──────────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private fun stopScan(scanner: BluetoothLeScanner) {
        if (!scanning) return
        scanning = false
        try { scanner.stopScan(scanCallback) } catch (_: Exception) {}
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.scanRecord?.deviceName ?: ""
            val advertisesUartService = result.scanRecord?.serviceUuids
                ?.contains(ParcelUuid(BleConstants.SERVICE_UUID)) == true
            if (!advertisesUartService && !name.startsWith(BleConstants.DEVICE_NAME_PREFIX)) {
                return  // not our module — keep scanning
            }
            val scanner = bluetoothManager.adapter?.bluetoothLeScanner
            if (scanning && scanner != null) stopScan(scanner)
            if (gatt != null) return  // a connection appeared meanwhile — don't stack another
            Log.i(TAG, "Found RN4871 candidate: $name ${result.device.address}")
            try {
                gatt = result.device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
                mainHandler.removeCallbacks(connectTimeoutRunnable)
                mainHandler.postDelayed(connectTimeoutRunnable, CONNECT_TIMEOUT_MS)
            } catch (e: SecurityException) {
                Log.e(TAG, "connectGatt failed (permission): ${e.message}")
                onConnectionChanged(false)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            Log.e(TAG, "BLE scan failed, error=$errorCode")
            onConnectionChanged(false)
            scheduleReconnect()
        }
    }

    // ── Connection / GATT callbacks ───────────────────────────────────────────

    private val gattCallback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "GATT connected, discovering services")
                    g.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.w(TAG, "GATT disconnected (status=$status)")
                    mainHandler.removeCallbacks(connectTimeoutRunnable)
                    txCharacteristic = null
                    onConnectionChanged(false)
                    try { g.close() } catch (_: Exception) {}
                    if (gatt === g) gatt = null
                    // Link dropped (module reset, radio glitch, timeout):
                    // reconnect automatically instead of going deaf.
                    scheduleReconnect()
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Service discovery failed, status=$status")
                onConnectionChanged(false)
                return
            }
            val service = g.getService(BleConstants.SERVICE_UUID)
            if (service == null) {
                Log.e(TAG, "Transparent UART service not found on device")
                onConnectionChanged(false)
                return
            }
            val rxChar = service.getCharacteristic(BleConstants.RX_CHARACTERISTIC_UUID)
            txCharacteristic = service.getCharacteristic(BleConstants.TX_CHARACTERISTIC_UUID)
            if (txCharacteristic == null || rxChar == null) {
                Log.e(TAG, "TX/RX characteristics not found")
                onConnectionChanged(false)
                return
            }

            g.setCharacteristicNotification(rxChar, true)
            val cccd = rxChar.getDescriptor(BleConstants.CCCD_UUID)
            if (cccd != null) {
                writeDescriptorCompat(g, cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            }

            mainHandler.removeCallbacks(connectTimeoutRunnable)
            onConnectionChanged(true)
            Log.i(TAG, "BLE device connected, notifications enabled")
        }

        @Deprecated("Deprecated in Java")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid == BleConstants.RX_CHARACTERISTIC_UUID) {
                handleIncoming(characteristic.value)
            }
        }

        // API 33+ overload; only this one fires on Android 13+ (the deprecated one above won't).
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (characteristic.uuid == BleConstants.RX_CHARACTERISTIC_UUID) {
                handleIncoming(value)
            }
        }
    }

    private fun handleIncoming(data: ByteArray?) {
        if (data == null) return
        textParser.feed(data).forEach { onPacketReceived(it) }
    }

    // ── API-level write compatibility (signatures changed in API 33) ──────────

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    private fun writeCharacteristicCompat(
        g: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        bytes: ByteArray
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(characteristic, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        } else {
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            characteristic.value = bytes
            g.writeCharacteristic(characteristic)
        }
    }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    private fun writeDescriptorCompat(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, value: ByteArray) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(descriptor, value)
        } else {
            descriptor.value = value
            g.writeDescriptor(descriptor)
        }
    }

    companion object {
        /** Delay between automatic reconnect attempts after a drop or empty scan. */
        private const val RECONNECT_DELAY_MS = 3000L

        /** Max time for a connect attempt to fully establish before teardown. */
        private const val CONNECT_TIMEOUT_MS = 15_000L

        /** Runtime permissions the Activity must request before calling [tryConnect]. */
        fun requiredPermissions(): Array<String> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
            }

        fun hasRequiredPermissions(context: Context): Boolean =
            requiredPermissions().all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }
    }
}

package com.group17.liquidid.data.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import com.group17.liquidid.data.model.ConnectionState
import com.group17.liquidid.data.model.DeviceCommand
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.util.concurrent.Executors

/**
 * Manages the USB CDC serial connection to the STM32 device.
 *
 * Responsibilities:
 * - Device discovery and permission handling
 * - Connection lifecycle (open, close, reconnect)
 * - Background byte-stream reading with packet boundary detection
 * - Packet parsing and delivery to consumers via StateFlow
 * - Command transmission with retry logic
 *
 * Uses the usb-serial-for-android library for CDC serial interface management.
 */
class UsbSerialManager(private val context: Context) {

    companion object {
        private const val TAG = "UsbSerialManager"
        private const val ACTION_USB_PERMISSION = "com.group17.liquidid.USB_PERMISSION"
        private const val BAUD_RATE = 115200
        private const val DATA_BITS = 8
        private const val READ_TIMEOUT_MS = 100
        private const val WRITE_TIMEOUT_MS = 500
        private const val ACK_TIMEOUT_MS = 500L
        private const val MAX_RETRIES = 3
        private const val MAX_BUFFER_SIZE = 4096
    }

    // --- Public state flows ---

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _parsedPackets = MutableStateFlow<PacketParser.ParsedPacket?>(null)
    val parsedPackets: StateFlow<PacketParser.ParsedPacket?> = _parsedPackets.asStateFlow()

    // --- Internal state ---

    private var usbManager: UsbManager? = null
    private var serialPort: UsbSerialPort? = null
    private var connection: UsbDeviceConnection? = null
    private var ioManager: SerialInputOutputManager? = null

    // Accumulation buffer for incoming bytes (packet reassembly)
    private val rxBuffer = ByteArray(MAX_BUFFER_SIZE)
    private var rxBufferPos = 0

    // --- USB permission receiver ---

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action == ACTION_USB_PERMISSION) {
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                }
                if (granted && device != null) {
                    openConnection(device)
                } else {
                    Log.w(TAG, "USB permission denied")
                    _connectionState.value = ConnectionState.ERROR
                }
            }
        }
    }

    // --- USB detach receiver ---

    private val usbDetachReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action == UsbManager.ACTION_USB_DEVICE_DETACHED) {
                disconnect()
            }
        }
    }

    // --- Lifecycle ---

    /**
     * Initialize the manager. Call from Activity.onCreate().
     */
    fun initialize() {
        usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

        val permFilter = IntentFilter(ACTION_USB_PERMISSION)
        val detachFilter = IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(usbPermissionReceiver, permFilter, Context.RECEIVER_NOT_EXPORTED)
            context.registerReceiver(usbDetachReceiver, detachFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(usbPermissionReceiver, permFilter)
            context.registerReceiver(usbDetachReceiver, detachFilter)
        }
    }

    /**
     * Scan for compatible USB serial devices and attempt connection.
     */
    fun connect() {
        _connectionState.value = ConnectionState.CONNECTING

        val manager = usbManager ?: run {
            _connectionState.value = ConnectionState.ERROR
            return
        }

        val availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(manager)
        if (availableDrivers.isEmpty()) {
            Log.w(TAG, "No USB serial devices found")
            _connectionState.value = ConnectionState.DISCONNECTED
            return
        }

        val driver: UsbSerialDriver = availableDrivers[0]
        val device = driver.device

        if (!manager.hasPermission(device)) {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE
            } else {
                0
            }
            val permIntent = PendingIntent.getBroadcast(context, 0,
                Intent(ACTION_USB_PERMISSION), flags)
            manager.requestPermission(device, permIntent)
            return
        }

        openConnection(device)
    }

    /**
     * Open the serial port and begin reading.
     */
    private fun openConnection(device: UsbDevice) {
        val manager = usbManager ?: return

        try {
            val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(manager)
            val driver = drivers.firstOrNull { it.device == device } ?: run {
                _connectionState.value = ConnectionState.ERROR
                return
            }

            connection = manager.openDevice(device)
            if (connection == null) {
                _connectionState.value = ConnectionState.ERROR
                return
            }

            serialPort = driver.ports[0].also { port ->
                port.open(connection)
                port.setParameters(BAUD_RATE, DATA_BITS,
                    UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            }

            // Start background I/O
            startReading()
            _connectionState.value = ConnectionState.CONNECTED
            Log.i(TAG, "Connected to ${device.productName}")

        } catch (e: IOException) {
            Log.e(TAG, "Connection failed", e)
            _connectionState.value = ConnectionState.ERROR
        }
    }

    /**
     * Start the background serial reader thread.
     */
    private fun startReading() {
        val port = serialPort ?: return

        ioManager = SerialInputOutputManager(port, object : SerialInputOutputManager.Listener {
            override fun onNewData(data: ByteArray) {
                processIncomingBytes(data)
            }

            override fun onRunError(e: Exception) {
                Log.e(TAG, "Serial read error", e)
                _connectionState.value = ConnectionState.ERROR
            }
        }).also {
            Executors.newSingleThreadExecutor().submit(it)
        }
    }

    /**
     * Process raw bytes from the serial stream, detect packet boundaries,
     * validate checksums, and emit parsed packets.
     */
    @Synchronized
    private fun processIncomingBytes(data: ByteArray) {
        // Append incoming data to buffer
        val spaceAvailable = MAX_BUFFER_SIZE - rxBufferPos
        val copyLen = minOf(data.size, spaceAvailable)
        data.copyInto(rxBuffer, rxBufferPos, 0, copyLen)
        rxBufferPos += copyLen

        // Scan for complete packets
        var scanPos = 0
        while (scanPos < rxBufferPos - PacketParser.HEADER_SIZE) {
            // Look for sync marker
            if (rxBuffer[scanPos] != PacketParser.SYNC_BYTE_1 ||
                rxBuffer[scanPos + 1] != PacketParser.SYNC_BYTE_2) {
                scanPos++
                continue
            }

            // Read header fields
            val type = rxBuffer[scanPos + 2]
            val payloadLen = (rxBuffer[scanPos + 3].toInt() and 0xFF) or
                    ((rxBuffer[scanPos + 4].toInt() and 0xFF) shl 8)

            val totalPacketLen = PacketParser.HEADER_SIZE + payloadLen + PacketParser.CHECKSUM_SIZE

            // Do we have the full packet?
            if (scanPos + totalPacketLen > rxBufferPos) {
                break // Wait for more data
            }

            // Extract the full packet bytes
            val packetBytes = rxBuffer.copyOfRange(scanPos, scanPos + totalPacketLen)

            // Validate checksum
            if (PacketParser.validateChecksum(packetBytes)) {
                val payload = packetBytes.copyOfRange(
                    PacketParser.HEADER_SIZE,
                    PacketParser.HEADER_SIZE + payloadLen
                )
                val parsed = PacketParser.parse(type, payload)
                _parsedPackets.value = parsed
            } else {
                Log.w(TAG, "Checksum failed for packet at offset $scanPos")
            }

            scanPos += totalPacketLen
        }

        // Compact the buffer — move unprocessed bytes to the front
        if (scanPos > 0) {
            val remaining = rxBufferPos - scanPos
            if (remaining > 0) {
                rxBuffer.copyInto(rxBuffer, 0, scanPos, scanPos + remaining)
            }
            rxBufferPos = remaining
        }
    }

    /**
     * Send a command to the device with retry logic.
     */
    fun sendCommand(command: DeviceCommand, params: ByteArray = byteArrayOf()) {
        val port = serialPort ?: run {
            Log.w(TAG, "Cannot send — not connected")
            return
        }

        val packet = PacketParser.buildCommandPacket(command.code, params)

        try {
            port.write(packet, WRITE_TIMEOUT_MS)
            Log.d(TAG, "Sent command: ${command.name}")
        } catch (e: IOException) {
            Log.e(TAG, "Failed to send command", e)
        }
    }

    /**
     * Disconnect and release all resources.
     */
    fun disconnect() {
        ioManager?.listener = null
        ioManager?.stop()
        ioManager = null

        try {
            serialPort?.close()
        } catch (e: IOException) {
            Log.w(TAG, "Error closing port", e)
        }
        serialPort = null

        connection?.close()
        connection = null

        rxBufferPos = 0
        _connectionState.value = ConnectionState.DISCONNECTED
        Log.i(TAG, "Disconnected")
    }

    /**
     * Unregister receivers. Call from Activity.onDestroy().
     */
    fun release() {
        disconnect()
        try {
            context.unregisterReceiver(usbPermissionReceiver)
            context.unregisterReceiver(usbDetachReceiver)
        } catch (_: IllegalArgumentException) {
            // Already unregistered
        }
    }
}

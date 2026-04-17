package com.group17.liquidid.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.group17.liquidid.data.model.*
import com.group17.liquidid.data.repository.SessionLogger
import com.group17.liquidid.data.usb.PacketParser
import com.group17.liquidid.data.usb.UsbSerialManager
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * Central ViewModel implementing MVVM pattern for the Liquid ID app.
 *
 * Holds:
 * - Current device state (connection, mode, status)
 * - Latest measurement result
 * - Substance label table
 * - Session history
 * - Live sensor reading history for charting
 *
 * Exposes all state via Kotlin StateFlow for reactive UI updates.
 */
class DeviceViewModel(application: Application) : AndroidViewModel(application) {

    // --- Managers ---

    val usbManager = UsbSerialManager(application.applicationContext)
    val sessionLogger = SessionLogger(application.applicationContext)

    // --- Device State ---

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _deviceStatus = MutableStateFlow<DeviceStatus?>(null)
    val deviceStatus: StateFlow<DeviceStatus?> = _deviceStatus.asStateFlow()

    private val _substanceLabels = MutableStateFlow<List<String>>(emptyList())
    val substanceLabels: StateFlow<List<String>> = _substanceLabels.asStateFlow()

    // --- Measurement State ---

    private val _latestMeasurement = MutableStateFlow<MeasurementRecord?>(null)
    val latestMeasurement: StateFlow<MeasurementRecord?> = _latestMeasurement.asStateFlow()

    /** Rolling history of the last N measurements for charting */
    private val _measurementHistory = MutableStateFlow<List<MeasurementRecord>>(emptyList())
    val measurementHistory: StateFlow<List<MeasurementRecord>> = _measurementHistory.asStateFlow()

    /** Max chart points to retain */
    private val maxHistorySize = 20

    // --- UI State ---

    private val _selectedModel = MutableStateFlow(ClassificationModel.KNN)
    val selectedModel: StateFlow<ClassificationModel> = _selectedModel.asStateFlow()

    private val _toastMessage = MutableSharedFlow<String>(replay = 0)
    val toastMessage: SharedFlow<String> = _toastMessage.asSharedFlow()

    // --- Initialization ---

    init {
        usbManager.initialize()

        // Mirror USB manager connection state
        viewModelScope.launch {
            usbManager.connectionState.collect { state ->
                _connectionState.value = state
            }
        }

        // Process incoming parsed packets
        viewModelScope.launch {
            usbManager.parsedPackets
                .filterNotNull()
                .collect { packet -> handlePacket(packet) }
        }
    }

    // --- Packet Handling ---

    private suspend fun handlePacket(packet: PacketParser.ParsedPacket) {
        when (packet) {
            is PacketParser.ParsedPacket.Measurement -> {
                val record = packet.record
                _latestMeasurement.value = record

                // Append to rolling history
                val current = _measurementHistory.value.toMutableList()
                current.add(record)
                if (current.size > maxHistorySize) {
                    current.removeAt(0)
                }
                _measurementHistory.value = current

                // Log if session recording is active
                sessionLogger.addRecord(record)
            }

            is PacketParser.ParsedPacket.Status -> {
                _deviceStatus.value = packet.status
            }

            is PacketParser.ParsedPacket.LabelTable -> {
                _substanceLabels.value = packet.labels
            }

            is PacketParser.ParsedPacket.Ack -> {
                // ACK received — could implement pending-command confirmation here
            }

            is PacketParser.ParsedPacket.Unknown -> {
                // Ignore unknown packet types
            }
        }
    }

    // --- Actions ---

    fun connectDevice() {
        usbManager.connect()
    }

    fun disconnectDevice() {
        usbManager.disconnect()
    }

    fun startMeasurement() {
        usbManager.sendCommand(DeviceCommand.START_MEASUREMENT)
    }

    fun setModeIdentification() {
        usbManager.sendCommand(DeviceCommand.SET_MODE_IDENTIFICATION)
    }

    fun setModeReference() {
        usbManager.sendCommand(DeviceCommand.SET_MODE_REFERENCE)
    }

    fun requestExport() {
        usbManager.sendCommand(DeviceCommand.REQUEST_EXPORT)
    }

    fun requestStatus() {
        usbManager.sendCommand(DeviceCommand.REQUEST_STATUS)
    }

    fun selectModel(model: ClassificationModel) {
        _selectedModel.value = model
    }

    // --- Session Logging ---

    fun startSessionLogging() {
        sessionLogger.startSession()
        viewModelScope.launch { _toastMessage.emit("Session recording started") }
    }

    fun stopSessionLogging() {
        sessionLogger.stopSession()
        viewModelScope.launch { _toastMessage.emit("Session recording stopped") }
    }

    fun exportSessionCsv() {
        val path = sessionLogger.exportCsv(
            _substanceLabels.value,
            _deviceStatus.value?.modelVersion ?: 0
        )
        viewModelScope.launch {
            if (path != null) {
                _toastMessage.emit("Exported CSV to $path")
            } else {
                _toastMessage.emit("Export failed — no data recorded")
            }
        }
    }

    fun exportSessionJson() {
        val path = sessionLogger.exportJson(
            _substanceLabels.value,
            _deviceStatus.value?.modelVersion ?: 0
        )
        viewModelScope.launch {
            if (path != null) {
                _toastMessage.emit("Exported JSON to $path")
            } else {
                _toastMessage.emit("Export failed — no data recorded")
            }
        }
    }

    // --- Helpers ---

    /**
     * Resolve a substance index to its human-readable label.
     */
    fun getSubstanceLabel(index: Int): String {
        return _substanceLabels.value.getOrElse(index) { "Unknown (#$index)" }
    }

    // --- Cleanup ---

    override fun onCleared() {
        super.onCleared()
        usbManager.release()
    }
}

/**
 * Which classification model to display in the Results View.
 */
enum class ClassificationModel {
    KNN,
    NEAREST_CENTROID
}

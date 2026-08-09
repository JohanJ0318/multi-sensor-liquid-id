package com.group17.liquidsensor.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.group17.liquidsensor.data.*
import com.group17.liquidsensor.export.SessionLogger
import com.group17.liquidsensor.ble.PacketParser
import com.group17.liquidsensor.ble.BleSerialManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class DeviceViewModel(application: Application) : AndroidViewModel(application) {

    private companion object {
        /** START → FINAL normally takes ~6 s; past this, the link is stalled. */
        const val MEASUREMENT_RESPONSE_TIMEOUT_MS = 12_000L
    }

    // ── Public state ─────────────────────────────────────────────────────────

    private val _deviceState = MutableStateFlow(DeviceState())
    val deviceState: StateFlow<DeviceState> = _deviceState.asStateFlow()

    private val _latestRecord = MutableStateFlow<MeasurementRecord?>(null)
    val latestRecord: StateFlow<MeasurementRecord?> = _latestRecord.asStateFlow()

    /** Rolling history used by the Live Dashboard charts (last 20 points). */
    private val _liveReadings = MutableStateFlow(LiveReadings())
    val liveReadings: StateFlow<LiveReadings> = _liveReadings.asStateFlow()

    private val _sessionLoggingEnabled = MutableStateFlow(false)
    val sessionLoggingEnabled: StateFlow<Boolean> = _sessionLoggingEnabled.asStateFlow()

    /** One-shot toast/snackbar messages for the UI. */
    private val _uiMessage = MutableStateFlow<String?>(null)
    val uiMessage: StateFlow<String?> = _uiMessage.asStateFlow()

    private val _isDemoMode = MutableStateFlow(false)
    val isDemoMode: StateFlow<Boolean> = _isDemoMode.asStateFlow()

    private var demoJob: Job? = null

    // ── Internal ─────────────────────────────────────────────────────────────

    val sessionLogger = SessionLogger()
    private val parser = PacketParser()

    private val bleSerialManager = BleSerialManager(
        context = application,
        onPacketReceived = ::handlePacket,
        onConnectionChanged = { connected ->
            _deviceState.update {
                it.copy(connectionState = if (connected) ConnectionState.CONNECTED else ConnectionState.DISCONNECTED)
            }
        }
    )

    // Pending ACK tracking: commandCode → sentTime (ms)
    private val pendingAcks = mutableMapOf<Byte, Long>()

    /** Caller (MainActivity) must confirm BLE permissions are granted before invoking this. */
    fun connect() = bleSerialManager.tryConnect()

    // ── Packet handling ───────────────────────────────────────────────────────

    private fun handlePacket(packet: Packet) {
        viewModelScope.launch(Dispatchers.Main) {
            when (packet) {
                is Packet.StatusPacket -> {
                    _deviceState.update {
                        it.copy(
                            firmwareState = packet.firmwareState,
                            operatingMode = packet.operatingMode
                        )
                    }
                }

                is Packet.SubstanceLabelTable -> {
                    _deviceState.update { it.copy(substanceLabels = packet.labels) }
                }

                is Packet.MeasurementResultPacket -> {
                    // Data arrived: the link is alive, stand down the watchdog.
                    measurementWatchdog?.cancel()
                    // Prefer the device's label table when it exists; otherwise
                    // keep the name the parser already resolved (the text
                    // protocol carries indices that TextLineParser maps itself).
                    val labels = _deviceState.value.substanceLabels
                    val resolved = packet.record.copy(
                        knnPredictionName = labels[packet.record.knnPredictionIndex]
                            ?: packet.record.knnPredictionName,
                        ncPredictionName  = labels[packet.record.ncPredictionIndex]
                            ?: packet.record.ncPredictionName
                    )
                    if (!resolved.isHistorical) {
                        _latestRecord.value = resolved
                        if (resolved.validityStatus == ValidityStatus.OK) {
                            _liveReadings.update { r ->
                                r.copy(
                                    conductivityHistory = (r.conductivityHistory + resolved.conductivityFeature).takeLast(20),
                                    densityHistory      = (r.densityHistory      + resolved.densityFeature).takeLast(20),
                                    temperatureHistory  = (r.temperatureHistory  + resolved.temperatureFeature).takeLast(20)
                                )
                            }
                        }
                    }
                    if (_sessionLoggingEnabled.value) sessionLogger.addRecord(resolved)
                }

                is Packet.AcknowledgmentPacket -> {
                    pendingAcks.remove(packet.commandCode)
                }
            }
        }
    }

    // ── Commands ──────────────────────────────────────────────────────────────

    // Watchdog: a real device always answers START within ~6 s. If nothing
    // arrives, the notification pipe has silently died even though Android
    // still reports "connected" — force a reconnect to rebuild it.
    private var measurementWatchdog: Job? = null

    fun startMeasurement() {
        viewModelScope.launch(Dispatchers.IO) {
            bleSerialManager.send("START\n".toByteArray(Charsets.US_ASCII))
        }
        if (_isDemoMode.value) return
        measurementWatchdog?.cancel()
        measurementWatchdog = viewModelScope.launch {
            delay(MEASUREMENT_RESPONSE_TIMEOUT_MS)
            _uiMessage.value = "No response from device — reconnecting Bluetooth"
            bleSerialManager.forceReconnect()
        }
    }

    /** Ask the firmware to do a full software reboot (re-runs main from the top). */
    fun resetDevice() {
        viewModelScope.launch(Dispatchers.IO) {
            bleSerialManager.send("RESET\n".toByteArray(Charsets.US_ASCII))
        }
    }

    fun setMode(mode: OperatingMode) {
        val cmd = if (mode == OperatingMode.IDENTIFICATION)
            CommandCode.MODE_IDENTIFICATION else CommandCode.MODE_REFERENCE_COLLECTION
        sendCommandWithRetry(cmd)
    }

    fun requestExport() = sendCommandWithRetry(CommandCode.REQUEST_EXPORT)

    private fun sendCommandWithRetry(commandCode: Byte) {
        viewModelScope.launch(Dispatchers.IO) {
            val packet = parser.buildCommand(commandCode)
            pendingAcks[commandCode] = System.currentTimeMillis()
            bleSerialManager.send(packet)

            // Retry up to 3 times if no ACK within 500 ms (per spec)
            repeat(3) {
                delay(500)
                if (pendingAcks.containsKey(commandCode)) {
                    bleSerialManager.send(packet)
                } else {
                    return@launch  // ACK received, stop retrying
                }
            }
            // After 3 retries with no ACK, surface an error
            if (pendingAcks.remove(commandCode) != null) {
                viewModelScope.launch(Dispatchers.Main) {
                    _uiMessage.value = "Command 0x%02X: no acknowledgment after 3 retries".format(commandCode)
                }
            }
        }
    }

    // ── Demo mode ─────────────────────────────────────────────────────────────

    /**
     * Start demo mode: injects fake substance labels and emits a new
     * [MeasurementRecord] every 2 seconds without any USB connection.
     */
    fun startDemo() {
        if (_isDemoMode.value) return
        DemoDataGenerator.reset()
        _isDemoMode.value = true
        _deviceState.update {
            it.copy(
                connectionState = ConnectionState.CONNECTED,
                operatingMode   = OperatingMode.IDENTIFICATION,
                substanceLabels = DemoDataGenerator.substanceLabels
            )
        }
        demoJob = viewModelScope.launch {
            while (true) {
                delay(2000)
                val record = DemoDataGenerator.nextRecord()
                _latestRecord.value = record
                _liveReadings.update { r ->
                    r.copy(
                        conductivityHistory = (r.conductivityHistory + record.conductivityFeature).takeLast(20),
                        densityHistory      = (r.densityHistory      + record.densityFeature).takeLast(20),
                        temperatureHistory  = (r.temperatureHistory  + record.temperatureFeature).takeLast(20)
                    )
                }
                if (_sessionLoggingEnabled.value) sessionLogger.addRecord(record)
            }
        }
    }

    fun stopDemo() {
        demoJob?.cancel()
        demoJob = null
        _isDemoMode.value = false
        _latestRecord.value = null
        _liveReadings.value = LiveReadings()
        _deviceState.update {
            it.copy(
                connectionState = ConnectionState.DISCONNECTED,
                substanceLabels = emptyMap()
            )
        }
    }

    // ── Session logging / export ──────────────────────────────────────────────

    fun setSessionLogging(enabled: Boolean) {
        _sessionLoggingEnabled.value = enabled
        if (!enabled) sessionLogger.clear()
    }

    fun exportSessionCsv(): String = sessionLogger.exportCsv(_deviceState.value)
    fun exportSessionJson(): String = sessionLogger.exportJson(_deviceState.value)

    fun clearUiMessage() { _uiMessage.value = null }

    override fun onCleared() {
        super.onCleared()
        demoJob?.cancel()
        bleSerialManager.disconnect()
    }
}

/** Rolling sensor readings for the Dashboard charts. */
data class LiveReadings(
    val conductivityHistory: List<Float> = emptyList(),
    val densityHistory:      List<Float> = emptyList(),
    val temperatureHistory:  List<Float> = emptyList()
)

package com.group17.liquidid.data.model

/**
 * Represents a single measurement result received from the STM32 device.
 * Maps directly to Measurement Result Packet (Type 0x01) — 47-byte payload.
 */
data class MeasurementRecord(
    val recordNumber: Long,              // uint32 — MSB set = historical record
    val timestampMs: Long,               // uint32 — device uptime in ms
    val rawConductivityAdc: Float,       // float32 — raw ADC value
    val rawLoadCellAdc: Float,           // float32 — raw HX711 value
    val rawTemperature: Float,           // float32 — raw DS18B20 value
    val conductivityMsCm: Float,         // float32 — calibrated mS/cm
    val densityGMl: Float,              // float32 — calibrated g/mL
    val temperatureC: Float,            // float32 — calibrated °C
    val validityBitmask: Int,            // uint8 — bit0=cond, bit1=density, bit2=temp
    val knnPredictedIndex: Int,          // uint8 — substance index
    val knnConfidence: Float,            // float32 — 0.0–1.0
    val nearestCentroidIndex: Int,       // uint8 — substance index
    val nearestCentroidConfidence: Float, // float32 — 0.0–1.0
    val modelVersion: Int                // uint8 — parameter version ID
) {
    /** True if this record was replayed from flash (MSB of recordNumber set) */
    val isHistorical: Boolean get() = (recordNumber and 0x80000000L) != 0L

    val isConductivityValid: Boolean get() = (validityBitmask and 0x01) != 0
    val isDensityValid: Boolean get() = (validityBitmask and 0x02) != 0
    val isTemperatureValid: Boolean get() = (validityBitmask and 0x04) != 0
    val isAllValid: Boolean get() = isConductivityValid && isDensityValid && isTemperatureValid
}

/**
 * Device status received on connection (Status Packet, Type 0x03).
 */
data class DeviceStatus(
    val operatingMode: OperatingMode,
    val storedRecordCount: Int,
    val batteryVoltage: Float?,
    val modelVersion: Int
)

/**
 * Device operating modes.
 */
enum class OperatingMode(val code: Byte) {
    IDENTIFICATION(0x00),
    REFERENCE_COLLECTION(0x01);

    companion object {
        fun fromCode(code: Byte): OperatingMode =
            entries.firstOrNull { it.code == code } ?: IDENTIFICATION
    }
}

/**
 * Commands sent from the Android app to the device (Command Packet, Type 0x02).
 */
enum class DeviceCommand(val code: Byte) {
    START_MEASUREMENT(0x01),
    SET_MODE_IDENTIFICATION(0x02),
    SET_MODE_REFERENCE(0x03),
    REQUEST_EXPORT(0x04),
    REQUEST_STATUS(0x05)
}

/**
 * Connection state of the USB device.
 */
enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR
}

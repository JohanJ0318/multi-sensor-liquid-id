package com.group17.liquidsensor.data

/**
 * A single measurement result received from the device.
 * Fields map 1:1 to the Measurement Result Packet defined in the firmware (Table 7.5).
 */
data class MeasurementRecord(
    val recordNumber: Long,
    val timestamp: Long,          // milliseconds (converted from device seconds)
    val isHistorical: Boolean,    // true when MSB of record number is set (export replay)

    // Raw ADC / sensor values
    val conductivityRaw: Float,
    val densityRaw: Float,
    val temperatureRaw: Float,

    // Computed features after DSP / calibration
    val conductivityFeature: Float,   // mS/cm
    val densityFeature: Float,        // g/mL  (mass / known volume)
    val temperatureFeature: Float,    // °C

    // Validity
    val validityStatus: ValidityStatus,

    // k-Nearest Neighbors result
    val knnPredictionIndex: Int,
    val knnConfidence: Float,   // 0.0 – 1.0

    // Nearest-Centroid result
    val ncPredictionIndex: Int,
    val ncConfidence: Float,    // 0.0 – 1.0

    // Human-readable names resolved via SubstanceLabelTable
    val knnPredictionName: String = "Unknown",
    val ncPredictionName: String = "Unknown"
)

enum class ValidityStatus(val code: Int) {
    OK(0),
    PROBE_DISCONNECTED(1),
    OUT_OF_RANGE(2),
    UNSTABLE(3),
    UNKNOWN(-1);

    companion object {
        fun fromCode(code: Int): ValidityStatus =
            entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}

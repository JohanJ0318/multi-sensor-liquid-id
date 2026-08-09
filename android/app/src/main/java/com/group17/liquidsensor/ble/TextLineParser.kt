package com.group17.liquidsensor.ble

import com.group17.liquidsensor.data.MeasurementRecord
import com.group17.liquidsensor.data.Packet
import com.group17.liquidsensor.data.ValidityStatus

/**
 * Parses the firmware's plain-text output instead of the documented binary
 * packet protocol. Supports both firmware generations:
 *
 * SD2_Final (current, no on-device classification yet):
 *   "COND:1234 | WEIGHT:17.6 G | TEMP:23.50 C"
 * Legacy build:
 *   "T:-1000.0 C | W: 17.6 g | TDS:-0 | UNKNOWN"
 *
 * TODO: replace with [PacketParser]'s binary protocol once Frederick's
 * firmware implements the real Measurement Result Packet format. Until then:
 *   - WEIGHT/"W" (grams) is mapped into the density slot (g/mL) as the
 *     closest available field — it is NOT a true density reading.
 *   - COND (raw ADC counts) / "TDS" is mapped into the conductivity slot
 *     (mS/cm) as the closest available field — not a calibrated reading.
 *   - SD2_Final sends no classification, so predictions are "Unknown" with
 *     zero confidence; the legacy single name fills both model slots.
 */
class TextLineParser {

    // SD2_Final firmware, all variants:
    //   streaming:   "COND:%u | WEIGHT:%.1f G | TEMP:%.2f C"
    //   one-shot:    "FINAL | COND:%u | WEIGHT:%.1f G | TEMP:%.2f C | VALID:%u"
    //   calibrated:  "... | MSCM:%.3f | ... | VALID:%u"
    //   classifying: "... | VALID:%u | KNN:%u,%.2f | NC:%u,%.2f"
    // MSCM (real mS/cm, temperature-compensated) is used as the conductivity
    // feature when present; raw ADC counts stay in the raw slot. KNN/NC carry
    // the on-device predictions: class index (255 = invalid) and confidence.
    private val condLineRegex = Regex(
        """COND:\s*(-?\d+\.?\d*)\s*(?:\|\s*MSCM:\s*(-?\d+\.?\d*)\s*)?\|\s*WEIGHT:\s*(-?\d+\.?\d*)\s*G\s*\|\s*TEMP:\s*(-?\d+\.?\d*)\s*C(?:\s*\|\s*VALID:\s*(\d))?(?:\s*\|\s*KNN:\s*(\d+)\s*,\s*(-?[\d.]+)\s*\|\s*NC:\s*(\d+)\s*,\s*(-?[\d.]+))?""",
        RegexOption.IGNORE_CASE
    )

    // Legacy firmware: T:.. C | W:.. g | TDS:.. | NAME
    private val legacyLineRegex = Regex(
        """T:\s*(-?\d+\.?\d*)\s*C\s*\|\s*W:\s*(-?\d+\.?\d*)\s*g\s*\|\s*TDS:\s*(-?\d+\.?\d*)\s*\|\s*(.+)"""
    )

    private companion object {
        // Firmware's ADC validity ceiling (see main.c COND_ADC_MAX_VALID).
        // No floor: near-zero conductivity is valid (low-conductivity liquids).
        const val COND_ADC_MAX_VALID = 4050f
        const val TEMP_SENTINEL_C = -999f

        // Display names by class index — MUST match CLASS_NAMES order in
        // model_params.h (training labels sort alphabetically).
        val CLASS_NAMES = listOf("Coke", "Distilled Water", "Glycerin", "Isopropyl", "Salt Water", "Tap Water")
    }

    private val lineBuffer = StringBuilder()
    private var recordNumber = 0L

    /** Feed raw bytes from the BLE characteristic; returns any complete lines parsed. */
    fun feed(bytes: ByteArray): List<Packet> {
        lineBuffer.append(String(bytes, Charsets.US_ASCII))
        val results = mutableListOf<Packet>()

        var newlineIdx = lineBuffer.indexOf("\n")
        while (newlineIdx >= 0) {
            val line = lineBuffer.substring(0, newlineIdx).trim()
            lineBuffer.delete(0, newlineIdx + 1)
            parseLine(line)?.let { results += it }
            newlineIdx = lineBuffer.indexOf("\n")
        }
        return results
    }

    private fun parseLine(line: String): Packet? {
        condLineRegex.find(line)?.let { match ->
            val g = match.groupValues
            val cond        = g[1].toFloatOrNull() ?: return null
            val weight      = g[3].toFloatOrNull() ?: return null
            val temperature = g[4].toFloatOrNull() ?: return null
            // Calibrated conductivity: the wire carries mS/cm (MSCM field),
            // the app displays and stores µS/cm, so convert here. Absent on
            // older firmware, in which case raw counts double as the feature.
            val microScm    = g[2].toFloatOrNull()?.times(1000f)
            // Device's own combined validity flag (one-shot format only);
            // empty string when the streaming format omits it.
            val deviceValid = g[5].toIntOrNull()
            // On-device predictions (classifying firmware only). 255 = the
            // device measured invalid data and declined to classify.
            val knnIdx  = g[6].toIntOrNull() ?: 255
            val knnConf = g[7].toFloatOrNull() ?: 0f
            val ncIdx   = g[8].toIntOrNull() ?: 255
            val ncConf  = g[9].toFloatOrNull() ?: 0f

            // No lower conductivity bound: near-zero is a real reading for
            // isopropyl / distilled water, which are identification classes.
            val validity = when {
                temperature <= TEMP_SENTINEL_C -> ValidityStatus.PROBE_DISCONNECTED
                cond >= COND_ADC_MAX_VALID -> ValidityStatus.OUT_OF_RANGE
                deviceValid == 0 -> ValidityStatus.OUT_OF_RANGE
                else -> ValidityStatus.OK
            }

            return buildRecord(
                conductivityRaw     = cond,
                conductivityFeature = microScm ?: cond,
                weight              = weight,
                temperature         = temperature,
                validity            = validity,
                knnIndex            = knnIdx,
                knnName             = CLASS_NAMES.getOrNull(knnIdx) ?: "Unknown",
                knnConfidence       = knnConf,
                ncIndex             = ncIdx,
                ncName              = CLASS_NAMES.getOrNull(ncIdx) ?: "Unknown",
                ncConfidence        = ncConf
            )
        }

        legacyLineRegex.find(line)?.let { match ->
            val (tempStr, weightStr, tdsStr, name) = match.destructured
            val temperature   = tempStr.toFloatOrNull() ?: return null
            val weight        = weightStr.toFloatOrNull() ?: return null
            val tds           = tdsStr.toFloatOrNull() ?: return null
            val substanceName = name.trim()
            val isUnknown     = substanceName.equals("UNKNOWN", ignoreCase = true)

            // The firmware's sentinel for "no probe reading" — distinct from an
            // unmatched substance classification, which is a normal/valid
            // outcome and shouldn't be treated as a bad sensor reading.
            val isProbeError = temperature <= TEMP_SENTINEL_C

            val legacyConf = if (isUnknown) 0f else 1f
            return buildRecord(
                conductivityRaw     = tds,
                conductivityFeature = tds,
                weight              = weight,
                temperature         = temperature,
                validity            = if (isProbeError) ValidityStatus.PROBE_DISCONNECTED
                                      else ValidityStatus.OK,
                knnIndex            = 0,
                knnName             = substanceName,
                knnConfidence       = legacyConf,
                ncIndex             = 0,
                ncName              = substanceName,
                ncConfidence        = legacyConf
            )
        }

        return null
    }

    private fun buildRecord(
        conductivityRaw: Float,
        conductivityFeature: Float,
        weight: Float,
        temperature: Float,
        validity: ValidityStatus,
        knnIndex: Int,
        knnName: String,
        knnConfidence: Float,
        ncIndex: Int,
        ncName: String,
        ncConfidence: Float
    ): Packet.MeasurementResultPacket {
        recordNumber++
        return Packet.MeasurementResultPacket(
            MeasurementRecord(
                recordNumber        = recordNumber,
                timestamp           = System.currentTimeMillis(),
                isHistorical        = false,
                conductivityRaw     = conductivityRaw,
                densityRaw          = weight,
                temperatureRaw      = temperature,
                conductivityFeature = conductivityFeature,
                densityFeature      = weight,
                temperatureFeature  = temperature,
                validityStatus      = validity,
                knnPredictionIndex  = knnIndex,
                knnConfidence       = knnConfidence,
                ncPredictionIndex   = ncIndex,
                ncConfidence        = ncConfidence,
                knnPredictionName   = knnName,
                ncPredictionName    = ncName
            )
        )
    }
}

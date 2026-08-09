package com.group17.liquidsensor.export

import com.group17.liquidsensor.data.DeviceState
import com.group17.liquidsensor.data.MeasurementRecord
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Records measurement results in memory and exports them to CSV or JSON.
 *
 * Export files include a metadata header so the data is self-describing
 * when analysed separately on a PC.
 */
class SessionLogger {

    private val records = mutableListOf<MeasurementRecord>()
    private val sessionStartMs = System.currentTimeMillis()

    fun addRecord(record: MeasurementRecord) { records.add(record) }

    fun clear() { records.clear() }

    fun recordCount(): Int = records.size

    // ── CSV export ────────────────────────────────────────────────────────────

    fun exportCsv(deviceState: DeviceState): String = buildString {
        appendLine("# LiquidID Session Export")
        appendLine("# Session Start: ${formatTs(sessionStartMs)}")
        appendLine("# Substance Labels: ${deviceState.substanceLabels.entries.joinToString("|") { "${it.key}=${it.value}" }}")
        appendLine("# Total Records: ${records.size}")
        appendLine(CSV_HEADER)
        records.forEach { r ->
            appendLine(
                "${r.recordNumber},${formatTs(r.timestamp)}," +
                "${r.conductivityRaw},${r.densityRaw},${r.temperatureRaw}," +
                "${r.conductivityFeature},${r.densityFeature},${r.temperatureFeature}," +
                "${r.validityStatus.name}," +
                "${r.knnPredictionIndex},${r.knnPredictionName},${r.knnConfidence}," +
                "${r.ncPredictionIndex},${r.ncPredictionName},${r.ncConfidence}"
            )
        }
    }

    // ── JSON export ───────────────────────────────────────────────────────────

    fun exportJson(deviceState: DeviceState): String {
        val root = JSONObject().apply {
            put("session_start", formatTs(sessionStartMs))
            put("substance_labels",
                JSONObject(deviceState.substanceLabels.mapKeys { it.key.toString() }))
            put("total_records", records.size)
            put("records", JSONArray().also { arr ->
                records.forEach { r ->
                    arr.put(JSONObject().apply {
                        put("record_number",        r.recordNumber)
                        put("timestamp",            formatTs(r.timestamp))
                        put("conductivity_raw",     r.conductivityRaw)
                        put("density_raw",          r.densityRaw)
                        put("temperature_raw",      r.temperatureRaw)
                        put("conductivity_feature", r.conductivityFeature)
                        put("density_feature",      r.densityFeature)
                        put("temperature_feature",  r.temperatureFeature)
                        put("validity",             r.validityStatus.name)
                        put("knn_prediction_index", r.knnPredictionIndex)
                        put("knn_prediction_name",  r.knnPredictionName)
                        put("knn_confidence",       r.knnConfidence)
                        put("nc_prediction_index",  r.ncPredictionIndex)
                        put("nc_prediction_name",   r.ncPredictionName)
                        put("nc_confidence",        r.ncConfidence)
                    })
                }
            })
        }
        return root.toString(2)
    }

    private fun formatTs(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).format(Date(ms))

    companion object {
        private const val CSV_HEADER =
            "record_number,timestamp," +
            "conductivity_raw,density_raw,temperature_raw," +
            "conductivity_feature,density_feature,temperature_feature," +
            "validity," +
            "knn_prediction_index,knn_prediction_name,knn_confidence," +
            "nc_prediction_index,nc_prediction_name,nc_confidence"
    }
}

package com.group17.liquidid.data.repository

import android.content.Context
import android.os.Environment
import com.group17.liquidid.data.model.MeasurementRecord
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Manages session recording and export of measurement data.
 *
 * When logging is enabled, every MeasurementRecord received from the device
 * is stored in-memory. The user can export the session as CSV or JSON to
 * the device's shared storage (Downloads).
 */
class SessionLogger(private val context: Context) {

    private val records = mutableListOf<MeasurementRecord>()
    private var sessionStartTime: Long = 0L
    private var _isLogging = false

    val isLogging: Boolean get() = _isLogging
    val recordCount: Int get() = records.size

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)

    /**
     * Start recording incoming measurements.
     */
    fun startSession() {
        records.clear()
        sessionStartTime = System.currentTimeMillis()
        _isLogging = true
    }

    /**
     * Stop recording.
     */
    fun stopSession() {
        _isLogging = false
    }

    /**
     * Append a record (called by ViewModel when a measurement arrives).
     */
    fun addRecord(record: MeasurementRecord) {
        if (_isLogging) {
            records.add(record)
        }
    }

    /**
     * Export the current session as CSV.
     * @return The file path of the exported file, or null on failure.
     */
    fun exportCsv(substanceLabels: List<String>, modelVersion: Int): String? {
        if (records.isEmpty()) return null

        val fileName = "liquid_id_session_${dateFormat.format(Date(sessionStartTime))}.csv"
        val file = getExportFile(fileName) ?: return null

        try {
            FileWriter(file).use { writer ->
                // Metadata header (commented)
                writer.write("# Session Start: ${Date(sessionStartTime)}\n")
                writer.write("# Model Version: $modelVersion\n")
                writer.write("# Substances: ${substanceLabels.joinToString(", ")}\n")
                writer.write("# Record Count: ${records.size}\n")

                // Column headers
                writer.write("record_number,timestamp_ms,")
                writer.write("raw_conductivity_adc,raw_load_cell_adc,raw_temperature,")
                writer.write("conductivity_ms_cm,density_g_ml,temperature_c,")
                writer.write("validity_bitmask,")
                writer.write("knn_predicted_index,knn_predicted_label,knn_confidence,")
                writer.write("nc_predicted_index,nc_predicted_label,nc_confidence,")
                writer.write("model_version\n")

                // Data rows
                for (r in records) {
                    val knnLabel = substanceLabels.getOrElse(r.knnPredictedIndex) { "Unknown" }
                    val ncLabel = substanceLabels.getOrElse(r.nearestCentroidIndex) { "Unknown" }

                    writer.write("${r.recordNumber},${r.timestampMs},")
                    writer.write("${r.rawConductivityAdc},${r.rawLoadCellAdc},${r.rawTemperature},")
                    writer.write("${r.conductivityMsCm},${r.densityGMl},${r.temperatureC},")
                    writer.write("${r.validityBitmask},")
                    writer.write("${r.knnPredictedIndex},$knnLabel,${r.knnConfidence},")
                    writer.write("${r.nearestCentroidIndex},$ncLabel,${r.nearestCentroidConfidence},")
                    writer.write("${r.modelVersion}\n")
                }
            }
            return file.absolutePath
        } catch (e: Exception) {
            return null
        }
    }

    /**
     * Export the current session as JSON.
     */
    fun exportJson(substanceLabels: List<String>, modelVersion: Int): String? {
        if (records.isEmpty()) return null

        val fileName = "liquid_id_session_${dateFormat.format(Date(sessionStartTime))}.json"
        val file = getExportFile(fileName) ?: return null

        try {
            val root = JSONObject().apply {
                put("session_start", Date(sessionStartTime).toString())
                put("model_version", modelVersion)
                put("substance_labels", JSONArray(substanceLabels))
                put("record_count", records.size)

                val recordsArray = JSONArray()
                for (r in records) {
                    recordsArray.put(JSONObject().apply {
                        put("record_number", r.recordNumber)
                        put("timestamp_ms", r.timestampMs)
                        put("raw_conductivity_adc", r.rawConductivityAdc.toDouble())
                        put("raw_load_cell_adc", r.rawLoadCellAdc.toDouble())
                        put("raw_temperature", r.rawTemperature.toDouble())
                        put("conductivity_ms_cm", r.conductivityMsCm.toDouble())
                        put("density_g_ml", r.densityGMl.toDouble())
                        put("temperature_c", r.temperatureC.toDouble())
                        put("validity_bitmask", r.validityBitmask)
                        put("knn_predicted_index", r.knnPredictedIndex)
                        put("knn_predicted_label",
                            substanceLabels.getOrElse(r.knnPredictedIndex) { "Unknown" })
                        put("knn_confidence", r.knnConfidence.toDouble())
                        put("nc_predicted_index", r.nearestCentroidIndex)
                        put("nc_predicted_label",
                            substanceLabels.getOrElse(r.nearestCentroidIndex) { "Unknown" })
                        put("nc_confidence", r.nearestCentroidConfidence.toDouble())
                        put("model_version", r.modelVersion)
                    })
                }
                put("records", recordsArray)
            }

            FileWriter(file).use { it.write(root.toString(2)) }
            return file.absolutePath
        } catch (e: Exception) {
            return null
        }
    }

    /**
     * Clear all recorded data.
     */
    fun clear() {
        records.clear()
        _isLogging = false
    }

    private fun getExportFile(fileName: String): File? {
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!dir.exists()) dir.mkdirs()
        return File(dir, fileName)
    }
}

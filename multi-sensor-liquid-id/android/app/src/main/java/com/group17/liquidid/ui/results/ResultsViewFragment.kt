package com.group17.liquidid.ui.results

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.ToggleButton
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.group17.liquidid.R
import com.group17.liquidid.data.model.MeasurementRecord
import com.group17.liquidid.viewmodel.ClassificationModel
import com.group17.liquidid.viewmodel.DeviceViewModel
import kotlinx.coroutines.launch

/**
 * Results View Screen — displays the most recent identification outcome.
 *
 * Center: predicted substance name (large text) + circular confidence gauge.
 * Below: individual feature values used for classification.
 * Top: toggle to switch between kNN and nearest-centroid results.
 */
class ResultsViewFragment : Fragment() {

    private val viewModel: DeviceViewModel by activityViewModels()

    private lateinit var tvSubstanceName: TextView
    private lateinit var tvConfidencePercent: TextView
    private lateinit var progressConfidence: ProgressBar
    private lateinit var tvModelLabel: TextView
    private lateinit var toggleModel: ToggleButton
    private lateinit var tvConductivityFeature: TextView
    private lateinit var tvDensityFeature: TextView
    private lateinit var tvTemperatureFeature: TextView
    private lateinit var tvStatusMessage: TextView

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_results, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        tvSubstanceName = view.findViewById(R.id.tv_substance_name)
        tvConfidencePercent = view.findViewById(R.id.tv_confidence_percent)
        progressConfidence = view.findViewById(R.id.progress_confidence)
        tvModelLabel = view.findViewById(R.id.tv_model_label)
        toggleModel = view.findViewById(R.id.toggle_model)
        tvConductivityFeature = view.findViewById(R.id.tv_feature_conductivity)
        tvDensityFeature = view.findViewById(R.id.tv_feature_density)
        tvTemperatureFeature = view.findViewById(R.id.tv_feature_temperature)
        tvStatusMessage = view.findViewById(R.id.tv_status_message)

        // Toggle between kNN and nearest-centroid
        toggleModel.setOnCheckedChangeListener { _, isChecked ->
            val model = if (isChecked) ClassificationModel.NEAREST_CENTROID
                        else ClassificationModel.KNN
            viewModel.selectModel(model)
        }

        // Observe selected model
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.selectedModel.collect { model ->
                tvModelLabel.text = when (model) {
                    ClassificationModel.KNN -> "k-Nearest Neighbors"
                    ClassificationModel.NEAREST_CENTROID -> "Nearest Centroid"
                }
            }
        }

        // Observe latest measurement
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.latestMeasurement.collect { record ->
                if (record != null) {
                    updateResult(record)
                } else {
                    showWaitingState()
                }
            }
        }
    }

    private fun updateResult(record: MeasurementRecord) {
        val model = viewModel.selectedModel.value

        val predictedIndex: Int
        val confidence: Float

        when (model) {
            ClassificationModel.KNN -> {
                predictedIndex = record.knnPredictedIndex
                confidence = record.knnConfidence
            }
            ClassificationModel.NEAREST_CENTROID -> {
                predictedIndex = record.nearestCentroidIndex
                confidence = record.nearestCentroidConfidence
            }
        }

        if (!record.isAllValid) {
            tvSubstanceName.text = "Invalid"
            tvSubstanceName.setTextColor(Color.parseColor("#FF5722"))
            tvStatusMessage.visibility = View.VISIBLE
            tvStatusMessage.text = buildInvalidMessage(record)
            progressConfidence.progress = 0
            tvConfidencePercent.text = "—"
        } else {
            val label = viewModel.getSubstanceLabel(predictedIndex)
            tvSubstanceName.text = label
            tvSubstanceName.setTextColor(Color.parseColor("#212121"))

            val confidencePercent = (confidence * 100).toInt()
            tvConfidencePercent.text = "${confidencePercent}%"
            progressConfidence.progress = confidencePercent

            tvStatusMessage.visibility = View.GONE
        }

        // Feature values
        tvConductivityFeature.text = String.format("Conductivity: %.2f mS/cm", record.conductivityMsCm)
        tvDensityFeature.text = String.format("Density: %.4f g/mL", record.densityGMl)
        tvTemperatureFeature.text = String.format("Temperature: %.1f °C", record.temperatureC)
    }

    private fun showWaitingState() {
        tvSubstanceName.text = "Waiting…"
        tvSubstanceName.setTextColor(Color.GRAY)
        tvConfidencePercent.text = "—"
        progressConfidence.progress = 0
        tvConductivityFeature.text = "Conductivity: —"
        tvDensityFeature.text = "Density: —"
        tvTemperatureFeature.text = "Temperature: —"
        tvStatusMessage.visibility = View.VISIBLE
        tvStatusMessage.text = "Press the start button to begin a measurement"
    }

    private fun buildInvalidMessage(record: MeasurementRecord): String {
        val issues = mutableListOf<String>()
        if (!record.isConductivityValid) issues.add("Conductivity probe error")
        if (!record.isDensityValid) issues.add("Load cell reading unstable")
        if (!record.isTemperatureValid) issues.add("Temperature out of range")
        return issues.joinToString("\n")
    }
}

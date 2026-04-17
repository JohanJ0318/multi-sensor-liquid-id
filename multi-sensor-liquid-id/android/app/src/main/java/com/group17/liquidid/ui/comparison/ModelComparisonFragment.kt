package com.group17.liquidid.ui.comparison

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter
import com.group17.liquidid.R
import com.group17.liquidid.data.model.MeasurementRecord
import com.group17.liquidid.viewmodel.DeviceViewModel
import kotlinx.coroutines.launch

/**
 * Model Comparison Screen — displays kNN and nearest-centroid results side by side.
 *
 * Two panels showing each model's prediction and confidence.
 * Agreement/disagreement banner at top.
 * Normalized feature vector bar chart at bottom.
 */
class ModelComparisonFragment : Fragment() {

    private val viewModel: DeviceViewModel by activityViewModels()

    // kNN panel
    private lateinit var tvKnnSubstance: TextView
    private lateinit var tvKnnConfidence: TextView
    private lateinit var progressKnn: ProgressBar

    // Nearest-centroid panel
    private lateinit var tvNcSubstance: TextView
    private lateinit var tvNcConfidence: TextView
    private lateinit var progressNc: ProgressBar

    // Agreement banner
    private lateinit var tvAgreementBanner: TextView

    // Feature bar chart
    private lateinit var chartFeatures: BarChart

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_comparison, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Bind views
        tvKnnSubstance = view.findViewById(R.id.tv_knn_substance)
        tvKnnConfidence = view.findViewById(R.id.tv_knn_confidence)
        progressKnn = view.findViewById(R.id.progress_knn)

        tvNcSubstance = view.findViewById(R.id.tv_nc_substance)
        tvNcConfidence = view.findViewById(R.id.tv_nc_confidence)
        progressNc = view.findViewById(R.id.progress_nc)

        tvAgreementBanner = view.findViewById(R.id.tv_agreement_banner)
        chartFeatures = view.findViewById(R.id.chart_features)

        configureFeatureChart()

        // Observe latest measurement
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.latestMeasurement.collect { record ->
                if (record != null) {
                    updateComparison(record)
                } else {
                    showWaitingState()
                }
            }
        }
    }

    private fun updateComparison(record: MeasurementRecord) {
        val knnLabel = viewModel.getSubstanceLabel(record.knnPredictedIndex)
        val ncLabel = viewModel.getSubstanceLabel(record.nearestCentroidIndex)

        // kNN panel
        tvKnnSubstance.text = knnLabel
        val knnPercent = (record.knnConfidence * 100).toInt()
        tvKnnConfidence.text = "$knnPercent%"
        progressKnn.progress = knnPercent

        // Nearest-centroid panel
        tvNcSubstance.text = ncLabel
        val ncPercent = (record.nearestCentroidConfidence * 100).toInt()
        tvNcConfidence.text = "$ncPercent%"
        progressNc.progress = ncPercent

        // Agreement banner
        val modelsAgree = record.knnPredictedIndex == record.nearestCentroidIndex
        if (modelsAgree) {
            tvAgreementBanner.text = "✓ Models Agree"
            tvAgreementBanner.setBackgroundColor(Color.parseColor("#E8F5E9"))
            tvAgreementBanner.setTextColor(Color.parseColor("#2E7D32"))
        } else {
            tvAgreementBanner.text = "⚠ Models Disagree"
            tvAgreementBanner.setBackgroundColor(Color.parseColor("#FFF3E0"))
            tvAgreementBanner.setTextColor(Color.parseColor("#E65100"))
        }

        // Feature bar chart — show normalized feature values
        updateFeatureChart(record)
    }

    private fun updateFeatureChart(record: MeasurementRecord) {
        // Normalize features to 0–1 range for visualization
        // Using reasonable reference ranges for each sensor
        val normConductivity = (record.conductivityMsCm / 100f).coerceIn(0f, 1f)
        val normDensity = ((record.densityGMl - 0.5f) / 1.5f).coerceIn(0f, 1f)
        val normTemperature = (record.temperatureC / 100f).coerceIn(0f, 1f)

        val entries = listOf(
            BarEntry(0f, normConductivity),
            BarEntry(1f, normDensity),
            BarEntry(2f, normTemperature)
        )

        val dataSet = BarDataSet(entries, "Feature Vector").apply {
            colors = listOf(
                Color.parseColor("#00BCD4"),  // Conductivity — Cyan
                Color.parseColor("#FFEB3B"),  // Density — Yellow
                Color.parseColor("#4CAF50")   // Temperature — Green
            )
            setDrawValues(true)
            valueTextSize = 12f
        }

        chartFeatures.data = BarData(dataSet).apply {
            barWidth = 0.6f
        }
        chartFeatures.invalidate()
    }

    private fun showWaitingState() {
        tvKnnSubstance.text = "—"
        tvKnnConfidence.text = "—"
        progressKnn.progress = 0
        tvNcSubstance.text = "—"
        tvNcConfidence.text = "—"
        progressNc.progress = 0
        tvAgreementBanner.text = "Waiting for measurement…"
        tvAgreementBanner.setBackgroundColor(Color.parseColor("#EEEEEE"))
        tvAgreementBanner.setTextColor(Color.GRAY)
    }

    private fun configureFeatureChart() {
        chartFeatures.apply {
            description.isEnabled = false
            setTouchEnabled(false)
            setDrawGridBackground(false)
            legend.isEnabled = false

            xAxis.apply {
                position = XAxis.XAxisPosition.BOTTOM
                setDrawGridLines(false)
                granularity = 1f
                valueFormatter = IndexAxisValueFormatter(
                    arrayOf("Conductivity", "Density", "Temperature")
                )
            }

            axisLeft.apply {
                axisMinimum = 0f
                axisMaximum = 1f
                setDrawGridLines(true)
                gridColor = Color.parseColor("#E0E0E0")
            }

            axisRight.isEnabled = false
            setFitBars(true)
        }
    }
}

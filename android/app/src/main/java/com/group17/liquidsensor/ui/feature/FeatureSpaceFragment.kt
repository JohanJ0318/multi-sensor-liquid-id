package com.group17.liquidsensor.ui.feature

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.graphics.ColorUtils
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.github.mikephil.charting.charts.ScatterChart
import com.github.mikephil.charting.components.AxisBase
import com.github.mikephil.charting.components.Legend
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.ScatterData
import com.github.mikephil.charting.data.ScatterDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import com.github.mikephil.charting.interfaces.datasets.IScatterDataSet
import com.group17.liquidsensor.data.DemoDataGenerator
import com.group17.liquidsensor.data.MeasurementRecord
import com.group17.liquidsensor.databinding.FragmentFeatureSpaceBinding
import com.group17.liquidsensor.viewmodel.DeviceViewModel
import kotlinx.coroutines.launch
import kotlin.math.log10
import kotlin.math.pow
import kotlin.random.Random

class FeatureSpaceFragment : Fragment() {

    private var _binding: FragmentFeatureSpaceBinding? = null
    private val binding get() = _binding!!

    private val viewModel: DeviceViewModel by activityViewModels()

    // One color per substance; indices match DemoDataGenerator.clusters
    // (which follow the model's class order in model_params.h)
    private val substanceColors = intArrayOf(
        Color.parseColor("#B71C1C"),  // Coke            — dark red
        Color.parseColor("#00BCD4"),  // Distilled Water — cyan
        Color.parseColor("#FFEB3B"),  // Glycerin        — yellow
        Color.parseColor("#9C27B0"),  // Isopropyl       — purple
        Color.parseColor("#FF9800"),  // Salt Water      — orange
        Color.parseColor("#2196F3"),  // Tap Water       — blue
    )

    // Pre-generated reference noise points (regenerated once per view lifecycle)
    private var clusterEntries: List<List<Entry>>? = null

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFeatureSpaceBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupChart()
        buildClusterEntries()

        // Show reference clusters immediately (no measurement yet)
        rebuildChart(record = null)

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.latestRecord.collect { record ->
                rebuildChart(record)
                if (record != null) updateExplanation(record)

                binding.cardNoData.visibility   = if (record == null) View.VISIBLE else View.GONE
                binding.cardExplanation.visibility = if (record == null) View.GONE else View.VISIBLE
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ── Chart setup ───────────────────────────────────────────────────────────

    private fun setupChart() {
        val clusters = DemoDataGenerator.clusters
        val logConds = clusters.map { log10(it.conductivity.toDouble()).toFloat() }
        val dens     = clusters.map { it.density }

        with(binding.chartFeatureSpace) {
            description.isEnabled = false
            setDrawGridBackground(false)
            setBackgroundColor(Color.TRANSPARENT)

            legend.apply {
                isEnabled   = true
                textColor   = Color.parseColor("#CCCCCC")
                form        = Legend.LegendForm.CIRCLE
                formSize    = 9f
                textSize    = 11f
                orientation = Legend.LegendOrientation.HORIZONTAL
                verticalAlignment   = Legend.LegendVerticalAlignment.BOTTOM
                horizontalAlignment = Legend.LegendHorizontalAlignment.CENTER
                isWordWrapEnabled   = true
            }

            xAxis.apply {
                position            = XAxis.XAxisPosition.BOTTOM
                textColor           = Color.parseColor("#AAAAAA")
                gridColor           = Color.parseColor("#333333")
                setDrawGridLines(true)
                labelCount          = 5
                valueFormatter      = LogConductivityFormatter()
                labelRotationAngle  = -30f
                axisMinimum         = logConds.min() - 0.7f
                axisMaximum         = logConds.max() + 0.7f
            }

            axisLeft.apply {
                textColor      = Color.parseColor("#AAAAAA")
                gridColor      = Color.parseColor("#333333")
                setDrawGridLines(true)
                valueFormatter = DensityFormatter()
                axisMinimum    = dens.min() - 0.08f
                axisMaximum    = dens.max() + 0.08f
            }

            axisRight.isEnabled         = false
            setTouchEnabled(true)
            isDragEnabled               = true
            setScaleEnabled(true)
            isDoubleTapToZoomEnabled    = false
        }
    }

    // ── Cluster data generation ───────────────────────────────────────────────

    /** Generate noisy reference cloud points once and reuse them across renders. */
    private fun buildClusterEntries() {
        clusterEntries = DemoDataGenerator.clusters.map { cluster ->
            val logCond = log10(cluster.conductivity.toDouble()).toFloat()
            List(30) {
                // Sum of two uniforms → triangular distribution ≈ Gaussian
                val nx = (Random.nextFloat() + Random.nextFloat() - 1f) * 0.18f  // ±0.18 log units
                val ny = (Random.nextFloat() + Random.nextFloat() - 1f) * 0.007f // ±0.007 g/mL
                Entry(logCond + nx, cluster.density + ny)
            }
        }
    }

    // ── Chart update ─────────────────────────────────────────────────────────

    private fun rebuildChart(record: MeasurementRecord?) {
        val entries     = clusterEntries ?: return
        val predictedIdx = record?.knnPredictionIndex ?: -1
        val dataSets    = mutableListOf<IScatterDataSet>()

        DemoDataGenerator.clusters.forEachIndexed { i, cluster ->
            val isPredicted = i == predictedIdx
            val baseColor   = substanceColors[i % substanceColors.size]
            val color       = if (isPredicted || record == null)
                baseColor
            else
                ColorUtils.setAlphaComponent(baseColor, 90)

            val ds = ScatterDataSet(entries[i], cluster.name).apply {
                setColor(color)
                setScatterShapeSize(if (isPredicted) 16f else 8f)
                setScatterShape(ScatterChart.ScatterShape.CIRCLE)
                setDrawValues(false)
                setDrawIcons(false)
            }
            dataSets.add(ds)
        }

        if (record != null) {
            val safeConf = record.conductivityFeature.coerceAtLeast(1e-5f)
            val logCond  = log10(safeConf.toDouble()).toFloat()

            val measurementDs = ScatterDataSet(
                listOf(Entry(logCond, record.densityFeature)), "Your Sample"
            ).apply {
                setColor(Color.YELLOW)
                setScatterShapeSize(22f)
                setScatterShape(ScatterChart.ScatterShape.CROSS)
                setDrawValues(false)
                setDrawIcons(false)
            }
            dataSets.add(measurementDs)

            binding.tvTemperatureNote.text =
                "T = %.1f°C  (affects conductivity & density)".format(record.temperatureFeature)
        }

        binding.chartFeatureSpace.data = ScatterData(dataSets)
        binding.chartFeatureSpace.invalidate()
    }

    // ── Explanation card ──────────────────────────────────────────────────────

    private fun updateExplanation(record: MeasurementRecord) {
        val cluster  = DemoDataGenerator.clusters.getOrNull(record.knnPredictionIndex)
        val knnConf  = (record.knnConfidence * 100).toInt()
        val ncConf   = (record.ncConfidence  * 100).toInt()

        binding.tvMatchSubstance.text =
            "${record.knnPredictionName}  ·  kNN $knnConf%  |  NC $ncConf%"

        val safeCond  = record.conductivityFeature.coerceAtLeast(1e-5f)
        val logSample = log10(safeCond.toDouble())
        binding.tvMatchSensors.text =
            "Conductivity : %.5f µS/cm  →  log₁₀ = %.2f\nMass         : %.4f g"
                .format(record.conductivityFeature, logSample, record.densityFeature)

        if (cluster != null) {
            val logRef = log10(cluster.conductivity.toDouble())
            binding.tvMatchWhy.text =
                "Conductivity : %.5f µS/cm  →  log₁₀ = %.2f\nMass         : %.4f g"
                    .format(cluster.conductivity, logRef, cluster.density)
        }
    }

    // ── Axis formatters ───────────────────────────────────────────────────────

    private inner class LogConductivityFormatter : ValueFormatter() {
        override fun getAxisLabel(value: Float, axis: AxisBase?): String {
            val v = 10.0.pow(value.toDouble())
            return when {
                v < 0.001  -> "%.4f".format(v)
                v < 0.01   -> "%.3f".format(v)
                v < 0.1    -> "%.3f".format(v)
                v < 1.0    -> "%.2f".format(v)
                v < 10.0   -> "%.1f".format(v)
                else       -> "%.0f".format(v)
            }
        }
    }

    private inner class DensityFormatter : ValueFormatter() {
        override fun getAxisLabel(value: Float, axis: AxisBase?): String =
            "%.3f".format(value)
    }
}

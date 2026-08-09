package com.group17.liquidsensor.ui.comparison

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter
import com.group17.liquidsensor.R
import com.group17.liquidsensor.data.MeasurementRecord
import com.group17.liquidsensor.data.ValidityStatus
import com.group17.liquidsensor.databinding.FragmentComparisonBinding
import com.group17.liquidsensor.viewmodel.DeviceViewModel
import kotlinx.coroutines.launch

class ModelComparisonFragment : Fragment() {

    private var _binding: FragmentComparisonBinding? = null
    private val binding get() = _binding!!
    private val viewModel: DeviceViewModel by activityViewModels()

    // Normalisation ranges — adjust these to the actual measurement ranges
    // once calibration data from the device is available.
    private val MAX_CONDUCTIVITY = 20000f  // µS/cm
    private val MAX_DENSITY      = 200f    // grams — the density slot carries weight (g) until firmware sends true g/mL
    private val MAX_TEMPERATURE  = 50f     // °C upper bound for display

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentComparisonBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupBarChart()

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.latestRecord.collect { record ->
                if (record == null) showEmpty() else updateComparison(record)
            }
        }
    }

    private fun setupBarChart() {
        binding.chartFeatures.apply {
            description.isEnabled = false
            legend.isEnabled      = false
            axisRight.isEnabled   = false
            axisLeft.apply {
                axisMinimum = 0f
                axisMaximum = 1f
                textColor   = Color.WHITE
                textSize    = 10f
            }
            xAxis.apply {
                valueFormatter = IndexAxisValueFormatter(
                    listOf("Conductivity", "Mass", "Temperature")
                )
                granularity      = 1f
                textColor        = Color.WHITE
                textSize         = 10f
            }
            setTouchEnabled(false)
            setBackgroundColor(Color.TRANSPARENT)
        }
    }

    private fun showEmpty() {
        binding.bannerAgreement.text            = getString(R.string.no_data)
        binding.bannerAgreement.setBackgroundColor(requireContext().getColor(R.color.color_card))
        binding.tvKnnPrediction.text            = "—"
        binding.tvKnnConfidence.text            = "—%"
        binding.tvNcPrediction.text             = "—"
        binding.tvNcConfidence.text             = "—%"
        binding.chartFeatures.clear()
    }

    private fun updateComparison(record: MeasurementRecord) {
        // ── Model panels ──
        binding.tvKnnPrediction.text = record.knnPredictionName
        binding.tvKnnConfidence.text = "%.1f%%".format(record.knnConfidence * 100)
        binding.tvNcPrediction.text  = record.ncPredictionName
        binding.tvNcConfidence.text  = "%.1f%%".format(record.ncConfidence * 100)

        // ── Agreement banner ──
        val valid = record.validityStatus == ValidityStatus.OK
        val agree = valid && record.knnPredictionName == record.ncPredictionName
        binding.bannerAgreement.text = if (valid) {
            if (agree) getString(R.string.models_agree) else getString(R.string.models_disagree)
        } else {
            record.validityStatus.name
        }
        binding.bannerAgreement.setBackgroundColor(
            when {
                !valid -> requireContext().getColor(R.color.color_warning)
                agree  -> requireContext().getColor(R.color.color_agree)
                else   -> requireContext().getColor(R.color.color_disagree)
            }
        )

        // ── Normalised feature bar chart ──
        val normCond = (record.conductivityFeature / MAX_CONDUCTIVITY).coerceIn(0f, 1f)
        val normDens = (record.densityFeature      / MAX_DENSITY).coerceIn(0f, 1f)
        val normTemp = (record.temperatureFeature  / MAX_TEMPERATURE).coerceIn(0f, 1f)

        val entries = listOf(
            BarEntry(0f, normCond),
            BarEntry(1f, normDens),
            BarEntry(2f, normTemp)
        )
        val dataSet = BarDataSet(entries, "Features").apply {
            colors = listOf(
                Color.CYAN,
                Color.GREEN,
                0xFFFF6D00.toInt()
            )
            setDrawValues(true)
            valueTextColor = Color.WHITE
            valueTextSize  = 10f
        }
        binding.chartFeatures.data = BarData(dataSet).apply { barWidth = 0.5f }
        binding.chartFeatures.invalidate()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

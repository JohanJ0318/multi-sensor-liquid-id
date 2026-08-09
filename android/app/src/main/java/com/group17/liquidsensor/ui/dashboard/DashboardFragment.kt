package com.group17.liquidsensor.ui.dashboard

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.group17.liquidsensor.R
import com.group17.liquidsensor.data.ConnectionState
import com.group17.liquidsensor.data.OperatingMode
import com.group17.liquidsensor.data.ValidityStatus
import com.group17.liquidsensor.databinding.FragmentDashboardBinding
import com.group17.liquidsensor.viewmodel.DeviceViewModel
import kotlinx.coroutines.launch

class DashboardFragment : Fragment() {

    private var _binding: FragmentDashboardBinding? = null
    private val binding get() = _binding!!
    private val viewModel: DeviceViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDashboardBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupCharts()
        binding.fabMeasure.setOnClickListener { viewModel.startMeasurement() }
        binding.fabReset.setOnClickListener { viewModel.resetDevice() }
        observeViewModel()
    }

    // ── Chart setup ───────────────────────────────────────────────────────────

    private fun setupCharts() {
        listOf(
            binding.chartConductivity,
            binding.chartDensity,
            binding.chartTemperature
        ).forEach { chart ->
            chart.description.isEnabled = false
            chart.legend.isEnabled      = false
            chart.setTouchEnabled(false)
            chart.axisRight.isEnabled   = false
            chart.xAxis.isEnabled       = false
            chart.axisLeft.textColor    = Color.WHITE
            chart.axisLeft.textSize     = 9f
            chart.setBackgroundColor(Color.TRANSPARENT)
            chart.setNoDataText("Waiting for data…")
            chart.setNoDataTextColor(Color.GRAY)
        }
    }

    private fun updateChart(chart: LineChart, history: List<Float>, lineColor: Int) {
        if (history.isEmpty()) { chart.clear(); return }
        val entries  = history.mapIndexed { i, v -> Entry(i.toFloat(), v) }
        val dataSet  = LineDataSet(entries, "").apply {
            color = lineColor
            setDrawCircles(false)
            setDrawValues(false)
            lineWidth = 2f
            mode      = LineDataSet.Mode.CUBIC_BEZIER
            setDrawFilled(true)
            fillAlpha = 40
            fillColor = lineColor
        }
        chart.data = LineData(dataSet)
        chart.notifyDataSetChanged()
        chart.invalidate()
    }

    // ── State observation ─────────────────────────────────────────────────────

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.liveReadings.collect { readings ->
                updateChart(binding.chartConductivity, readings.conductivityHistory, Color.CYAN)
                updateChart(binding.chartDensity,      readings.densityHistory,      Color.GREEN)
                updateChart(binding.chartTemperature,  readings.temperatureHistory,  0xFFFF6D00.toInt())
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.latestRecord.collect { record ->
                record ?: return@collect

                binding.tvConductivityValue.text = "%.1f µS/cm".format(record.conductivityFeature)
                binding.tvDensityValue.text      = "%.4f g".format(record.densityFeature)
                binding.tvTemperatureValue.text  = "%.2f °C".format(record.temperatureFeature)

                val warningBg = requireContext().getColor(R.color.color_warning)
                val cardBg    = requireContext().getColor(R.color.color_card)

                fun tint(card: androidx.cardview.widget.CardView, warn: Boolean) {
                    card.setCardBackgroundColor(if (warn) warningBg else cardBg)
                }

                val isInvalid = record.validityStatus != ValidityStatus.OK
                tint(binding.cardConductivity, isInvalid)
                tint(binding.cardDensity,      isInvalid)
                tint(binding.cardTemperature,  isInvalid)

                binding.tvValidityStatus.text = when (record.validityStatus) {
                    ValidityStatus.OK                -> "OK"
                    ValidityStatus.PROBE_DISCONNECTED -> "Probe disconnected"
                    ValidityStatus.OUT_OF_RANGE      -> "Reading out of range"
                    ValidityStatus.UNSTABLE          -> "Unstable"
                    ValidityStatus.UNKNOWN           -> "Unknown error"
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.deviceState.collect { state ->
                binding.tvConnectionStatus.text = when (state.connectionState) {
                    ConnectionState.CONNECTED    -> getString(R.string.status_connected)
                    ConnectionState.DISCONNECTED -> getString(R.string.status_disconnected)
                    ConnectionState.CONNECTING   -> getString(R.string.status_connecting)
                    ConnectionState.ERROR        -> getString(R.string.status_error)
                }
                binding.tvOperatingMode.text = when (state.operatingMode) {
                    OperatingMode.IDENTIFICATION      -> getString(R.string.mode_identification)
                    OperatingMode.REFERENCE_COLLECTION -> getString(R.string.mode_reference)
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

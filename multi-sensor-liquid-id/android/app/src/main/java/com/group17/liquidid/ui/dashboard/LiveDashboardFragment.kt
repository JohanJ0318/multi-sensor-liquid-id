package com.group17.liquidid.ui.dashboard

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.group17.liquidid.R
import com.group17.liquidid.data.model.ConnectionState
import com.group17.liquidid.data.model.MeasurementRecord
import com.group17.liquidid.viewmodel.DeviceViewModel
import kotlinx.coroutines.launch

/**
 * Live Dashboard Screen — displays real-time sensor readings with scrolling line charts.
 *
 * Layout: three vertical sensor sections (Conductivity, Density, Temperature), each
 * showing the current value with unit and a scrolling chart of the last 20 readings.
 * A status bar at the bottom shows connection state, mode, and validity.
 */
class LiveDashboardFragment : Fragment() {

    private val viewModel: DeviceViewModel by activityViewModels()

    // UI references
    private lateinit var tvConductivityValue: TextView
    private lateinit var tvDensityValue: TextView
    private lateinit var tvTemperatureValue: TextView
    private lateinit var chartConductivity: LineChart
    private lateinit var chartDensity: LineChart
    private lateinit var chartTemperature: LineChart
    private lateinit var tvConnectionStatus: TextView
    private lateinit var tvDeviceMode: TextView
    private lateinit var tvValidity: TextView

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_dashboard, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Bind views
        tvConductivityValue = view.findViewById(R.id.tv_conductivity_value)
        tvDensityValue = view.findViewById(R.id.tv_density_value)
        tvTemperatureValue = view.findViewById(R.id.tv_temperature_value)
        chartConductivity = view.findViewById(R.id.chart_conductivity)
        chartDensity = view.findViewById(R.id.chart_density)
        chartTemperature = view.findViewById(R.id.chart_temperature)
        tvConnectionStatus = view.findViewById(R.id.tv_connection_status)
        tvDeviceMode = view.findViewById(R.id.tv_device_mode)
        tvValidity = view.findViewById(R.id.tv_validity)

        // Configure charts
        configureChart(chartConductivity, "Conductivity (mS/cm)", COLOR_CONDUCTIVITY)
        configureChart(chartDensity, "Density (g/mL)", COLOR_DENSITY)
        configureChart(chartTemperature, "Temperature (°C)", COLOR_TEMPERATURE)

        // Observe data
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.latestMeasurement.collect { record ->
                record?.let { updateCurrentValues(it) }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.measurementHistory.collect { history ->
                updateCharts(history)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.connectionState.collect { state ->
                updateConnectionStatus(state)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.deviceStatus.collect { status ->
                status?.let {
                    tvDeviceMode.text = "Mode: ${it.operatingMode.name}"
                }
            }
        }
    }

    private fun updateCurrentValues(record: MeasurementRecord) {
        tvConductivityValue.text = String.format("%.2f mS/cm", record.conductivityMsCm)
        tvDensityValue.text = String.format("%.4f g/mL", record.densityGMl)
        tvTemperatureValue.text = String.format("%.1f °C", record.temperatureC)

        // Validity indicators
        val validityParts = mutableListOf<String>()
        if (!record.isConductivityValid) validityParts.add("Cond: INVALID")
        if (!record.isDensityValid) validityParts.add("Dens: INVALID")
        if (!record.isTemperatureValid) validityParts.add("Temp: INVALID")

        if (validityParts.isEmpty()) {
            tvValidity.text = "All sensors OK"
            tvValidity.setTextColor(Color.parseColor("#4CAF50"))
        } else {
            tvValidity.text = validityParts.joinToString(" | ")
            tvValidity.setTextColor(Color.parseColor("#FF5722"))
        }
    }

    private fun updateCharts(history: List<MeasurementRecord>) {
        updateSingleChart(chartConductivity,
            history.mapIndexed { i, r -> Entry(i.toFloat(), r.conductivityMsCm) },
            "Conductivity", COLOR_CONDUCTIVITY)

        updateSingleChart(chartDensity,
            history.mapIndexed { i, r -> Entry(i.toFloat(), r.densityGMl) },
            "Density", COLOR_DENSITY)

        updateSingleChart(chartTemperature,
            history.mapIndexed { i, r -> Entry(i.toFloat(), r.temperatureC) },
            "Temperature", COLOR_TEMPERATURE)
    }

    private fun updateSingleChart(chart: LineChart, entries: List<Entry>, label: String, color: Int) {
        val dataSet = LineDataSet(entries, label).apply {
            this.color = color
            lineWidth = 2.5f
            setDrawCircles(true)
            circleRadius = 3f
            setCircleColor(color)
            setDrawValues(false)
            mode = LineDataSet.Mode.CUBIC_BEZIER
            setDrawFilled(true)
            fillColor = color
            fillAlpha = 30
        }
        chart.data = LineData(dataSet)
        chart.invalidate()
    }

    private fun updateConnectionStatus(state: ConnectionState) {
        tvConnectionStatus.text = when (state) {
            ConnectionState.CONNECTED -> "● Connected"
            ConnectionState.CONNECTING -> "◌ Connecting..."
            ConnectionState.DISCONNECTED -> "○ Disconnected"
            ConnectionState.ERROR -> "✕ Error"
        }
        tvConnectionStatus.setTextColor(when (state) {
            ConnectionState.CONNECTED -> Color.parseColor("#4CAF50")
            ConnectionState.CONNECTING -> Color.parseColor("#FFC107")
            ConnectionState.DISCONNECTED -> Color.GRAY
            ConnectionState.ERROR -> Color.parseColor("#FF5722")
        })
    }

    private fun configureChart(chart: LineChart, label: String, color: Int) {
        chart.apply {
            description.isEnabled = false
            setTouchEnabled(true)
            isDragEnabled = true
            setScaleEnabled(false)
            setPinchZoom(false)
            setDrawGridBackground(false)
            legend.isEnabled = false

            xAxis.apply {
                position = XAxis.XAxisPosition.BOTTOM
                setDrawGridLines(false)
                granularity = 1f
                setDrawLabels(false)
            }

            axisLeft.apply {
                setDrawGridLines(true)
                gridColor = Color.parseColor("#E0E0E0")
            }

            axisRight.isEnabled = false

            // Initialize with empty data
            data = LineData()
        }
    }

    companion object {
        private val COLOR_CONDUCTIVITY = Color.parseColor("#00BCD4") // Cyan
        private val COLOR_DENSITY = Color.parseColor("#FFEB3B")      // Yellow
        private val COLOR_TEMPERATURE = Color.parseColor("#4CAF50")  // Green
    }
}

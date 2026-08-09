package com.group17.liquidsensor.ui.results

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.group17.liquidsensor.R
import com.group17.liquidsensor.data.ValidityStatus
import com.group17.liquidsensor.databinding.FragmentResultsBinding
import com.group17.liquidsensor.viewmodel.DeviceViewModel
import kotlinx.coroutines.launch

class ResultsFragment : Fragment() {

    private var _binding: FragmentResultsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: DeviceViewModel by activityViewModels()

    /** true = showing kNN, false = showing Nearest Centroid */
    private var showingKnn = true

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentResultsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Toggle model selection
        binding.toggleModel.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                showingKnn = (checkedId == R.id.btn_knn)
                updateDisplay()
            }
        }
        // Ensure kNN starts selected
        binding.btnKnn.isChecked = true

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.latestRecord.collect { updateDisplay() }
        }
    }

    private fun updateDisplay() {
        val record = viewModel.latestRecord.value

        if (record == null) {
            binding.tvSubstanceName.text     = getString(R.string.no_data)
            binding.tvConfidencePercent.text = "—"
            binding.progressConfidence.progress = 0
            binding.tvModelLabel.text        = if (showingKnn) getString(R.string.model_knn)
                                               else getString(R.string.model_nc)
            binding.tvConductivityResult.text = "Conductivity: —"
            binding.tvDensityResult.text      = "Mass: —"
            binding.tvTemperatureResult.text  = "Temperature: —"
            return
        }

        binding.tvModelLabel.text = if (showingKnn) getString(R.string.model_knn)
                                    else getString(R.string.model_nc)

        val (name, confidence) = if (showingKnn) {
            record.knnPredictionName to record.knnConfidence
        } else {
            record.ncPredictionName  to record.ncConfidence
        }

        when (record.validityStatus) {
            ValidityStatus.OK -> {
                binding.tvSubstanceName.text  = name
                val pct = (confidence * 100).toInt().coerceIn(0, 100)
                binding.tvConfidencePercent.text    = "$pct%"
                binding.progressConfidence.progress = pct
            }
            ValidityStatus.PROBE_DISCONNECTED -> {
                binding.tvSubstanceName.text        = "Probe Disconnected"
                binding.tvConfidencePercent.text    = "N/A"
                binding.progressConfidence.progress = 0
            }
            else -> {
                binding.tvSubstanceName.text        = record.validityStatus.name
                binding.tvConfidencePercent.text    = "N/A"
                binding.progressConfidence.progress = 0
            }
        }

        binding.tvConductivityResult.text = "Conductivity: %.4f µS/cm".format(record.conductivityFeature)
        binding.tvDensityResult.text      = "Mass: %.4f g".format(record.densityFeature)
        binding.tvTemperatureResult.text  = "Temperature: %.2f °C".format(record.temperatureFeature)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

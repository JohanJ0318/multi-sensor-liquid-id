package com.group17.liquidid

import android.os.Bundle
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.group17.liquidid.data.model.ConnectionState
import com.group17.liquidid.ui.comparison.ModelComparisonFragment
import com.group17.liquidid.ui.dashboard.LiveDashboardFragment
import com.group17.liquidid.ui.results.ResultsViewFragment
import com.group17.liquidid.viewmodel.DeviceViewModel
import kotlinx.coroutines.launch

/**
 * Main Activity — hosts three fragments via bottom navigation:
 *   1. Live Dashboard (real-time sensor charts)
 *   2. Results View (identification outcome)
 *   3. Model Comparison (kNN vs nearest-centroid side-by-side)
 *
 * Also manages the USB connection lifecycle and the FAB for triggering measurements.
 */
class MainActivity : AppCompatActivity() {

    private val viewModel: DeviceViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        setupBottomNavigation()
        setupFab()
        observeState()

        // Auto-connect on launch
        viewModel.connectDevice()
    }

    private fun setupBottomNavigation() {
        val navView = findViewById<BottomNavigationView>(R.id.bottom_navigation)

        // Load default fragment
        if (supportFragmentManager.findFragmentById(R.id.fragment_container) == null) {
            replaceFragment(LiveDashboardFragment())
        }

        navView.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_dashboard -> {
                    replaceFragment(LiveDashboardFragment())
                    true
                }
                R.id.nav_results -> {
                    replaceFragment(ResultsViewFragment())
                    true
                }
                R.id.nav_comparison -> {
                    replaceFragment(ModelComparisonFragment())
                    true
                }
                else -> false
            }
        }
    }

    private fun setupFab() {
        val fab = findViewById<FloatingActionButton>(R.id.fab_start_measurement)
        fab.setOnClickListener {
            if (viewModel.connectionState.value == ConnectionState.CONNECTED) {
                viewModel.startMeasurement()
            } else {
                Toast.makeText(this, "Device not connected", Toast.LENGTH_SHORT).show()
                viewModel.connectDevice()
            }
        }
    }

    private fun observeState() {
        lifecycleScope.launch {
            viewModel.connectionState.collect { state ->
                val fab = findViewById<FloatingActionButton>(R.id.fab_start_measurement)
                when (state) {
                    ConnectionState.CONNECTED -> {
                        fab.isEnabled = true
                        // Request initial status + label table
                        viewModel.requestStatus()
                    }
                    ConnectionState.CONNECTING -> {
                        fab.isEnabled = false
                    }
                    ConnectionState.DISCONNECTED -> {
                        fab.isEnabled = false
                    }
                    ConnectionState.ERROR -> {
                        fab.isEnabled = false
                        Toast.makeText(this@MainActivity,
                            "USB connection error", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        lifecycleScope.launch {
            viewModel.toastMessage.collect { message ->
                Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun replaceFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .commit()
    }

    override fun onDestroy() {
        super.onDestroy()
        viewModel.disconnectDevice()
    }
}

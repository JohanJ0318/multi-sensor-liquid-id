package com.group17.liquidsensor

import android.os.Bundle
import android.os.Environment
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.group17.liquidsensor.ble.BleSerialManager
import com.group17.liquidsensor.databinding.ActivityMainBinding
import com.group17.liquidsensor.viewmodel.DeviceViewModel
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: DeviceViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            viewModel.connect()
        } else {
            Toast.makeText(this, "Bluetooth permission denied — can't connect to the RN4871", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Set toolbar
        setSupportActionBar(binding.toolbar)

        // Wire bottom navigation to NavController
        val navHost = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        binding.bottomNav.setupWithNavController(navHost.navController)

        // Observe one-shot UI messages (command errors)
        lifecycleScope.launch {
            viewModel.uiMessage.collect { msg ->
                msg ?: return@collect
                Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
                viewModel.clearUiMessage()
            }
        }

        // Show / hide the demo banner
        lifecycleScope.launch {
            viewModel.isDemoMode.collect { isDemo ->
                binding.bannerDemo.visibility =
                    if (isDemo) android.view.View.VISIBLE else android.view.View.GONE
            }
        }

        connectWithPermissionCheck()
    }

    /** Request BLE scan/connect permissions if needed, then connect to the RN4871. */
    private fun connectWithPermissionCheck() {
        if (BleSerialManager.hasRequiredPermissions(this)) {
            viewModel.connect()
        } else {
            permissionLauncher.launch(BleSerialManager.requiredPermissions())
        }
    }

    // ── Options menu (session logging + export) ───────────────────────────────

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.action_demo_mode)?.isChecked    = viewModel.isDemoMode.value
        menu.findItem(R.id.action_toggle_logging)?.isChecked = viewModel.sessionLoggingEnabled.value
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_demo_mode -> {
            val enable = !item.isChecked
            item.isChecked = enable
            if (enable) viewModel.startDemo() else viewModel.stopDemo()
            Toast.makeText(this,
                if (enable) "Demo mode ON — fake data every 2 s"
                else        "Demo mode OFF",
                Toast.LENGTH_SHORT).show()
            true
        }
        R.id.action_toggle_logging -> {
            val newState = !item.isChecked
            item.isChecked = newState
            viewModel.setSessionLogging(newState)
            val msg = if (newState) "Session logging started" else "Session logging stopped"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            true
        }
        R.id.action_export_csv -> {
            exportToFile("csv") { viewModel.exportSessionCsv() }
            true
        }
        R.id.action_export_json -> {
            exportToFile("json") { viewModel.exportSessionJson() }
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    private fun exportToFile(extension: String, content: () -> String) {
        if (viewModel.sessionLogger.recordCount() == 0) {
            Toast.makeText(this, "No records to export (enable session logging first)", Toast.LENGTH_SHORT).show()
            return
        }
        val timestamp = System.currentTimeMillis()
        val fileName  = "liquidid_session_$timestamp.$extension"
        val dir       = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
            ?: filesDir
        val file = File(dir, fileName)
        file.writeText(content())
        Toast.makeText(this, "Exported to ${file.absolutePath}", Toast.LENGTH_LONG).show()
    }
}

package nl.utwente.doomscroll

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import nl.utwente.doomscroll.model.PauseReason
import nl.utwente.doomscroll.service.EventLoggerHolder
import nl.utwente.doomscroll.service.SensorLoggingService
import nl.utwente.doomscroll.storage.ExportManager
import nl.utwente.doomscroll.util.Preferences
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Preferences

    private lateinit var textParticipantId: TextView
    private lateinit var textStatus: TextView
    private lateinit var btnToggle: Button
    private lateinit var btnUsageStats: Button
    private lateinit var btnAccessibility: Button
    private lateinit var btnNotifications: Button
    private lateinit var btnBattery: Button
    private lateinit var btnPause: Button
    private lateinit var btnExport: Button
    private lateinit var textExportResult: TextView
    private lateinit var bannerAccessibility: View
    private lateinit var btnFixAccessibility: Button

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { updateUI() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = Preferences(this)
        if (prefs.participantId == null) {
            prefs.participantId = UUID.randomUUID().toString()
        }

        textParticipantId = findViewById(R.id.text_participant_id)
        textStatus = findViewById(R.id.text_status)
        btnToggle = findViewById(R.id.btn_toggle_logging)
        btnUsageStats = findViewById(R.id.btn_usage_stats)
        btnAccessibility = findViewById(R.id.btn_accessibility)
        btnNotifications = findViewById(R.id.btn_notifications)
        btnBattery = findViewById(R.id.btn_battery)
        btnPause = findViewById(R.id.btn_pause)
        btnExport = findViewById(R.id.btn_export)
        textExportResult = findViewById(R.id.text_export_result)
        bannerAccessibility = findViewById(R.id.banner_accessibility_warning)
        btnFixAccessibility = findViewById(R.id.btn_fix_accessibility)

        btnUsageStats.setOnClickListener {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }

        btnAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        btnNotifications.setOnClickListener {
            if (Build.VERSION.SDK_INT >= 33) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        btnBattery.setOnClickListener {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            intent.data = Uri.parse("package:$packageName")
            startActivity(intent)
        }

        btnToggle.setOnClickListener {
            if (prefs.loggingEnabled) {
                // Ignore single tap when logging — require long-press to stop
                return@setOnClickListener
            }
            startLogging()
            updateUI()
        }
        btnToggle.setOnLongClickListener {
            if (prefs.loggingEnabled) {
                stopLogging()
                updateUI()
            }
            true
        }

        btnPause.setOnClickListener {
            val logger = EventLoggerHolder.logger ?: return@setOnClickListener
            if (logger.isPaused) {
                kotlinx.coroutines.runBlocking { logger.resume(PauseReason.USER_REQUEST) }
            } else {
                kotlinx.coroutines.runBlocking { logger.pause(PauseReason.USER_REQUEST) }
            }
            updateUI()
        }

        btnFixAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        btnExport.setOnClickListener {
            startExport()
        }
    }

    override fun onResume() {
        super.onResume()
        updateUI()
    }

    private fun startLogging() {
        val pid = prefs.participantId ?: return
        prefs.loggingEnabled = true
        SensorLoggingService.start(this, pid)
    }

    private fun stopLogging() {
        prefs.loggingEnabled = false
        SensorLoggingService.stop(this)
    }

    private fun startExport() {
        btnExport.isEnabled = false
        textExportResult.text = "Preparing export…"

        // Stop logging first so the active file is cleanly closed before we read it.
        if (prefs.loggingEnabled) {
            stopLogging()
            updateUI()
        }

        // Run I/O off the main thread. Wait up to 3 s for the service to finish closing.
        Thread {
            var waited = 0
            while (EventLoggerHolder.logger != null && waited < 3_000) {
                Thread.sleep(100)
                waited += 100
            }

            val result = ExportManager(this).export()

            runOnUiThread {
                textExportResult.text = formatExportResult(result)
                btnExport.isEnabled = true
            }
        }.start()
    }

    private fun formatExportResult(result: ExportManager.ExportResult): String = when {
        result.exported == 0 && result.skipped == 0 ->
            "No log files found."
        result.exported == 0 && result.error != null ->
            "Export failed: ${result.error}"
        else -> buildString {
            append("Exported ${result.exported} file(s)")
            if (result.skipped > 0) append(", skipped ${result.skipped}")
            append("\n${formatBytes(result.totalBytes)}")
            result.path?.let { append("\nPath: $it") }
            if (result.error != null) append("\n⚠ Partial failure: ${result.error}")
        }
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes < 1_024 -> "$bytes B"
        bytes < 1_048_576 -> "%.1f KB".format(bytes / 1_024.0)
        else -> "%.1f MB".format(bytes / 1_048_576.0)
    }

    private fun updateUI() {
        textParticipantId.text = "Participant ID: ${prefs.participantId ?: "—"}"

        val usageOk = hasUsageStatsPermission()
        val accessOk = isAccessibilityServiceEnabled()
        val notifOk = if (Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
        } else true
        val batteryOk = isBatteryOptimizationDisabled()

        btnUsageStats.text = if (usageOk) "✓ Usage Access granted" else "1. Grant Usage Access"
        btnAccessibility.text = if (accessOk) "✓ Accessibility enabled" else "2. Enable Accessibility Service"
        btnNotifications.text = if (notifOk) "✓ Notifications allowed" else "3. Allow Notifications"
        btnBattery.text = if (batteryOk) "✓ Battery unrestricted" else "4. Disable Battery Optimization"

        val allGranted = usageOk && accessOk && notifOk && batteryOk
        btnToggle.isEnabled = allGranted

        // Accessibility warning banner — shown when logging is active but the service died
        bannerAccessibility.visibility =
            if (prefs.loggingEnabled && !accessOk) View.VISIBLE else View.GONE

        val logger = EventLoggerHolder.logger
        if (prefs.loggingEnabled) {
            if (logger?.isPaused == true) {
                textStatus.text = "Status: PAUSED by participant"
                btnPause.text = "Resume Logging"
            } else {
                textStatus.text = "Status: LOGGING ACTIVE"
                btnPause.text = "Pause Logging"
            }
            btnToggle.text = "Long-press to Stop"
            btnPause.visibility = View.VISIBLE
        } else {
            textStatus.text = if (allGranted) "Status: ready to start" else "Status: grant all permissions first"
            btnToggle.text = "Start Logging"
            btnPause.visibility = View.GONE
        }
    }

    private fun hasUsageStatsPermission(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabled = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_GENERIC)
        return enabled.any {
            it.resolveInfo.serviceInfo.packageName == packageName
        }
    }

    private fun isBatteryOptimizationDisabled(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }
}

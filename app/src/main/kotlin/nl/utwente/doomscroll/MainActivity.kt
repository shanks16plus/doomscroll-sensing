package nl.utwente.doomscroll

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
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

    // Header
    private lateinit var textParticipantId: TextView

    // Status card
    private lateinit var cardStatus: View
    private lateinit var pulseRing1: View
    private lateinit var pulseRing2: View
    private lateinit var pulseRing3: View
    private lateinit var statusDot: View
    private lateinit var textStatusLabel: TextView
    private lateinit var textStatus: TextView

    // Permission tiles
    private lateinit var btnUsageStats: View
    private lateinit var btnAccessibility: View
    private lateinit var btnNotifications: View
    private lateinit var btnBattery: View
    private lateinit var btnNotificationListener: View

    // Permission chips
    private lateinit var chipUsageStats: TextView
    private lateinit var chipAccessibility: TextView
    private lateinit var chipNotifications: TextView
    private lateinit var chipBattery: TextView
    private lateinit var chipNotificationListener: TextView

    // Accessibility warning
    private lateinit var bannerAccessibility: View
    private lateinit var btnFixAccessibility: Button

    // Actions
    private lateinit var btnToggle: Button
    private lateinit var btnPause: Button

    // Export
    private lateinit var btnExport: Button
    private lateinit var textExportResult: TextView

    // Pulse animation handles
    private val pulseAnimators = mutableListOf<ValueAnimator>()

    // True when the last export stopped an active logging session.
    private var exportStoppedLogging = false

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { updateUI() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = Preferences(this)
        if (prefs.participantId == null) {
            prefs.participantId = UUID.randomUUID().toString()
        }

        // Header
        textParticipantId = findViewById(R.id.text_participant_id)

        // Status card
        cardStatus       = findViewById(R.id.card_status)
        pulseRing1       = findViewById(R.id.pulse_ring_1)
        pulseRing2       = findViewById(R.id.pulse_ring_2)
        pulseRing3       = findViewById(R.id.pulse_ring_3)
        statusDot        = findViewById(R.id.status_dot)
        textStatusLabel  = findViewById(R.id.text_status_label)
        textStatus       = findViewById(R.id.text_status)

        // Permission tiles
        btnUsageStats            = findViewById(R.id.btn_usage_stats)
        btnAccessibility         = findViewById(R.id.btn_accessibility)
        btnNotifications         = findViewById(R.id.btn_notifications)
        btnBattery               = findViewById(R.id.btn_battery)
        btnNotificationListener  = findViewById(R.id.btn_notification_listener)

        // Permission chips
        chipUsageStats           = findViewById(R.id.chip_usage_stats)
        chipAccessibility        = findViewById(R.id.chip_accessibility)
        chipNotifications        = findViewById(R.id.chip_notifications)
        chipBattery              = findViewById(R.id.chip_battery)
        chipNotificationListener = findViewById(R.id.chip_notification_listener)

        // Accessibility warning
        bannerAccessibility = findViewById(R.id.banner_accessibility_warning)
        btnFixAccessibility = findViewById(R.id.btn_fix_accessibility)

        // Actions
        btnToggle = findViewById(R.id.btn_toggle_logging)
        btnPause  = findViewById(R.id.btn_pause)

        // Export
        btnExport        = findViewById(R.id.btn_export)
        textExportResult = findViewById(R.id.text_export_result)

        // ── Listeners ─────────────────────────────────────────────────────────

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
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
            )
        }

        btnNotificationListener.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        btnToggle.setOnClickListener {
            if (prefs.loggingEnabled) return@setOnClickListener   // require long-press to stop
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

        btnExport.setOnClickListener { startExport() }
    }

    override fun onResume() {
        super.onResume()
        updateUI()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopPulse()
    }

    // ── Logging control ───────────────────────────────────────────────────────

    private fun startLogging() {
        val pid = prefs.participantId ?: return
        exportStoppedLogging = false
        prefs.loggingEnabled = true
        SensorLoggingService.start(this, pid)
    }

    private fun stopLogging() {
        prefs.loggingEnabled = false
        SensorLoggingService.stop(this)
    }

    // ── Export ────────────────────────────────────────────────────────────────

    private fun startExport() {
        if (prefs.loggingEnabled) {
            AlertDialog.Builder(this)
                .setTitle("Export data")
                .setMessage(
                    "This will stop logging temporarily. " +
                    "After export completes, tap Resume Logging to continue " +
                    "if you're continuing the study."
                )
                .setPositiveButton("Export") { _, _ -> doExport(wasLogging = true) }
                .setNegativeButton("Cancel", null)
                .show()
        } else {
            doExport(wasLogging = false)
        }
    }

    private fun doExport(wasLogging: Boolean) {
        btnExport.isEnabled = false
        textExportResult.text = "Preparing export…"

        if (wasLogging) {
            stopLogging()
            exportStoppedLogging = true
            updateUI()
        }

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
                updateUI()
            }
        }.start()
    }

    private fun formatExportResult(result: ExportManager.ExportResult): String = when {
        result.exported == 0 && result.skipped == 0 ->
            "No log files found."
        result.exported == 0 && result.skipped > 0 ->
            "All ${result.skipped} file(s) failed to decrypt — they may be from a previous install. Delete and re-run logging."
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
        bytes < 1_024       -> "$bytes B"
        bytes < 1_048_576   -> "%.1f KB".format(bytes / 1_024.0)
        else                -> "%.1f MB".format(bytes / 1_048_576.0)
    }

    // ── UI update ─────────────────────────────────────────────────────────────

    private fun updateUI() {
        val pid = prefs.participantId ?: "—"
        // Show only last 8 chars so the pill stays compact
        textParticipantId.text = "ID  ${pid.takeLast(8).uppercase()}"

        val usageOk          = hasUsageStatsPermission()
        val accessOk         = isAccessibilityServiceEnabled()
        val notifOk          = if (Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
        } else true
        val batteryOk        = isBatteryOptimizationDisabled()
        val notifListenerOk  = isNotificationListenerEnabled()

        updatePermChip(chipUsageStats,           usageOk)
        updatePermChip(chipAccessibility,        accessOk)
        updatePermChip(chipNotifications,        notifOk)
        updatePermChip(chipBattery,              batteryOk)
        updatePermChip(chipNotificationListener, notifListenerOk)

        val allGranted = usageOk && accessOk && notifOk && batteryOk && notifListenerOk
        btnToggle.isEnabled = allGranted

        // Accessibility warning banner
        bannerAccessibility.visibility =
            if (prefs.loggingEnabled && !accessOk) View.VISIBLE else View.GONE

        val logger = EventLoggerHolder.logger

        when {
            prefs.loggingEnabled && logger?.isPaused == true -> {
                setStatusCard(
                    label     = "PAUSED",
                    labelColor = R.color.accent_amber,
                    detail    = "Collection paused by participant",
                    active    = false
                )
                btnToggle.text = "Long-press to Stop"
                btnPause.text  = "RESUME LOGGING"
                btnPause.visibility = View.VISIBLE
            }

            prefs.loggingEnabled -> {
                setStatusCard(
                    label     = "LOGGING ACTIVE",
                    labelColor = R.color.accent_green,
                    detail    = "Collecting behavioural data…",
                    active    = true
                )
                btnToggle.text = "Long-press to Stop"
                btnPause.text  = "PAUSE LOGGING"
                btnPause.visibility = View.VISIBLE
            }

            exportStoppedLogging -> {
                setStatusCard(
                    label     = "STANDBY",
                    labelColor = R.color.text_secondary,
                    detail    = "Logging stopped for export",
                    active    = false
                )
                btnToggle.text = "RESUME LOGGING"
                btnPause.visibility = View.GONE
            }

            allGranted -> {
                setStatusCard(
                    label     = "READY",
                    labelColor = R.color.text_secondary,
                    detail    = "Tap Start Logging to begin",
                    active    = false
                )
                btnToggle.text = "START LOGGING"
                btnPause.visibility = View.GONE
            }

            else -> {
                setStatusCard(
                    label     = "STANDBY",
                    labelColor = R.color.text_muted,
                    detail    = "Grant all permissions to begin",
                    active    = false
                )
                btnToggle.text = "START LOGGING"
                btnPause.visibility = View.GONE
            }
        }
    }

    private fun setStatusCard(
        label: String,
        labelColor: Int,
        detail: String,
        active: Boolean
    ) {
        textStatusLabel.text = label
        textStatusLabel.setTextColor(ContextCompat.getColor(this, labelColor))
        textStatus.text = detail

        if (active) {
            cardStatus.setBackgroundResource(R.drawable.bg_card_status_active)
            statusDot.setBackgroundResource(R.drawable.shape_status_dot)
            startPulse()
        } else {
            cardStatus.setBackgroundResource(R.drawable.bg_card_status)
            statusDot.setBackgroundResource(R.drawable.shape_status_dot_inactive)
            stopPulse()
        }
    }

    private fun updatePermChip(chip: TextView, granted: Boolean) {
        if (granted) {
            chip.text = "GRANTED"
            chip.setTextColor(ContextCompat.getColor(this, R.color.accent_green))
            chip.setBackgroundResource(R.drawable.bg_chip_granted)
        } else {
            chip.text = "NEEDED"
            chip.setTextColor(ContextCompat.getColor(this, R.color.accent_blue))
            chip.setBackgroundResource(R.drawable.bg_chip_needed)
        }
    }

    // ── Pulse animation ───────────────────────────────────────────────────────

    private fun startPulse() {
        if (pulseAnimators.isNotEmpty()) return   // already running
        animateRing(pulseRing1, startDelay = 0L)
        animateRing(pulseRing2, startDelay = 700L)
        animateRing(pulseRing3, startDelay = 1400L)
    }

    private fun animateRing(ring: View, startDelay: Long) {
        val dur = 2100L
        val interp = DecelerateInterpolator(1.5f)

        fun anim(prop: String, from: Float, to: Float) =
            ObjectAnimator.ofFloat(ring, prop, from, to).apply {
                duration      = dur
                this.startDelay = startDelay
                repeatCount   = ValueAnimator.INFINITE
                interpolator  = interp
            }.also {
                it.start()
                pulseAnimators.add(it)
            }

        anim("scaleX", 0.25f, 1.9f)
        anim("scaleY", 0.25f, 1.9f)
        anim("alpha",  0.65f, 0f)
    }

    private fun stopPulse() {
        pulseAnimators.forEach { it.cancel() }
        pulseAnimators.clear()
        pulseRing1.alpha = 0f
        pulseRing2.alpha = 0f
        pulseRing3.alpha = 0f
    }

    // ── Permission helpers ────────────────────────────────────────────────────

    private fun hasUsageStatsPermission(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode   = appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_GENERIC)
            .any { it.resolveInfo.serviceInfo.packageName == packageName }
    }

    private fun isBatteryOptimizationDisabled(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun isNotificationListenerEnabled(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
}

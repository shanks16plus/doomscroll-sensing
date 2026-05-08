package nl.utwente.doomscroll.service

import android.app.usage.UsageStatsManager
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import nl.utwente.doomscroll.classifier.AppCategoryClassifier
import nl.utwente.doomscroll.model.AppSessionEvent
import nl.utwente.doomscroll.model.SensorEvent
import nl.utwente.doomscroll.storage.EventLogger

class UsageTracker(
    private val context: Context,
    private val participantId: String,
    private val logger: EventLogger,
    private val scope: CoroutineScope
) {
    private val usageStatsManager =
        context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    private val classifier = AppCategoryClassifier(context)
    private var currentForegroundApp: String? = null
    private var pollJob: Job? = null

    var foregroundApp: String? = null
        private set
    private var currentActivityClass: String? = null

    fun start() {
        pollJob = scope.launch {
            while (isActive) {
                pollForegroundApp()
                delay(1000)
            }
        }
    }

    fun stop() {
        pollJob?.cancel()
        pollJob = null
    }

    /** Pause polling while the screen is off to avoid unnecessary wakeups. */
    fun onScreenOff() {
        pollJob?.cancel()
        pollJob = null
    }

    /** Resume polling when the screen turns on. */
    fun onScreenOn() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (isActive) {
                pollForegroundApp()
                delay(1000)
            }
        }
    }

    fun onForegroundAppFromAccessibility(packageName: String, activityClass: String? = null) {
        currentActivityClass = activityClass
        handleAppChange(packageName)
    }

    private fun pollForegroundApp() {
        val now = System.currentTimeMillis()
        val stats = usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            now - 5000,
            now
        )
        val recent = stats
            ?.filter { it.lastTimeUsed > 0 }
            ?.maxByOrNull { it.lastTimeUsed }
            ?: return

        handleAppChange(recent.packageName)
    }

    private fun handleAppChange(newPackage: String) {
        if (newPackage == currentForegroundApp) return
        val ts = System.currentTimeMillis()

        currentForegroundApp?.let { oldPkg ->
            val bgEvent = SensorEvent.AppSession(
                timestampMs = ts, participantId = participantId,
                event = AppSessionEvent.BACKGROUND,
                packageName = oldPkg, category = classifier.classify(oldPkg),
                activityClass = currentActivityClass
            )
            scope.launch { logger.log(bgEvent) }
        }

        currentForegroundApp = newPackage
        foregroundApp = newPackage

        val fgEvent = SensorEvent.AppSession(
            timestampMs = ts, participantId = participantId,
            event = AppSessionEvent.FOREGROUND,
            packageName = newPackage, category = classifier.classify(newPackage),
            activityClass = currentActivityClass
        )
        scope.launch { logger.log(fgEvent) }
    }
}

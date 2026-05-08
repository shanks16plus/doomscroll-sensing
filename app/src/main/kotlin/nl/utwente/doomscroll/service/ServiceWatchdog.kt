package nl.utwente.doomscroll.service

import android.app.ActivityManager
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import nl.utwente.doomscroll.util.Preferences
import java.util.concurrent.TimeUnit

class ServiceWatchdog(
    private val ctx: Context,
    params: WorkerParameters
) : CoroutineWorker(ctx, params) {

    companion object {
        private const val WORK_NAME = "doomscroll_watchdog"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<ServiceWatchdog>(15, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }

    override suspend fun doWork(): Result {
        val prefs = Preferences(ctx)
        if (!prefs.loggingEnabled) return Result.success()
        val pid = prefs.participantId ?: return Result.success()

        if (!isServiceRunning()) {
            SensorLoggingService.start(ctx, pid)
        }
        return Result.success()
    }

    private fun isServiceRunning(): Boolean {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        @Suppress("DEPRECATION")
        return am.getRunningServices(Int.MAX_VALUE)
            .any { it.service.className == SensorLoggingService::class.java.name }
    }
}

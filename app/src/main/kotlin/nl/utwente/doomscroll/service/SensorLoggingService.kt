package nl.utwente.doomscroll.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.IBinder
import android.os.StatFs
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import nl.utwente.doomscroll.R
import nl.utwente.doomscroll.accessibility.TouchGestureService
import nl.utwente.doomscroll.model.SensorEvent as ModelSensorEvent
import nl.utwente.doomscroll.storage.EventLogger

class SensorLoggingService : Service(), SensorEventListener {

    companion object {
        const val CHANNEL_ID = "psu_logging"
        const val NOTIFICATION_ID = 1
        const val EXTRA_PARTICIPANT_ID = "participant_id"
        const val TAG = "PSUSensing"

        // 20,000 µs = 50 Hz target. Using explicit period rather than SENSOR_DELAY_GAME
        // (which is an opaque hint). On Pixel 4 this reliably delivers ~50 Hz.
        const val SENSOR_PERIOD_US = 20_000

        fun start(context: Context, participantId: String) {
            val intent = Intent(context, SensorLoggingService::class.java)
            intent.putExtra(EXTRA_PARTICIPANT_ID, participantId)
            context.startForegroundService(intent)
            ServiceWatchdog.schedule(context)
        }

        fun stop(context: Context) {
            ServiceWatchdog.cancel(context)
            context.stopService(Intent(context, SensorLoggingService::class.java))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var sensorManager: SensorManager
    private var eventLogger: EventLogger? = null
    private var usageTracker: UsageTracker? = null
    private var screenStateReceiver: ScreenStateReceiver? = null
    private var flushJob: kotlinx.coroutines.Job? = null
    private var heartbeatJob: kotlinx.coroutines.Job? = null
    private var participantId: String = ""
    private var sensorsRegistered = false
    private var isScreenOn = true  // updated by ScreenStateReceiver callbacks

    // Rate measurement — reset on every registerSensors() call.
    // Check `adb logcat -s DoomScroll` in the first 60 seconds to verify ~50 Hz.
    private var accelSampleCount = 0
    private var gyroSampleCount = 0
    private var rateWindowStartMs = 0L

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        participantId = intent?.getStringExtra(EXTRA_PARTICIPANT_ID) ?: return START_NOT_STICKY
        startForeground(NOTIFICATION_ID, buildNotification())

        eventLogger = EventLogger(this, participantId)
        EventLoggerHolder.logger = eventLogger

        usageTracker = UsageTracker(this, participantId, eventLogger!!, scope)
        UsageTrackerHolder.tracker = usageTracker
        usageTracker!!.start()

        screenStateReceiver = ScreenStateReceiver(participantId, scope, ::onScreenOn, ::onScreenOff)
        registerReceiver(screenStateReceiver, ScreenStateReceiver.intentFilter())

        startPeriodicFlush()
        startHeartbeat()
        registerSensors()
        return START_STICKY
    }

    private fun registerSensors() {
        if (sensorsRegistered) return
        val accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val gyro = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        accel?.let { sensorManager.registerListener(this, it, SENSOR_PERIOD_US) }
        gyro?.let { sensorManager.registerListener(this, it, SENSOR_PERIOD_US) }
        sensorsRegistered = true
        rateWindowStartMs = System.currentTimeMillis()
        accelSampleCount = 0
        gyroSampleCount = 0
    }

    private fun unregisterSensors() {
        if (!sensorsRegistered) return
        sensorManager.unregisterListener(this)
        sensorsRegistered = false
    }

    private fun onScreenOn() {
        isScreenOn = true
        registerSensors()
        usageTracker?.onScreenOn()
    }

    private fun onScreenOff() {
        isScreenOn = false
        unregisterSensors()
        usageTracker?.onScreenOff()
    }

    private fun startPeriodicFlush() {
        flushJob = scope.launch {
            while (isActive) {
                delay(5 * 60 * 1000L)
                eventLogger?.flush()
            }
        }
    }

    /**
     * Emit a heartbeat event every 60 seconds.
     *
     * Heartbeats let analysts distinguish "service was down" (no heartbeats) from
     * "screen was off" (heartbeats present but no sensor/interaction events).
     *
     * Also checks accessibility service health: if it's disabled, updates the
     * persistent notification so the researcher sees a warning immediately.
     */
    private fun startHeartbeat() {
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(60_000L)
                val accessibilityOk = isAccessibilityServiceEnabled()
                val logger = eventLogger ?: continue

                logger.log(ModelSensorEvent.Heartbeat(
                    timestampMs = System.currentTimeMillis(),
                    participantId = participantId,
                    screenOn = isScreenOn,
                    accessibilityEnabled = accessibilityOk,
                    freeStorageMb = getFreeStorageMb()
                ))

                // Update notification if accessibility service has gone down
                updateNotification(accessibilityDown = !accessibilityOk)
            }
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        val logger = eventLogger ?: return
        if (logger.isPaused) return

        val ts = System.currentTimeMillis()

        // Count samples for rate measurement; logged every 60 s to Logcat.
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> accelSampleCount++
            Sensor.TYPE_GYROSCOPE -> gyroSampleCount++
        }
        val elapsed = ts - rateWindowStartMs
        if (rateWindowStartMs > 0 && elapsed >= 60_000L) {
            Log.i(TAG, "Sensor rates — accel: %.1f Hz  gyro: %.1f Hz".format(
                accelSampleCount * 1000.0 / elapsed,
                gyroSampleCount * 1000.0 / elapsed
            ))
            accelSampleCount = 0
            gyroSampleCount = 0
            rateWindowStartMs = ts
        }

        val modelEvent = when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> ModelSensorEvent.Accelerometer(
                timestampMs = ts, participantId = participantId,
                x = event.values[0], y = event.values[1], z = event.values[2]
            )
            Sensor.TYPE_GYROSCOPE -> ModelSensorEvent.Gyroscope(
                timestampMs = ts, participantId = participantId,
                x = event.values[0], y = event.values[1], z = event.values[2]
            )
            else -> return
        }
        scope.launch { logger.log(modelEvent) }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        flushJob?.cancel()
        heartbeatJob?.cancel()
        unregisterSensors()
        usageTracker?.stop()
        screenStateReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) {}
        }
        kotlinx.coroutines.runBlocking {
            eventLogger?.flush()
            eventLogger?.close()
        }
        scope.cancel()
        EventLoggerHolder.logger = null
        UsageTrackerHolder.tracker = null
        super.onDestroy()
    }

    // ---- Helpers ----

    private fun isAccessibilityServiceEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val cn = ComponentName(this, TouchGestureService::class.java)
        return enabled.split(":").any { part ->
            part.trim().equals(cn.flattenToString(), ignoreCase = true) ||
            part.trim().equals(cn.flattenToShortString(), ignoreCase = true)
        }
    }

    private fun getFreeStorageMb(): Long {
        val stat = StatFs(filesDir.absolutePath)
        return stat.availableBlocksLong * stat.blockSizeLong / (1024L * 1024L)
    }

    private fun updateNotification(accessibilityDown: Boolean) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(accessibilityDown))
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Data Collection", NotificationManager.IMPORTANCE_MIN
        ).apply { description = "Active while logging sensor data" }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
    }

    private fun buildNotification(accessibilityDown: Boolean = false): Notification {
        val text = if (accessibilityDown)
            "WARNING: gesture logging stopped — open app"
        else
            "Logging sensor data"
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Doomscroll Sensing")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .build()
    }
}

object EventLoggerHolder {
    @Volatile
    var logger: EventLogger? = null
}

object UsageTrackerHolder {
    @Volatile
    var tracker: UsageTracker? = null
}

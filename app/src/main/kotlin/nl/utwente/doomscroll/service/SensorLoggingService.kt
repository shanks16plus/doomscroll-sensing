package nl.utwente.doomscroll.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.IBinder
import android.os.PowerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import nl.utwente.doomscroll.R
import nl.utwente.doomscroll.storage.EventLogger
import nl.utwente.doomscroll.model.SensorEvent as ModelSensorEvent

class SensorLoggingService : Service(), SensorEventListener {

    companion object {
        const val CHANNEL_ID = "doomscroll_logging"
        const val NOTIFICATION_ID = 1
        const val EXTRA_PARTICIPANT_ID = "participant_id"

        fun start(context: Context, participantId: String) {
            val intent = Intent(context, SensorLoggingService::class.java)
            intent.putExtra(EXTRA_PARTICIPANT_ID, participantId)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SensorLoggingService::class.java))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var sensorManager: SensorManager
    private var wakeLock: PowerManager.WakeLock? = null
    private var eventLogger: EventLogger? = null
    private var usageTracker: UsageTracker? = null
    private var screenStateReceiver: ScreenStateReceiver? = null
    private var participantId: String = ""

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        participantId = intent?.getStringExtra(EXTRA_PARTICIPANT_ID) ?: return START_NOT_STICKY
        startForeground(NOTIFICATION_ID, buildNotification())
        acquireWakeLock()

        eventLogger = EventLogger(this, participantId)
        EventLoggerHolder.logger = eventLogger

        usageTracker = UsageTracker(this, participantId, eventLogger!!, scope)
        UsageTrackerHolder.tracker = usageTracker
        usageTracker!!.start()

        screenStateReceiver = ScreenStateReceiver(participantId, scope)
        registerReceiver(screenStateReceiver, ScreenStateReceiver.intentFilter())

        registerSensors()
        return START_STICKY
    }

    private fun registerSensors() {
        val accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val gyro = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        accel?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        gyro?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    override fun onSensorChanged(event: SensorEvent) {
        val logger = eventLogger ?: return
        if (logger.isPaused) return

        val ts = System.currentTimeMillis()
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
        sensorManager.unregisterListener(this)
        usageTracker?.stop()
        screenStateReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) {}
        }
        kotlinx.coroutines.runBlocking {
            eventLogger?.flush()
            eventLogger?.close()
        }
        scope.cancel()
        wakeLock?.release()
        EventLoggerHolder.logger = null
        UsageTrackerHolder.tracker = null
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "doomscroll:sensor").apply {
            acquire(10 * 60 * 60 * 1000L)
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Data Collection", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Active while logging sensor data" }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Doomscroll Sensing")
            .setContentText("Logging sensor data")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .build()
}

object EventLoggerHolder {
    @Volatile
    var logger: EventLogger? = null
}

object UsageTrackerHolder {
    @Volatile
    var tracker: UsageTracker? = null
}

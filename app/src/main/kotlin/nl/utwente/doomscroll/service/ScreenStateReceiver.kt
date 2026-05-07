package nl.utwente.doomscroll.service

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import nl.utwente.doomscroll.model.PauseReason
import nl.utwente.doomscroll.model.ScreenState
import nl.utwente.doomscroll.model.SensorEvent
import nl.utwente.doomscroll.storage.EventLogger

class ScreenStateReceiver(
    private val participantId: String,
    private val scope: CoroutineScope
) : BroadcastReceiver() {

    companion object {
        fun intentFilter(): IntentFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val logger = EventLoggerHolder.logger ?: return
        val ts = System.currentTimeMillis()

        when (intent.action) {
            Intent.ACTION_SCREEN_ON -> {
                logScreenState(logger, ts, ScreenState.ON)
                // Screen is on but keyguard may still be active
                val km = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
                if (km.isKeyguardLocked) {
                    scope.launch { logger.pause(PauseReason.KEYGUARD) }
                }
            }
            Intent.ACTION_SCREEN_OFF -> {
                logScreenState(logger, ts, ScreenState.OFF)
                scope.launch { logger.pause(PauseReason.KEYGUARD) }
            }
            Intent.ACTION_USER_PRESENT -> {
                logScreenState(logger, ts, ScreenState.UNLOCKED)
                scope.launch { logger.resume(PauseReason.KEYGUARD) }
            }
        }
    }

    private fun logScreenState(logger: EventLogger, ts: Long, state: ScreenState) {
        val event = SensorEvent.ScreenStateEvent(
            timestampMs = ts,
            participantId = participantId,
            state = state
        )
        scope.launch { logger.log(event) }
    }
}

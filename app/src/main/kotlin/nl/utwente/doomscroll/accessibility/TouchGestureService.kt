package nl.utwente.doomscroll.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import nl.utwente.doomscroll.model.PauseReason
import nl.utwente.doomscroll.model.ScrollDirection
import nl.utwente.doomscroll.model.ScrollEventType
import nl.utwente.doomscroll.model.SensorEvent
import nl.utwente.doomscroll.model.TapType
import nl.utwente.doomscroll.service.EventLoggerHolder

class TouchGestureService : AccessibilityService() {

    companion object {
        private val TAP_TARGET_APPS = setOf(
            "com.instagram.android",
            "com.zhiliaoapp.musically"
        )
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var participantId: String = ""
    private var currentForegroundApp: String = ""
    private var isPasswordFieldFocused = false

    override fun onServiceConnected() {
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_VIEW_SCROLLED or
                    AccessibilityEvent.TYPE_VIEW_CLICKED or
                    AccessibilityEvent.TYPE_VIEW_FOCUSED or
                    AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            notificationTimeout = 50
        }

        val prefs = nl.utwente.doomscroll.util.Preferences(this)
        participantId = prefs.participantId ?: ""
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val logger = EventLoggerHolder.logger ?: return
        if (participantId.isEmpty()) return
        val ts = System.currentTimeMillis()

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                event.packageName?.toString()?.let { pkg ->
                    currentForegroundApp = pkg
                }
            }

            AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                val nowPassword = isPasswordNode(event.source)
                if (nowPassword && !isPasswordFieldFocused) {
                    isPasswordFieldFocused = true
                    scope.launch { logger.pause(PauseReason.PASSWORD_FIELD) }
                } else if (!nowPassword && isPasswordFieldFocused) {
                    isPasswordFieldFocused = false
                    scope.launch { logger.resume(PauseReason.PASSWORD_FIELD) }
                }
            }

            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                val direction = inferScrollDirection(event)
                val scrollStart = SensorEvent.ScrollEvent(
                    timestampMs = ts, participantId = participantId,
                    event = ScrollEventType.SCROLL_START,
                    direction = direction,
                    foregroundApp = currentForegroundApp
                )
                val scrollEnd = SensorEvent.ScrollEvent(
                    timestampMs = ts + 1, participantId = participantId,
                    event = ScrollEventType.SCROLL_END,
                    direction = direction,
                    foregroundApp = currentForegroundApp
                )
                scope.launch {
                    logger.log(scrollStart)
                    logger.log(scrollEnd)
                }
            }

            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                if (currentForegroundApp in TAP_TARGET_APPS) {
                    val tapEvent = SensorEvent.TapEvent(
                        timestampMs = ts, participantId = participantId,
                        tapType = TapType.SINGLE,
                        foregroundApp = currentForegroundApp
                    )
                    scope.launch { logger.log(tapEvent) }
                }
            }
        }
    }

    private fun isPasswordNode(node: AccessibilityNodeInfo?): Boolean {
        return node?.isPassword == true
    }

    private fun inferScrollDirection(event: AccessibilityEvent): ScrollDirection {
        val fromIndex = event.fromIndex
        val toIndex = event.toIndex
        return when {
            toIndex > fromIndex -> ScrollDirection.DOWN
            toIndex < fromIndex -> ScrollDirection.UP
            else -> ScrollDirection.NONE
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

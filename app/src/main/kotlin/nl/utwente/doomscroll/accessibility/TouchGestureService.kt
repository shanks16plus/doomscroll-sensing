package nl.utwente.doomscroll.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import nl.utwente.doomscroll.model.InteractionType
import nl.utwente.doomscroll.model.PauseReason
import nl.utwente.doomscroll.model.ScrollDirection
import nl.utwente.doomscroll.model.ScrollEventType
import nl.utwente.doomscroll.model.SensorEvent
import nl.utwente.doomscroll.model.TapType
import nl.utwente.doomscroll.service.EventLoggerHolder
import nl.utwente.doomscroll.service.UsageTrackerHolder

class TouchGestureService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var participantId: String = ""
    private var currentForegroundApp: String = ""
    private var currentActivityClass: String = ""
    private var isPasswordFieldFocused = false

    private var lastClickTimestamp: Long = 0
    private var lastClickApp: String = ""
    private var lastScrollTimestamp: Long = 0

    // ---- Scroll direction tracking ----
    // Maps view resource ID → last known scrollY (for API <28 fallback).
    private val scrollYMap = mutableMapOf<String, Int>()
    // SCROLL_END debounce: emit 500 ms after the last scroll event so we get a real end time.
    private var scrollDebounceJob: Job? = null

    // ---- Touch interaction state for double-tap via TYPE_TOUCH_INTERACTION_START ----
    private var lastTouchStartTs: Long = 0
    private var lastTouchStartApp: String = ""
    private var hadScrollSinceTouchStart: Boolean = false

    override fun onServiceConnected() {
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_VIEW_SCROLLED or
                    AccessibilityEvent.TYPE_VIEW_CLICKED or
                    AccessibilityEvent.TYPE_VIEW_FOCUSED or
                    AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                    AccessibilityEvent.TYPE_TOUCH_INTERACTION_START
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
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
                event.packageName?.toString()?.let { pkg -> currentForegroundApp = pkg }
                event.className?.toString()?.let { cls -> currentActivityClass = cls }
                UsageTrackerHolder.tracker?.onForegroundAppFromAccessibility(
                    currentForegroundApp, currentActivityClass
                )
            }

            AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                val node = event.source
                val nowPassword = isPasswordNode(node)
                val nowEditText = isEditTextField(node)

                if (nowPassword && !isPasswordFieldFocused) {
                    isPasswordFieldFocused = true
                    scope.launch { logger.pause(PauseReason.PASSWORD_FIELD) }
                } else if (!nowPassword && isPasswordFieldFocused) {
                    isPasswordFieldFocused = false
                    scope.launch { logger.resume(PauseReason.PASSWORD_FIELD) }
                }

                if (nowEditText && !nowPassword && currentForegroundApp in InteractionClassifier.SOCIAL_APPS) {
                    scope.launch {
                        logger.log(SensorEvent.TapEvent(
                            timestampMs = ts, participantId = participantId,
                            tapType = TapType.SINGLE,
                            foregroundApp = currentForegroundApp,
                            interactionType = InteractionType.COMMENT_OPEN,
                            viewDescription = "comment_field_focused"
                        ))
                    }
                }
                node?.recycle()
            }

            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                hadScrollSinceTouchStart = true

                val direction = inferScrollDirection(event)
                val dwellTime = InteractionClassifier.computeDwellTime(ts, lastScrollTimestamp)

                scope.launch {
                    logger.log(SensorEvent.ScrollEvent(
                        timestampMs = ts, participantId = participantId,
                        event = ScrollEventType.SCROLL_START,
                        direction = direction,
                        foregroundApp = currentForegroundApp,
                        dwellTimeMs = dwellTime
                    ))
                }

                // Debounce SCROLL_END: cancel any pending end and schedule a new one 500 ms
                // from the last scroll event. This gives a real end timestamp rather than the
                // old synthetic ts+1 approach.
                scrollDebounceJob?.cancel()
                val endTs = ts
                val endDir = direction
                val endApp = currentForegroundApp
                scrollDebounceJob = scope.launch {
                    delay(500)
                    logger.log(SensorEvent.ScrollEvent(
                        timestampMs = endTs, participantId = participantId,
                        event = ScrollEventType.SCROLL_END,
                        direction = endDir,
                        foregroundApp = endApp
                    ))
                }
                lastScrollTimestamp = ts
            }

            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                if (currentForegroundApp in InteractionClassifier.SOCIAL_APPS) {
                    val node = event.source
                    val description = getViewDescription(node)
                    val interaction = InteractionClassifier.classifyInteraction(description)
                    val (tapType, finalInteraction) = InteractionClassifier.resolveDoubleTap(
                        ts, lastClickTimestamp, currentForegroundApp, lastClickApp, interaction
                    )

                    scope.launch {
                        logger.log(SensorEvent.TapEvent(
                            timestampMs = ts, participantId = participantId,
                            tapType = tapType,
                            foregroundApp = currentForegroundApp,
                            interactionType = finalInteraction,
                            viewDescription = description
                        ))
                    }
                    lastClickTimestamp = ts
                    lastClickApp = currentForegroundApp
                    // Reset touch-start state so TYPE_TOUCH_INTERACTION_START doesn't
                    // emit a duplicate double-tap for the same gesture.
                    lastTouchStartTs = 0
                    node?.recycle()
                }
            }

            AccessibilityEvent.TYPE_TOUCH_INTERACTION_START -> {
                // Fires even when the target has clickable=false (e.g. Instagram feed photo),
                // so catches double-tap-to-like where TYPE_VIEW_CLICKED is unreliable.
                // Only double-taps are logged here — single taps come from TYPE_VIEW_CLICKED.
                // If TYPE_VIEW_CLICKED also fires (clickable=true), it resets lastTouchStartTs
                // above, preventing a duplicate here.
                //
                // Known limitation: on some social apps, every feed tap fires a scroll event
                // (momentum cancel), setting hadScrollSinceTouchStart=true and suppressing
                // detection. Treat view_description="touch_double_tap" events as best-effort.
                if (currentForegroundApp in InteractionClassifier.SOCIAL_APPS) {
                    val dt = ts - lastTouchStartTs
                    if (dt in 1 until InteractionClassifier.DOUBLE_TAP_WINDOW_MS &&
                        currentForegroundApp == lastTouchStartApp &&
                        !hadScrollSinceTouchStart) {
                        scope.launch {
                            logger.log(SensorEvent.TapEvent(
                                timestampMs = ts, participantId = participantId,
                                tapType = TapType.DOUBLE,
                                foregroundApp = currentForegroundApp,
                                interactionType = InteractionType.DOUBLE_TAP_LIKE,
                                viewDescription = "touch_double_tap"
                            ))
                        }
                    }
                    hadScrollSinceTouchStart = false
                    lastTouchStartTs = ts
                    lastTouchStartApp = currentForegroundApp
                }
            }
        }
    }

    // ---- Scroll direction helpers ----

    /**
     * Infer scroll direction using the best available method.
     *
     * API 28+: getScrollDeltaY() — signed delta since the previous event.
     *   Positive = down, negative = up. Falls through to position tracking if delta is 0
     *   (can happen for horizontal-only scrollables or first event on a view).
     *
     * Fallback: compare scrollY against the stored previous value for the same view ID.
     *   Returns NONE when no previous value exists or the view has no resource ID.
     */
    private fun inferScrollDirection(event: AccessibilityEvent): ScrollDirection {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val dy = event.scrollDeltaY
            if (dy != 0) return if (dy > 0) ScrollDirection.DOWN else ScrollDirection.UP
        }
        val viewId = event.source?.viewIdResourceName ?: return ScrollDirection.NONE
        val currentY = event.scrollY
        val prevY = scrollYMap[viewId]
        scrollYMap[viewId] = currentY
        return when {
            prevY == null -> ScrollDirection.NONE
            currentY > prevY -> ScrollDirection.DOWN
            currentY < prevY -> ScrollDirection.UP
            else -> ScrollDirection.NONE
        }
    }

    private fun isPasswordNode(node: AccessibilityNodeInfo?): Boolean = node?.isPassword == true

    private fun isEditTextField(node: AccessibilityNodeInfo?): Boolean {
        val className = node?.className?.toString() ?: return false
        return className.contains("EditText") || className.contains("AutoCompleteTextView")
    }

    private fun getViewDescription(node: AccessibilityNodeInfo?): String? {
        if (node == null) return null
        node.contentDescription?.toString()?.let { return it }
        findDescriptionInChildren(node, 2)?.let { return it }
        findDescriptionInParents(node, 2)?.let { return it }
        node.viewIdResourceName?.let { return it }
        return node.className?.toString()
    }

    private fun findDescriptionInChildren(node: AccessibilityNodeInfo, depth: Int): String? {
        if (depth <= 0) return null
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            child.contentDescription?.toString()?.let { child.recycle(); return it }
            findDescriptionInChildren(child, depth - 1)?.let { child.recycle(); return it }
            child.recycle()
        }
        return null
    }

    private fun findDescriptionInParents(node: AccessibilityNodeInfo, depth: Int): String? {
        if (depth <= 0) return null
        val parent = node.parent ?: return null
        parent.contentDescription?.toString()?.let { parent.recycle(); return it }
        val result = findDescriptionInParents(parent, depth - 1)
        parent.recycle()
        return result
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        scrollDebounceJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }
}

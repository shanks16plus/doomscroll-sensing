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

    override fun onServiceConnected() {
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_VIEW_SCROLLED or
                    AccessibilityEvent.TYPE_VIEW_CLICKED or
                    AccessibilityEvent.TYPE_VIEW_FOCUSED or
                    AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
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
                event.packageName?.toString()?.let { pkg ->
                    currentForegroundApp = pkg
                }
                event.className?.toString()?.let { cls ->
                    currentActivityClass = cls
                }
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
                    val tapEvent = SensorEvent.TapEvent(
                        timestampMs = ts, participantId = participantId,
                        tapType = TapType.SINGLE,
                        foregroundApp = currentForegroundApp,
                        interactionType = InteractionType.COMMENT_OPEN,
                        viewDescription = "comment_field_focused"
                    )
                    scope.launch { logger.log(tapEvent) }
                }

                node?.recycle()
            }

            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                val direction = inferScrollDirection(event)
                val dwellTime = InteractionClassifier.computeDwellTime(ts, lastScrollTimestamp)

                val scrollStart = SensorEvent.ScrollEvent(
                    timestampMs = ts, participantId = participantId,
                    event = ScrollEventType.SCROLL_START,
                    direction = direction,
                    foregroundApp = currentForegroundApp,
                    dwellTimeMs = dwellTime
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

                    val tapEvent = SensorEvent.TapEvent(
                        timestampMs = ts, participantId = participantId,
                        tapType = tapType,
                        foregroundApp = currentForegroundApp,
                        interactionType = finalInteraction,
                        viewDescription = description
                    )
                    scope.launch { logger.log(tapEvent) }

                    lastClickTimestamp = ts
                    lastClickApp = currentForegroundApp
                    node?.recycle()
                }
            }
        }
    }

    private fun isPasswordNode(node: AccessibilityNodeInfo?): Boolean {
        return node?.isPassword == true
    }

    private fun isEditTextField(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        val className = node.className?.toString() ?: return false
        return className.contains("EditText") || className.contains("AutoCompleteTextView")
    }

    private fun getViewDescription(node: AccessibilityNodeInfo?): String? {
        if (node == null) return null
        node.contentDescription?.toString()?.let { return it }
        // Traverse children (2 levels deep) — social apps nest buttons inside containers
        findDescriptionInChildren(node, 2)?.let { return it }
        // Try parent chain (2 levels up) — click may land on icon inside a labeled button
        findDescriptionInParents(node, 2)?.let { return it }
        node.viewIdResourceName?.let { return it }
        return node.className?.toString()
    }

    private fun findDescriptionInChildren(node: AccessibilityNodeInfo, depth: Int): String? {
        if (depth <= 0) return null
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            child.contentDescription?.toString()?.let {
                child.recycle()
                return it
            }
            findDescriptionInChildren(child, depth - 1)?.let {
                child.recycle()
                return it
            }
            child.recycle()
        }
        return null
    }

    private fun findDescriptionInParents(node: AccessibilityNodeInfo, depth: Int): String? {
        if (depth <= 0) return null
        val parent = node.parent ?: return null
        parent.contentDescription?.toString()?.let {
            parent.recycle()
            return it
        }
        val result = findDescriptionInParents(parent, depth - 1)
        parent.recycle()
        return result
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

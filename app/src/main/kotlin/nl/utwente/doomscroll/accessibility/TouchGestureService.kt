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

class TouchGestureService : AccessibilityService() {

    companion object {
        private val SOCIAL_APPS = setOf(
            "com.instagram.android",
            "com.zhiliaoapp.musically",
            "com.google.android.youtube",
            "com.twitter.android",
            "com.reddit.frontpage",
            "com.snapchat.android",
            "com.facebook.katana",
            "com.facebook.orca"
        )

        private val LIKE_KEYWORDS = setOf(
            "like", "unlike", "love", "heart", "favourite", "favorite"
        )
        private val COMMENT_KEYWORDS = setOf(
            "comment", "reply", "add a comment", "write a comment"
        )
        private val SHARE_KEYWORDS = setOf(
            "share", "send", "repost", "retweet"
        )
        private val FOLLOW_KEYWORDS = setOf(
            "follow", "subscribe"
        )
        private val SAVE_KEYWORDS = setOf(
            "save", "bookmark", "add to collection"
        )

        private const val DOUBLE_TAP_WINDOW_MS = 400L
    }

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

                if (nowEditText && !nowPassword && currentForegroundApp in SOCIAL_APPS) {
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
                val dwellTime = if (lastScrollTimestamp > 0) ts - lastScrollTimestamp else null

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
                if (currentForegroundApp in SOCIAL_APPS) {
                    val node = event.source
                    val description = getViewDescription(node)
                    val interaction = classifyInteraction(description)

                    val isDoubleTap = (ts - lastClickTimestamp < DOUBLE_TAP_WINDOW_MS) &&
                            (currentForegroundApp == lastClickApp)

                    val tapType: TapType
                    val finalInteraction: InteractionType

                    if (isDoubleTap) {
                        tapType = TapType.DOUBLE
                        finalInteraction = InteractionType.DOUBLE_TAP_LIKE
                    } else {
                        tapType = TapType.SINGLE
                        finalInteraction = interaction
                    }

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
        val cd = node.contentDescription?.toString()
        if (cd != null) return cd
        val viewId = node.viewIdResourceName
        if (viewId != null) return viewId
        return node.className?.toString()
    }

    private fun classifyInteraction(description: String?): InteractionType {
        if (description == null) return InteractionType.OTHER
        val lower = description.lowercase()
        return when {
            LIKE_KEYWORDS.any { lower.contains(it) } -> InteractionType.LIKE
            COMMENT_KEYWORDS.any { lower.contains(it) } -> InteractionType.COMMENT_OPEN
            SHARE_KEYWORDS.any { lower.contains(it) } -> InteractionType.SHARE
            FOLLOW_KEYWORDS.any { lower.contains(it) } -> InteractionType.FOLLOW
            SAVE_KEYWORDS.any { lower.contains(it) } -> InteractionType.SAVE
            else -> InteractionType.OTHER
        }
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

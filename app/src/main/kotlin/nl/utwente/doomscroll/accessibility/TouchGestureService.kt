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

    // ── Dwell time tracking ─────────────────────────────────────────────────
    // Set to System.currentTimeMillis() when the SCROLL_END debounce fires.
    // The NEXT SCROLL_START subtracts this to get "time paused between bursts".
    @Volatile private var lastScrollEndTs: Long = 0

    // ── Scroll direction tracking ───────────────────────────────────────────
    private val scrollYMap = mutableMapOf<String, Int>()
    private var scrollDebounceJob: Job? = null

    // ── Touch state for TYPE_TOUCH_INTERACTION_START double-tap ────────────
    private var lastTouchStartTs: Long = 0
    private var lastTouchStartApp: String = ""
    private var hadScrollSinceTouchStart: Boolean = false

    override fun onServiceConnected() {
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes =
                AccessibilityEvent.TYPE_VIEW_SCROLLED          or
                AccessibilityEvent.TYPE_VIEW_CLICKED           or
                AccessibilityEvent.TYPE_VIEW_FOCUSED           or
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED   or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_TOUCH_INTERACTION_START or
                AccessibilityEvent.TYPE_ANNOUNCEMENT           // ← catches YouTube "Liked" etc.
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags =
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
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

            // ── App foreground tracking ─────────────────────────────────────
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                event.packageName?.toString()?.let { pkg -> currentForegroundApp = pkg }
                event.className?.toString()?.let  { cls -> currentActivityClass = cls }
                UsageTrackerHolder.tracker?.onForegroundAppFromAccessibility(
                    currentForegroundApp, currentActivityClass
                )
            }

            // ── Password / comment-field focus ──────────────────────────────
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

                if (nowEditText && !nowPassword &&
                    currentForegroundApp in InteractionClassifier.SOCIAL_APPS) {
                    scope.launch {
                        logger.log(SensorEvent.TapEvent(
                            timestampMs = ts,
                            participantId = participantId,
                            tapType = TapType.SINGLE,
                            foregroundApp = currentForegroundApp,
                            interactionType = InteractionType.COMMENT_OPEN,
                            viewDescription = "comment_field_focused"
                        ))
                    }
                }
                node?.recycle()
            }

            // ── Scroll ──────────────────────────────────────────────────────
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                hadScrollSinceTouchStart = true

                val direction = inferScrollDirection(event)
                // Dwell = gap from when the PREVIOUS scroll burst ended to now.
                // lastScrollEndTs is set in the SCROLL_END debounce below.
                val dwellTime = if (lastScrollEndTs > 0L) ts - lastScrollEndTs else null

                scope.launch {
                    logger.log(SensorEvent.ScrollEvent(
                        timestampMs = ts,
                        participantId = participantId,
                        event = ScrollEventType.SCROLL_START,
                        direction = direction,
                        foregroundApp = currentForegroundApp,
                        dwellTimeMs = dwellTime
                    ))
                }

                // Debounce SCROLL_END: reset the 500 ms window on every raw scroll event.
                // Captures the last direction and app at schedule time.
                scrollDebounceJob?.cancel()
                val endDir = direction
                val endApp = currentForegroundApp
                scrollDebounceJob = scope.launch {
                    delay(500)
                    // Record when this burst ended — used for dwell on the NEXT burst.
                    val now = System.currentTimeMillis()
                    lastScrollEndTs = now
                    logger.log(SensorEvent.ScrollEvent(
                        timestampMs = now,
                        participantId = participantId,
                        event = ScrollEventType.SCROLL_END,
                        direction = endDir,
                        foregroundApp = endApp,
                        dwellTimeMs = null   // dwell belongs on SCROLL_START, not END
                    ))
                }
            }

            // ── Tap / click on social apps ──────────────────────────────────
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
                            timestampMs = ts,
                            participantId = participantId,
                            tapType = tapType,
                            foregroundApp = currentForegroundApp,
                            interactionType = finalInteraction,
                            viewDescription = description
                        ))
                    }
                    lastClickTimestamp = ts
                    lastClickApp = currentForegroundApp
                    lastTouchStartTs = 0   // prevent duplicate from TOUCH_INTERACTION_START
                    node?.recycle()
                }
            }

            // ── Announcement events (most reliable LIKE source on YouTube) ──
            //
            // YouTube fires TYPE_ANNOUNCEMENT when an action completes — e.g.:
            //   "Video was liked"  /  "Liked"  →  LIKE
            //   "Subscribed to channel"          →  FOLLOW
            // This catches cases where the like button has no content description
            // and TYPE_VIEW_CLICKED only gets "android.widget.Button".
            AccessibilityEvent.TYPE_ANNOUNCEMENT -> {
                if (currentForegroundApp in InteractionClassifier.SOCIAL_APPS) {
                    val text = event.text
                        ?.mapNotNull { it?.toString()?.trim() }
                        ?.filter { it.isNotEmpty() }
                        ?.joinToString(" ")
                        ?.takeIf { it.isNotEmpty() } ?: return

                    val interaction = InteractionClassifier.classifyInteraction(text)
                    if (interaction != InteractionType.OTHER) {
                        scope.launch {
                            logger.log(SensorEvent.TapEvent(
                                timestampMs = ts,
                                participantId = participantId,
                                tapType = TapType.SINGLE,
                                foregroundApp = currentForegroundApp,
                                interactionType = interaction,
                                viewDescription = "announcement:$text"
                            ))
                        }
                    }
                }
            }

            // ── Double-tap on non-clickable views (Instagram/TikTok feed) ───
            AccessibilityEvent.TYPE_TOUCH_INTERACTION_START -> {
                if (currentForegroundApp in InteractionClassifier.SOCIAL_APPS) {
                    val dt = ts - lastTouchStartTs
                    if (dt in 1 until InteractionClassifier.DOUBLE_TAP_WINDOW_MS &&
                        currentForegroundApp == lastTouchStartApp &&
                        !hadScrollSinceTouchStart) {
                        scope.launch {
                            logger.log(SensorEvent.TapEvent(
                                timestampMs = ts,
                                participantId = participantId,
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

    // ── Scroll direction ────────────────────────────────────────────────────

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
            prevY == null      -> ScrollDirection.NONE
            currentY > prevY   -> ScrollDirection.DOWN
            currentY < prevY   -> ScrollDirection.UP
            else               -> ScrollDirection.NONE
        }
    }

    // ── View description ────────────────────────────────────────────────────

    /**
     * Best-effort semantic label for an accessibility node.
     *
     * Priority order:
     *  1. contentDescription on the node itself
     *  2. contentDescription or text on descendant nodes (depth ≤ 3)
     *  3. contentDescription or text on ancestor nodes (depth ≤ 3)
     *  4. viewIdResourceName, local part only  (e.g. "like_button")
     *  5. node text
     *  6. class name as last resort
     */
    private fun getViewDescription(node: AccessibilityNodeInfo?): String? {
        if (node == null) return null

        // 1. Own content description
        node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { return it }

        // 2. Children (depth 3, checks both contentDescription and text)
        findSemanticLabelInChildren(node, depth = 3)?.let { return it }

        // 3. Ancestors
        findSemanticLabelInParents(node, depth = 3)?.let { return it }

        // 4. Resource ID — strip "package:id/" prefix so "like_button" is the classifier input.
        //    InteractionClassifier already has "like" as a keyword, so this covers
        //    resource IDs like "com.google.android.youtube:id/like_button".
        node.viewIdResourceName?.let { res ->
            val local = res.substringAfterLast("/").ifBlank { res }
            return local
        }

        // 5. Button text
        node.text?.toString()?.takeIf { it.isNotBlank() }?.let { return it }

        // 6. Class name
        return node.className?.toString()
    }

    private fun findSemanticLabelInChildren(node: AccessibilityNodeInfo, depth: Int): String? {
        if (depth <= 0) return null
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val label =
                child.contentDescription?.toString()?.takeIf { it.isNotBlank() }
                    ?: child.text?.toString()?.takeIf { it.isNotBlank() }
            if (label != null) { child.recycle(); return label }
            findSemanticLabelInChildren(child, depth - 1)?.let { child.recycle(); return it }
            child.recycle()
        }
        return null
    }

    private fun findSemanticLabelInParents(node: AccessibilityNodeInfo, depth: Int): String? {
        if (depth <= 0) return null
        val parent = node.parent ?: return null
        val label =
            parent.contentDescription?.toString()?.takeIf { it.isNotBlank() }
                ?: parent.text?.toString()?.takeIf { it.isNotBlank() }
        if (label != null) { parent.recycle(); return label }
        val result = findSemanticLabelInParents(parent, depth - 1)
        parent.recycle()
        return result
    }

    // ── Focus helpers ───────────────────────────────────────────────────────

    private fun isPasswordNode(node: AccessibilityNodeInfo?): Boolean =
        node?.isPassword == true

    private fun isEditTextField(node: AccessibilityNodeInfo?): Boolean {
        val cls = node?.className?.toString() ?: return false
        return cls.contains("EditText") || cls.contains("AutoCompleteTextView")
    }

    // ── Lifecycle ───────────────────────────────────────────────────────────

    override fun onInterrupt() {}

    override fun onDestroy() {
        scrollDebounceJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }
}

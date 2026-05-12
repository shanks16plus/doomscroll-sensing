package nl.utwente.doomscroll.model

enum class ScreenState { ON, OFF, UNLOCKED }

enum class AppSessionEvent { FOREGROUND, BACKGROUND }

enum class ScrollEventType { SCROLL_START, SCROLL_END }

enum class ScrollDirection { UP, DOWN, NONE }

enum class TapType { SINGLE, DOUBLE }

enum class AppCategory {
    SOCIAL, NEWS, BROWSER, MESSAGING,
    PRODUCTIVE, ENTERTAINMENT, UTILITY, OTHER
}

enum class PauseReason {
    PASSWORD_FIELD, KEYGUARD, SENSITIVE_APP, USER_REQUEST
}

/**
 * Whether a screen unlock was preceded by a notification within
 * [NotificationMonitor.TRIGGER_WINDOW_MS] of the unlock timestamp.
 * Null on SCREEN_ON and SCREEN_OFF events.
 */
enum class UnlockTrigger { NOTIFICATION, SPONTANEOUS }

enum class InteractionType {
    LIKE, DOUBLE_TAP_LIKE, COMMENT_OPEN, SHARE, FOLLOW, SAVE, OTHER
}

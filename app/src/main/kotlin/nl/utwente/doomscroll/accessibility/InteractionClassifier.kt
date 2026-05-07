package nl.utwente.doomscroll.accessibility

import nl.utwente.doomscroll.model.InteractionType
import nl.utwente.doomscroll.model.TapType

object InteractionClassifier {

    val SOCIAL_APPS = setOf(
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

    const val DOUBLE_TAP_WINDOW_MS = 400L

    fun classifyInteraction(description: String?): InteractionType {
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

    fun resolveDoubleTap(
        currentTimestamp: Long,
        lastClickTimestamp: Long,
        currentApp: String,
        lastClickApp: String,
        baseInteraction: InteractionType
    ): Pair<TapType, InteractionType> {
        val isDoubleTap = (currentTimestamp - lastClickTimestamp < DOUBLE_TAP_WINDOW_MS) &&
                (currentApp == lastClickApp)
        return if (isDoubleTap) {
            TapType.DOUBLE to InteractionType.DOUBLE_TAP_LIKE
        } else {
            TapType.SINGLE to baseInteraction
        }
    }

    fun computeDwellTime(currentTimestamp: Long, lastScrollTimestamp: Long): Long? {
        return if (lastScrollTimestamp > 0) currentTimestamp - lastScrollTimestamp else null
    }
}

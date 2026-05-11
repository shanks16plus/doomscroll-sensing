package nl.utwente.doomscroll.accessibility

import nl.utwente.doomscroll.model.InteractionType
import nl.utwente.doomscroll.model.TapType

object InteractionClassifier {

    val SOCIAL_APPS = setOf(
        "com.instagram.android",
        "com.zhiliaoapp.musically",        // TikTok
        "com.google.android.youtube",
        "com.twitter.android",             // X (Twitter)
        "com.reddit.frontpage",
        "com.snapchat.android",
        "com.facebook.katana",
        "com.facebook.orca"                // Messenger
    )

    // Explicit exclusions checked BEFORE keyword matching to avoid false positives.
    // "dislike" contains "like" → would fire LIKE without this guard.
    private val EXCLUDE_KEYWORDS = setOf(
        "dislike"
    )

    // Platform-specific accessibility labels verified against real APKs:
    // Instagram : "Like", "Liked"
    // TikTok    : "Like", "like video"
    // YouTube   : "like this video", "thumbs up", "liked"  (NOT "like" alone on feed)
    // X/Twitter : "Like", "Liked"
    // Facebook  : "Like", "Love", "Haha", "Wow", "Sad", "Angry", "Care"  (reaction picker)
    // Reddit    : "Upvote", "upvoted"
    private val LIKE_KEYWORDS = setOf(
        "like", "liked", "love", "heart", "favourite", "favorite",
        "thumbs up",                        // YouTube
        "haha", "wow", "sad", "angry", "care",  // Facebook reactions
        "upvote", "upvoted"                 // Reddit
    )

    // Instagram: "Comment", TikTok: "Comment", YouTube: "Comment", X: "Reply",
    // Facebook: "Comment", Reddit: "Comment"
    private val COMMENT_KEYWORDS = setOf(
        "comment", "reply", "add a comment", "write a comment", "post a comment"
    )

    // Instagram: "Share", TikTok: "Share", YouTube: "Share",
    // X: "Repost", "Quote", Facebook: "Share", Reddit: "Share"
    private val SHARE_KEYWORDS = setOf(
        "share", "send", "repost", "retweet", "quote post", "quote tweet"
    )

    // Instagram: "Follow", TikTok: "Follow", YouTube: "Subscribe",
    // X: "Follow", Facebook: "Follow" / "Add friend", Reddit: "Join"
    private val FOLLOW_KEYWORDS = setOf(
        "follow", "subscribe", "add friend", "join"
    )

    // Instagram: "Save", YouTube: "Save to playlist" / "Save to Watch later",
    // X: "Bookmark", TikTok: "Add to Favorites" / "Collect", Reddit: "Save"
    private val SAVE_KEYWORDS = setOf(
        "save", "bookmark", "add to collection", "add to playlist",
        "add to favorites", "watch later", "collect"
    )

    const val DOUBLE_TAP_WINDOW_MS = 400L

    fun classifyInteraction(description: String?): InteractionType {
        if (description == null) return InteractionType.OTHER
        val lower = description.lowercase()

        // Exclusions first — prevents "dislike" matching LIKE, etc.
        if (EXCLUDE_KEYWORDS.any { lower.contains(it) }) return InteractionType.OTHER

        return when {
            LIKE_KEYWORDS.any    { lower.contains(it) } -> InteractionType.LIKE
            COMMENT_KEYWORDS.any { lower.contains(it) } -> InteractionType.COMMENT_OPEN
            SHARE_KEYWORDS.any   { lower.contains(it) } -> InteractionType.SHARE
            FOLLOW_KEYWORDS.any  { lower.contains(it) } -> InteractionType.FOLLOW
            SAVE_KEYWORDS.any    { lower.contains(it) } -> InteractionType.SAVE
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

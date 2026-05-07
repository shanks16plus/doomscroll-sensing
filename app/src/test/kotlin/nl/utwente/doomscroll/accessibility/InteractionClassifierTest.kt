package nl.utwente.doomscroll.accessibility

import nl.utwente.doomscroll.model.InteractionType
import nl.utwente.doomscroll.model.TapType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InteractionClassifierTest {

    // --- classifyInteraction ---

    @Test
    fun `null description returns OTHER`() {
        assertEquals(InteractionType.OTHER, InteractionClassifier.classifyInteraction(null))
    }

    @Test
    fun `empty description returns OTHER`() {
        assertEquals(InteractionType.OTHER, InteractionClassifier.classifyInteraction(""))
    }

    @Test
    fun `like button detected`() {
        assertEquals(InteractionType.LIKE, InteractionClassifier.classifyInteraction("Like"))
        assertEquals(InteractionType.LIKE, InteractionClassifier.classifyInteraction("unlike"))
        assertEquals(InteractionType.LIKE, InteractionClassifier.classifyInteraction("♥ Heart"))
        assertEquals(InteractionType.LIKE, InteractionClassifier.classifyInteraction("Add to Favourites"))
        assertEquals(InteractionType.LIKE, InteractionClassifier.classifyInteraction("Favorite this post"))
    }

    @Test
    fun `love reaction detected as LIKE`() {
        assertEquals(InteractionType.LIKE, InteractionClassifier.classifyInteraction("Love"))
    }

    @Test
    fun `comment detected`() {
        assertEquals(InteractionType.COMMENT_OPEN, InteractionClassifier.classifyInteraction("Comment"))
        assertEquals(InteractionType.COMMENT_OPEN, InteractionClassifier.classifyInteraction("Reply"))
        assertEquals(InteractionType.COMMENT_OPEN, InteractionClassifier.classifyInteraction("Add a comment"))
        assertEquals(InteractionType.COMMENT_OPEN, InteractionClassifier.classifyInteraction("Write a comment"))
    }

    @Test
    fun `share detected`() {
        assertEquals(InteractionType.SHARE, InteractionClassifier.classifyInteraction("Share"))
        assertEquals(InteractionType.SHARE, InteractionClassifier.classifyInteraction("Send to friend"))
        assertEquals(InteractionType.SHARE, InteractionClassifier.classifyInteraction("Repost"))
        assertEquals(InteractionType.SHARE, InteractionClassifier.classifyInteraction("Retweet"))
    }

    @Test
    fun `follow detected`() {
        assertEquals(InteractionType.FOLLOW, InteractionClassifier.classifyInteraction("Follow"))
        assertEquals(InteractionType.FOLLOW, InteractionClassifier.classifyInteraction("Subscribe"))
    }

    @Test
    fun `save detected`() {
        assertEquals(InteractionType.SAVE, InteractionClassifier.classifyInteraction("Save"))
        assertEquals(InteractionType.SAVE, InteractionClassifier.classifyInteraction("Bookmark"))
        assertEquals(InteractionType.SAVE, InteractionClassifier.classifyInteraction("Add to collection"))
    }

    @Test
    fun `case insensitive matching`() {
        assertEquals(InteractionType.LIKE, InteractionClassifier.classifyInteraction("LIKE"))
        assertEquals(InteractionType.SHARE, InteractionClassifier.classifyInteraction("SHARE THIS POST"))
        assertEquals(InteractionType.COMMENT_OPEN, InteractionClassifier.classifyInteraction("REPLY TO THIS"))
    }

    @Test
    fun `random view description returns OTHER`() {
        assertEquals(InteractionType.OTHER, InteractionClassifier.classifyInteraction("Navigate up"))
        assertEquals(InteractionType.OTHER, InteractionClassifier.classifyInteraction("com.instagram.android:id/tab_bar"))
        assertEquals(InteractionType.OTHER, InteractionClassifier.classifyInteraction("Profile picture"))
    }

    // --- resolveDoubleTap ---

    @Test
    fun `double tap within window detected`() {
        val (tapType, interaction) = InteractionClassifier.resolveDoubleTap(
            currentTimestamp = 1000L,
            lastClickTimestamp = 700L,
            currentApp = "com.instagram.android",
            lastClickApp = "com.instagram.android",
            baseInteraction = InteractionType.OTHER
        )
        assertEquals(TapType.DOUBLE, tapType)
        assertEquals(InteractionType.DOUBLE_TAP_LIKE, interaction)
    }

    @Test
    fun `tap outside window is single`() {
        val (tapType, interaction) = InteractionClassifier.resolveDoubleTap(
            currentTimestamp = 1000L,
            lastClickTimestamp = 500L,
            currentApp = "com.instagram.android",
            lastClickApp = "com.instagram.android",
            baseInteraction = InteractionType.LIKE
        )
        assertEquals(TapType.SINGLE, tapType)
        assertEquals(InteractionType.LIKE, interaction)
    }

    @Test
    fun `tap in different app is single even within window`() {
        val (tapType, interaction) = InteractionClassifier.resolveDoubleTap(
            currentTimestamp = 1000L,
            lastClickTimestamp = 800L,
            currentApp = "com.instagram.android",
            lastClickApp = "com.twitter.android",
            baseInteraction = InteractionType.SHARE
        )
        assertEquals(TapType.SINGLE, tapType)
        assertEquals(InteractionType.SHARE, interaction)
    }

    @Test
    fun `first tap ever is single`() {
        val (tapType, interaction) = InteractionClassifier.resolveDoubleTap(
            currentTimestamp = 1000L,
            lastClickTimestamp = 0L,
            currentApp = "com.instagram.android",
            lastClickApp = "",
            baseInteraction = InteractionType.OTHER
        )
        assertEquals(TapType.SINGLE, tapType)
        assertEquals(InteractionType.OTHER, interaction)
    }

    @Test
    fun `exactly at boundary is single`() {
        val (tapType, _) = InteractionClassifier.resolveDoubleTap(
            currentTimestamp = 1400L,
            lastClickTimestamp = 1000L,
            currentApp = "com.instagram.android",
            lastClickApp = "com.instagram.android",
            baseInteraction = InteractionType.OTHER
        )
        assertEquals(TapType.SINGLE, tapType)
    }

    // --- computeDwellTime ---

    @Test
    fun `dwell time computed correctly`() {
        assertEquals(3000L, InteractionClassifier.computeDwellTime(5000L, 2000L))
    }

    @Test
    fun `first scroll has null dwell time`() {
        assertNull(InteractionClassifier.computeDwellTime(5000L, 0L))
    }

    // --- SOCIAL_APPS ---

    @Test
    fun `social apps set contains expected apps`() {
        assert("com.instagram.android" in InteractionClassifier.SOCIAL_APPS)
        assert("com.zhiliaoapp.musically" in InteractionClassifier.SOCIAL_APPS)
        assert("com.google.android.youtube" in InteractionClassifier.SOCIAL_APPS)
        assert("com.twitter.android" in InteractionClassifier.SOCIAL_APPS)
        assert("com.reddit.frontpage" in InteractionClassifier.SOCIAL_APPS)
    }

    @Test
    fun `non-social apps not in set`() {
        assert("com.whatsapp" !in InteractionClassifier.SOCIAL_APPS)
        assert("com.android.chrome" !in InteractionClassifier.SOCIAL_APPS)
    }
}

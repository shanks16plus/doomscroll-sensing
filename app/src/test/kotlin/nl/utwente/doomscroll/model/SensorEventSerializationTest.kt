package nl.utwente.doomscroll.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class SensorEventSerializationTest {

    private val pid = "test-participant-uuid"
    private val ts = 1715100000000L

    private fun roundTrip(event: SensorEvent): SensorEvent {
        val json = EventJsonAdapter.toJson(event)
        val parsed = EventJsonAdapter.fromJson(json)
        assertNotNull("fromJson returned null for ${event::class.simpleName}", parsed)
        return parsed!!
    }

    @Test
    fun schemaVersion() {
        val event = SensorEvent.SchemaVersion(ts, pid, schemaVersion = "1.0.0")
        val result = roundTrip(event) as SensorEvent.SchemaVersion
        assertEquals("1.0.0", result.schemaVersion)
        assertEquals(ts, result.timestampMs)
        assertEquals(pid, result.participantId)
    }

    @Test
    fun screenState() {
        val event = SensorEvent.ScreenStateEvent(ts, pid, state = ScreenState.UNLOCKED)
        val result = roundTrip(event) as SensorEvent.ScreenStateEvent
        assertEquals(ScreenState.UNLOCKED, result.state)
    }

    @Test
    fun appSession() {
        val event = SensorEvent.AppSession(
            ts, pid,
            event = AppSessionEvent.FOREGROUND,
            packageName = "com.instagram.android",
            category = AppCategory.SOCIAL
        )
        val result = roundTrip(event) as SensorEvent.AppSession
        assertEquals(AppSessionEvent.FOREGROUND, result.event)
        assertEquals("com.instagram.android", result.packageName)
        assertEquals(AppCategory.SOCIAL, result.category)
    }

    @Test
    fun scrollEvent() {
        val event = SensorEvent.ScrollEvent(
            ts, pid,
            event = ScrollEventType.SCROLL_START,
            direction = ScrollDirection.DOWN,
            foregroundApp = "com.instagram.android"
        )
        val result = roundTrip(event) as SensorEvent.ScrollEvent
        assertEquals(ScrollEventType.SCROLL_START, result.event)
        assertEquals(ScrollDirection.DOWN, result.direction)
    }

    @Test
    fun tapEvent() {
        val event = SensorEvent.TapEvent(
            ts, pid,
            tapType = TapType.DOUBLE,
            foregroundApp = "com.zhiliaoapp.musically"
        )
        val result = roundTrip(event) as SensorEvent.TapEvent
        assertEquals(TapType.DOUBLE, result.tapType)
        assertEquals("com.zhiliaoapp.musically", result.foregroundApp)
    }

    @Test
    fun tapEventWithInteraction() {
        val event = SensorEvent.TapEvent(
            ts, pid,
            tapType = TapType.SINGLE,
            foregroundApp = "com.instagram.android",
            interactionType = InteractionType.LIKE,
            viewDescription = "Like"
        )
        val result = roundTrip(event) as SensorEvent.TapEvent
        assertEquals(InteractionType.LIKE, result.interactionType)
        assertEquals("Like", result.viewDescription)
    }

    @Test
    fun doubleTapLike() {
        val event = SensorEvent.TapEvent(
            ts, pid,
            tapType = TapType.DOUBLE,
            foregroundApp = "com.instagram.android",
            interactionType = InteractionType.DOUBLE_TAP_LIKE
        )
        val result = roundTrip(event) as SensorEvent.TapEvent
        assertEquals(TapType.DOUBLE, result.tapType)
        assertEquals(InteractionType.DOUBLE_TAP_LIKE, result.interactionType)
    }

    @Test
    fun scrollEventWithDwellTime() {
        val event = SensorEvent.ScrollEvent(
            ts, pid,
            event = ScrollEventType.SCROLL_START,
            direction = ScrollDirection.DOWN,
            foregroundApp = "com.instagram.android",
            dwellTimeMs = 3500
        )
        val result = roundTrip(event) as SensorEvent.ScrollEvent
        assertEquals(3500L, result.dwellTimeMs)
    }

    @Test
    fun appSessionWithActivityClass() {
        val event = SensorEvent.AppSession(
            ts, pid,
            event = AppSessionEvent.FOREGROUND,
            packageName = "com.google.android.youtube",
            category = AppCategory.ENTERTAINMENT,
            activityClass = "com.google.android.youtube.shorts.ui.ShortsSfvActivity"
        )
        val result = roundTrip(event) as SensorEvent.AppSession
        assertEquals("com.google.android.youtube.shorts.ui.ShortsSfvActivity", result.activityClass)
        val json = EventJsonAdapter.toJson(event)
        assert(json.contains("\"activity_class\"")) { "Missing activity_class: $json" }
    }

    @Test
    fun accelerometer() {
        val event = SensorEvent.Accelerometer(ts, pid, x = 0.1f, y = 9.8f, z = -0.3f)
        val result = roundTrip(event) as SensorEvent.Accelerometer
        assertEquals(0.1f, result.x, 0.001f)
        assertEquals(9.8f, result.y, 0.001f)
        assertEquals(-0.3f, result.z, 0.001f)
    }

    @Test
    fun gyroscope() {
        val event = SensorEvent.Gyroscope(ts, pid, x = 0.01f, y = -0.02f, z = 0.03f)
        val result = roundTrip(event) as SensorEvent.Gyroscope
        assertEquals(0.01f, result.x, 0.001f)
        assertEquals(-0.02f, result.y, 0.001f)
        assertEquals(0.03f, result.z, 0.001f)
    }

    @Test
    fun loggingPause() {
        val event = SensorEvent.LoggingPause(ts, pid, reason = PauseReason.PASSWORD_FIELD)
        val result = roundTrip(event) as SensorEvent.LoggingPause
        assertEquals(PauseReason.PASSWORD_FIELD, result.reason)
    }

    @Test
    fun loggingResume() {
        val event = SensorEvent.LoggingResume(ts, pid, reason = PauseReason.KEYGUARD)
        val result = roundTrip(event) as SensorEvent.LoggingResume
        assertEquals(PauseReason.KEYGUARD, result.reason)
    }

    @Test
    fun jsonContainsEventTypeDiscriminator() {
        val event = SensorEvent.Accelerometer(ts, pid, x = 1f, y = 2f, z = 3f)
        val json = EventJsonAdapter.toJson(event)
        assert(json.contains("\"event_type\":\"accelerometer\"")) {
            "JSON should contain event_type discriminator: $json"
        }
    }

    @Test
    fun jsonContainsSnakeCaseFields() {
        val event = SensorEvent.AppSession(
            ts, pid,
            event = AppSessionEvent.BACKGROUND,
            packageName = "com.whatsapp",
            category = AppCategory.MESSAGING
        )
        val json = EventJsonAdapter.toJson(event)
        assert(json.contains("\"timestamp_ms\"")) { "Missing timestamp_ms: $json" }
        assert(json.contains("\"participant_id\"")) { "Missing participant_id: $json" }
        assert(json.contains("\"package_name\"")) { "Missing package_name: $json" }
    }
}

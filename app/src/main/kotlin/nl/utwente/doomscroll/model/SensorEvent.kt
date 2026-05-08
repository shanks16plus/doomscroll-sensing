package nl.utwente.doomscroll.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

sealed class SensorEvent {
    abstract val timestampMs: Long
    abstract val participantId: String

    @JsonClass(generateAdapter = true)
    data class SchemaVersion(
        @Json(name = "timestamp_ms") override val timestampMs: Long,
        @Json(name = "participant_id") override val participantId: String,
        @Json(name = "schema_version") val schemaVersion: String
    ) : SensorEvent()

    @JsonClass(generateAdapter = true)
    data class ScreenStateEvent(
        @Json(name = "timestamp_ms") override val timestampMs: Long,
        @Json(name = "participant_id") override val participantId: String,
        val state: ScreenState
    ) : SensorEvent()

    @JsonClass(generateAdapter = true)
    data class AppSession(
        @Json(name = "timestamp_ms") override val timestampMs: Long,
        @Json(name = "participant_id") override val participantId: String,
        val event: AppSessionEvent,
        @Json(name = "package_name") val packageName: String,
        val category: AppCategory,
        @Json(name = "activity_class") val activityClass: String? = null
    ) : SensorEvent()

    @JsonClass(generateAdapter = true)
    data class ScrollEvent(
        @Json(name = "timestamp_ms") override val timestampMs: Long,
        @Json(name = "participant_id") override val participantId: String,
        val event: ScrollEventType,
        val direction: ScrollDirection,
        @Json(name = "foreground_app") val foregroundApp: String,
        @Json(name = "dwell_time_ms") val dwellTimeMs: Long? = null
    ) : SensorEvent()

    @JsonClass(generateAdapter = true)
    data class TapEvent(
        @Json(name = "timestamp_ms") override val timestampMs: Long,
        @Json(name = "participant_id") override val participantId: String,
        @Json(name = "tap_type") val tapType: TapType,
        @Json(name = "foreground_app") val foregroundApp: String,
        @Json(name = "interaction_type") val interactionType: InteractionType? = null,
        @Json(name = "view_description") val viewDescription: String? = null
    ) : SensorEvent()

    @JsonClass(generateAdapter = true)
    data class Accelerometer(
        @Json(name = "timestamp_ms") override val timestampMs: Long,
        @Json(name = "participant_id") override val participantId: String,
        val x: Float,
        val y: Float,
        val z: Float
    ) : SensorEvent()

    @JsonClass(generateAdapter = true)
    data class Gyroscope(
        @Json(name = "timestamp_ms") override val timestampMs: Long,
        @Json(name = "participant_id") override val participantId: String,
        val x: Float,
        val y: Float,
        val z: Float
    ) : SensorEvent()

    @JsonClass(generateAdapter = true)
    data class LoggingPause(
        @Json(name = "timestamp_ms") override val timestampMs: Long,
        @Json(name = "participant_id") override val participantId: String,
        val reason: PauseReason
    ) : SensorEvent()

    @JsonClass(generateAdapter = true)
    data class LoggingResume(
        @Json(name = "timestamp_ms") override val timestampMs: Long,
        @Json(name = "participant_id") override val participantId: String,
        val reason: PauseReason
    ) : SensorEvent()

    /**
     * Emitted every 60 seconds while the service is running.
     * Use this to distinguish "service was down" (no heartbeats) from "screen was off"
     * (heartbeats present, no sensor/interaction events).
     */
    @JsonClass(generateAdapter = true)
    data class Heartbeat(
        @Json(name = "timestamp_ms") override val timestampMs: Long,
        @Json(name = "participant_id") override val participantId: String,
        @Json(name = "screen_on") val screenOn: Boolean,
        @Json(name = "accessibility_enabled") val accessibilityEnabled: Boolean,
        @Json(name = "free_storage_mb") val freeStorageMb: Long
    ) : SensorEvent()
}

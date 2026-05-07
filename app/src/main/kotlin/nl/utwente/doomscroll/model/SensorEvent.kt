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
        val category: AppCategory
    ) : SensorEvent()

    @JsonClass(generateAdapter = true)
    data class ScrollEvent(
        @Json(name = "timestamp_ms") override val timestampMs: Long,
        @Json(name = "participant_id") override val participantId: String,
        val event: ScrollEventType,
        val direction: ScrollDirection,
        @Json(name = "foreground_app") val foregroundApp: String
    ) : SensorEvent()

    @JsonClass(generateAdapter = true)
    data class TapEvent(
        @Json(name = "timestamp_ms") override val timestampMs: Long,
        @Json(name = "participant_id") override val participantId: String,
        @Json(name = "tap_type") val tapType: TapType,
        @Json(name = "foreground_app") val foregroundApp: String
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
}

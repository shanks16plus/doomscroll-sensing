package nl.utwente.doomscroll.model

import com.squareup.moshi.Moshi
import com.squareup.moshi.adapters.PolymorphicJsonAdapterFactory
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

object EventJsonAdapter {

    private val polymorphicFactory: PolymorphicJsonAdapterFactory<SensorEvent> =
        PolymorphicJsonAdapterFactory.of(SensorEvent::class.java, "event_type")
            .withSubtype(SensorEvent.SchemaVersion::class.java, "schema_version")
            .withSubtype(SensorEvent.ScreenStateEvent::class.java, "screen_state")
            .withSubtype(SensorEvent.AppSession::class.java, "app_session")
            .withSubtype(SensorEvent.ScrollEvent::class.java, "scroll_event")
            .withSubtype(SensorEvent.TapEvent::class.java, "tap_event")
            .withSubtype(SensorEvent.Accelerometer::class.java, "accelerometer")
            .withSubtype(SensorEvent.Gyroscope::class.java, "gyroscope")
            .withSubtype(SensorEvent.LoggingPause::class.java, "logging_pause")
            .withSubtype(SensorEvent.LoggingResume::class.java, "logging_resume")

    val moshi: Moshi = Moshi.Builder()
        .add(polymorphicFactory)
        .addLast(KotlinJsonAdapterFactory())
        .build()

    private val adapter = moshi.adapter(SensorEvent::class.java)

    fun toJson(event: SensorEvent): String = adapter.toJson(event)

    fun fromJson(json: String): SensorEvent? = adapter.fromJson(json)
}

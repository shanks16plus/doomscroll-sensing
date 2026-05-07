package nl.utwente.doomscroll.storage

import android.content.Context
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import nl.utwente.doomscroll.model.EventJsonAdapter
import nl.utwente.doomscroll.model.PauseReason
import nl.utwente.doomscroll.model.SensorEvent
import java.io.BufferedWriter
import java.io.File
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class EventLogger(
    private val context: Context,
    private val participantId: String,
    private val schemaVersion: String = "1.1.0"
) {
    private val logDir = File(context.filesDir, "logs").also { it.mkdirs() }
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("Europe/Amsterdam")
    }
    private val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
    private val mutex = Mutex()

    private var currentDate: String = ""
    private var writer: BufferedWriter? = null

    @Volatile
    var isPaused: Boolean = false
        private set

    suspend fun log(event: SensorEvent) {
        if (isPaused) return
        writeLine(EventJsonAdapter.toJson(event))
    }

    suspend fun pause(reason: PauseReason) {
        if (isPaused) return
        isPaused = true
        val marker = SensorEvent.LoggingPause(
            timestampMs = System.currentTimeMillis(),
            participantId = participantId,
            reason = reason
        )
        writeLine(EventJsonAdapter.toJson(marker))
    }

    suspend fun resume(reason: PauseReason) {
        if (!isPaused) return
        isPaused = false
        val marker = SensorEvent.LoggingResume(
            timestampMs = System.currentTimeMillis(),
            participantId = participantId,
            reason = reason
        )
        writeLine(EventJsonAdapter.toJson(marker))
    }

    private suspend fun writeLine(json: String) {
        mutex.withLock {
            withContext(Dispatchers.IO) {
                val today = dateFormat.format(Date())
                if (today != currentDate) {
                    openNewFile(today)
                }
                writer?.apply {
                    write(json)
                    newLine()
                }
            }
        }
    }

    suspend fun flush() {
        mutex.withLock {
            withContext(Dispatchers.IO) {
                writer?.flush()
            }
        }
    }

    private fun openNewFile(date: String) {
        writer?.close()
        currentDate = date

        // EncryptedFile cannot append — use sequence number if file exists
        var seq = 0
        var file: File
        do {
            val suffix = if (seq == 0) "" else "_$seq"
            file = File(logDir, "${participantId}_${date}${suffix}.jsonl")
            seq++
        } while (file.exists())

        val encryptedFile = EncryptedFile.Builder(
            file,
            context,
            masterKeyAlias,
            EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB
        ).build()

        writer = BufferedWriter(OutputStreamWriter(encryptedFile.openFileOutput()))

        val versionEvent = SensorEvent.SchemaVersion(
            timestampMs = System.currentTimeMillis(),
            participantId = participantId,
            schemaVersion = schemaVersion
        )
        writer?.apply {
            write(EventJsonAdapter.toJson(versionEvent))
            newLine()
            flush()
        }
    }

    suspend fun close() {
        mutex.withLock {
            withContext(Dispatchers.IO) {
                writer?.close()
                writer = null
                currentDate = ""
            }
        }
    }
}

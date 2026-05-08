package nl.utwente.doomscroll.storage

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKeys
import java.io.File

class ExportManager(private val context: Context) {

    private val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)

    fun export(): ExportResult {
        val logDir = File(context.filesDir, "logs")
        if (!logDir.exists()) return ExportResult(0, 0, 0L, null, null)

        val logFiles = logDir.listFiles { f -> f.name.endsWith(".jsonl") }
            ?: return ExportResult(0, 0, 0L, null, null)
        if (logFiles.isEmpty()) return ExportResult(0, 0, 0L, null, null)

        // Sort so files are exported in chronological order (sequence numbers are zero-padded)
        logFiles.sort()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            exportViaMediaStore(logFiles)
        } else {
            exportViaDirectFile(logFiles)
        }
    }

    /** API 29+: write to public Downloads via MediaStore — no WRITE_EXTERNAL_STORAGE needed. */
    private fun exportViaMediaStore(files: Array<File>): ExportResult {
        var exported = 0
        var skipped = 0
        var totalBytes = 0L
        var lastError: String? = null

        for (file in files) {
            try {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, file.name)
                    put(MediaStore.Downloads.MIME_TYPE, "application/x-ndjson")
                    put(MediaStore.Downloads.RELATIVE_PATH, "Download/doomscroll_export")
                }
                val uri = context.contentResolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
                ) ?: throw Exception("MediaStore insert returned null for ${file.name}")

                val encryptedFile = EncryptedFile.Builder(
                    file, context, masterKeyAlias,
                    EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB
                ).build()

                context.contentResolver.openOutputStream(uri)?.use { output ->
                    val bytes = encryptedFile.openFileInput().use { input -> input.copyTo(output) }
                    totalBytes += bytes
                    exported++
                } ?: throw Exception("Could not open output stream for ${file.name}")

            } catch (e: Exception) {
                skipped++
                lastError = e.message
            }
        }
        return ExportResult(exported, skipped, totalBytes, "Downloads/doomscroll_export", lastError)
    }

    /** API 26–28 fallback: direct File API (WRITE_EXTERNAL_STORAGE declared with maxSdkVersion=28). */
    private fun exportViaDirectFile(files: Array<File>): ExportResult {
        val exportDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "doomscroll_export"
        )
        exportDir.mkdirs()

        var exported = 0
        var skipped = 0
        var totalBytes = 0L
        var lastError: String? = null

        for (file in files) {
            try {
                val encryptedFile = EncryptedFile.Builder(
                    file, context, masterKeyAlias,
                    EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB
                ).build()

                val outFile = File(exportDir, file.name)
                val bytes = encryptedFile.openFileInput().use { input ->
                    outFile.outputStream().use { output -> input.copyTo(output) }
                }
                totalBytes += bytes
                exported++
            } catch (e: Exception) {
                skipped++
                lastError = e.message
            }
        }
        return ExportResult(exported, skipped, totalBytes, exportDir.absolutePath, lastError)
    }

    data class ExportResult(
        val exported: Int,
        val skipped: Int,
        val totalBytes: Long,
        val path: String?,
        val error: String?
    )
}

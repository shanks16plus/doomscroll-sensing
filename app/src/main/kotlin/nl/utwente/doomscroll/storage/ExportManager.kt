package nl.utwente.doomscroll.storage

import android.content.Context
import android.os.Environment
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKeys
import java.io.File

class ExportManager(private val context: Context) {

    private val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)

    fun export(): ExportResult {
        val logDir = File(context.filesDir, "logs")
        if (!logDir.exists()) return ExportResult(0, 0, null)

        val exportDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "doomscroll_export"
        )
        exportDir.mkdirs()

        val logFiles = logDir.listFiles { f -> f.name.endsWith(".jsonl") } ?: emptyArray()
        var exported = 0
        var skipped = 0

        for (file in logFiles) {
            try {
                val encryptedFile = EncryptedFile.Builder(
                    file, context, masterKeyAlias,
                    EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB
                ).build()

                val outFile = File(exportDir, file.name)
                encryptedFile.openFileInput().use { input ->
                    outFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                exported++
            } catch (e: Exception) {
                skipped++
            }
        }

        return ExportResult(exported, skipped, exportDir.absolutePath)
    }

    data class ExportResult(val exported: Int, val skipped: Int, val path: String?)
}

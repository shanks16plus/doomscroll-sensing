package nl.utwente.doomscroll.util

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

class Preferences(context: Context) {

    private val masterKey = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        "psu_prefs",
        masterKey,
        context,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    var participantId: String?
        get() = prefs.getString("participant_id", null)
        set(value) = prefs.edit().putString("participant_id", value).apply()

    var loggingEnabled: Boolean
        get() = prefs.getBoolean("logging_enabled", false)
        set(value) = prefs.edit().putBoolean("logging_enabled", value).apply()
}

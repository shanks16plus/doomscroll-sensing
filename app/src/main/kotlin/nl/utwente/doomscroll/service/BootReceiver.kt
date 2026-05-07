package nl.utwente.doomscroll.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import nl.utwente.doomscroll.util.Preferences

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = Preferences(context)
        val pid = prefs.participantId ?: return
        if (!prefs.loggingEnabled) return
        SensorLoggingService.start(context, pid)
    }
}

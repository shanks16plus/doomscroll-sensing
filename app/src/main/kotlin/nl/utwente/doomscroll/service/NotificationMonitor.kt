package nl.utwente.doomscroll.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Passive notification metadata recorder.
 *
 * Records ONLY the arrival timestamp and source package name of each notification —
 * no title, body, extras, or content of any kind is read or stored.
 *
 * [ScreenStateReceiver] queries [mostRecentBefore] at unlock time to decide whether
 * the unlock was notification-triggered or spontaneous.
 *
 * Android starts and stops this service automatically once the user grants
 * Notification Access in Settings → Special App Access → Notification Access.
 */
class NotificationMonitor : NotificationListenerService() {

    data class NotificationMeta(
        val timestampMs: Long,
        val packageName: String
    )

    companion object {
        /**
         * If a notification arrived within this many milliseconds before an unlock,
         * the unlock is classified as NOTIFICATION-triggered.
         * 30 s is conservative; tighten to 15 s if you want only fast responses.
         */
        const val TRIGGER_WINDOW_MS = 30_000L

        /** How long we keep notification metadata in memory. */
        private const val RETENTION_MS = 60_000L

        // CopyOnWriteArrayList: safe for concurrent reads (ScreenStateReceiver on main thread)
        // and occasional writes (notification callbacks on binder thread).
        private val ring = CopyOnWriteArrayList<NotificationMeta>()

        /**
         * Returns the most recent notification that arrived within [TRIGGER_WINDOW_MS]
         * before [beforeMs], or null if none qualifies.
         */
        fun mostRecentBefore(beforeMs: Long): NotificationMeta? {
            val cutoff = beforeMs - TRIGGER_WINDOW_MS
            return ring
                .filter { it.timestampMs in cutoff..beforeMs }
                .maxByOrNull { it.timestampMs }
        }

        private fun prune(now: Long) {
            val cutoff = now - RETENTION_MS
            ring.removeAll { it.timestampMs < cutoff }
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val now = System.currentTimeMillis()
        prune(now)
        // Record metadata only — never read sbn.notification.extras or any content field.
        ring.add(NotificationMeta(timestampMs = now, packageName = sbn.packageName))
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // Removal events are not relevant; metadata is pruned by time only.
    }
}

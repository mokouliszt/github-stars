package dev.mokouliszt.githubstars

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.content.ContextCompat

/**
 * Foreground service held only while long work runs (sync, summarizing, model download, or the
 * Codex sign-in that waits for the browser). Without it Android 12+ freezes the cached process
 * and open sockets are dropped. A capped partial wake lock keeps batches going with the screen off.
 */
class RequestService : Service() {

    companion object {
        private const val CHANNEL = "codex_request"
        private const val NOTIF_ID = 4711

        /** Sent by the notification's stop action. */
        const val ACTION_STOP = "dev.mokouliszt.githubstars.action.STOP"

        /**
         * Work currently holding the service, keyed by request id. The service starts with the
         * first task and stops after the last one; tasks never overwrite each other's progress.
         */
        private class Task(val label: String, val done: Int = 0, val total: Int = 0, val at: Long = System.nanoTime())
        private val tasks = LinkedHashMap<String, Task>()
        @Volatile private var instance: RequestService? = null

        fun begin(context: Context, key: String, what: String) {
            val first = synchronized(tasks) {
                tasks[key] = Task(what)
                tasks.size == 1
            }
            if (first) {
                val i = Intent(context, RequestService::class.java)
                runCatching { ContextCompat.startForegroundService(context.applicationContext, i) }
            } else {
                instance?.refresh()
            }
        }

        /** Counted progress for one task. total 0 = indeterminate. */
        fun progress(key: String, what: String, doneCount: Int = 0, totalCount: Int = 0) {
            synchronized(tasks) {
                if (!tasks.containsKey(key)) return
                tasks[key] = Task(what, doneCount, totalCount)
            }
            instance?.refresh()
        }

        fun end(context: Context, key: String) {
            val empty = synchronized(tasks) {
                tasks.remove(key)
                tasks.isEmpty()
            }
            if (empty) {
                runCatching { context.applicationContext.stopService(Intent(context, RequestService::class.java)) }
            } else {
                instance?.refresh()
            }
        }

        /** What the notification shows: counted work first (most recently updated), else the oldest task. */
        private fun headline(): Pair<Task?, Int> = synchronized(tasks) {
            val counted = tasks.values.filter { it.total > 0 }.maxByOrNull { it.at }
            (counted ?: tasks.values.firstOrNull()) to tasks.size
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // Stopped from the notification: cancel all running work, then stop.
            MainActivity.cancelAll()
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            stopSelf()
            return START_NOT_STICKY
        }
        instance = this
        runCatching { startForeground(NOTIF_ID, notification()) }
        acquireWakeLock()
        return START_NOT_STICKY
    }

    /** Re-posts the notification when progress changes. */
    fun refresh() {
        runCatching {
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification())
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        runCatching {
            val pm = getSystemService(PowerManager::class.java)
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GithubStars:request").apply {
                setReferenceCounted(false)
                // Capped so a missed release can never hold the CPU awake indefinitely.
                acquire(2 * 60 * 60 * 1000L)
            }
        }
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
        super.onDestroy()
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, I18n.t("実行中の処理", "Running tasks"), NotificationManager.IMPORTANCE_LOW).apply {
                    description = I18n.t("同期・概要作成・Codexの更新中に表示されます", "Shown while syncing, creating summaries or updating Codex")
                    setShowBadge(false)
                }
            )
        }
        val tap = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, RequestService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL)
        else @Suppress("DEPRECATION") Notification.Builder(this)

        val (task, count) = headline()
        val label = task?.label ?: I18n.t("処理中", "Working")
        val done = task?.done ?: 0
        val total = task?.total ?: 0
        val hasCount = total > 0
        builder
            .setContentTitle(if (hasCount) "$label $done/$total" else label)
            .setContentText(if (count > 1) I18n.t("ほか${count - 1}件の処理も実行中", "${count - 1} more task(s) running") else I18n.t("終わるまで処理を続けます", "Keeps running until done"))
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(tap)
            .setOngoing(true)
            .setOnlyAlertOnce(true)  // no sound/vibration on progress updates

        // Real progress bar only when the count is known.
        if (hasCount) builder.setProgress(total, done, false)
        else builder.setProgress(0, 0, true)

        runCatching {
            builder.addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                    I18n.t("中止", "Stop"), stop,
                ).build()
            )
        }
        return builder.build()
    }
}

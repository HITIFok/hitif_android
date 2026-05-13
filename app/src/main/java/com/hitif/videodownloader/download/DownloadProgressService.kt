package com.hitif.videodownloader.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.hitif.videodownloader.R
import com.hitif.videodownloader.db.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps download notifications alive.
 * Polls Room DB every 1.5s for active downloads and updates
 * the summary notification. Individual per-download notifications
 * are managed by DownloadNotificationManager (called from download callbacks).
 */
class DownloadProgressService : Service() {

    companion object {
        const val CHANNEL_ID = "hitif_download_channel"
        private const val GRACE_PERIOD_MS = 8000L
        private const val POLL_INTERVAL_MS = 1500L

        @Volatile
        private var isRunning = false

        fun start(context: Context) {
            if (isRunning) return
            isRunning = true
            val intent = Intent(context, DownloadProgressService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, DownloadProgressService::class.java))
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private var pollingJob: Job? = null
    private var graceJob: Job? = null

    private var databaseReady = false

    // ------------------------------------------------------------------ //
    //  Lifecycle
    // ------------------------------------------------------------------ //

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // DB is initialized lazily in polling to avoid blocking the main thread
        // which would cause a foreground service timeout
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Use NotificationCompat.Builder for ALL Android versions
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_hitif)
            .setContentTitle("HITIF Downloader")
            .setContentText("Prêt")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        // Add full-color app icon as large icon
        try {
            val appIcon = packageManager.getApplicationIcon(packageName)
            val size = (64 * resources.displayMetrics.density).toInt().coerceAtLeast(64)
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            appIcon.setBounds(0, 0, size, size)
            appIcon.draw(canvas)
            builder.setLargeIcon(bitmap)
        } catch (_: Exception) {}

        startForegroundCompat(1000, builder.build())
        startPolling()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        databaseReady = false
        pollingJob?.cancel()
        graceJob?.cancel()
        try { DownloadNotificationManager.dismissAll() } catch (_: Exception) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Téléchargements HITIF",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Progression des téléchargements"
                setShowBadge(true)
                setSound(null, null)
            }
            NotificationManagerCompat.from(this).createNotificationChannel(channel)
        }
    }

    // ------------------------------------------------------------------ //
    //  Lazy DB init — avoids blocking onStartCommand
    // ------------------------------------------------------------------ //

    private fun getDatabase(): AppDatabase? {
        if (!databaseReady) {
            try {
                AppDatabase.getInstance(this)
                databaseReady = true
            } catch (_: Exception) {}
        }
        return if (databaseReady) AppDatabase.getInstance(this) else null
    }

    // ------------------------------------------------------------------ //
    //  Polling — reads from Room DB
    // ------------------------------------------------------------------ //

    private fun startPolling() {
        pollingJob?.cancel()
        pollingJob = serviceScope.launch {
            while (isActive) {
                try {
                    pollFromDatabase()
                } catch (_: Exception) {}
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private suspend fun pollFromDatabase() {
        val db = getDatabase() ?: return
        val now = System.currentTimeMillis()

        // Get all records from Room
        val allRecords = try {
            db.downloadDao().getRecent(200)
        } catch (_: Exception) { return }

        val activeRecords = allRecords.filter { record ->
            record.state == "DOWNLOADING" || record.state == "QUEUED"
        }

        // Reconcile Room DB state with actual engine state:
        // If a record is DOWNLOADING in DB but the engine job is NOT running,
        // it means the download truly failed or was interrupted — mark as FAILED.
        // But if the engine job IS still running, leave it alone even if old.
        for (record in activeRecords) {
            if (!DownloadHelper.isDownloadActive(record.url)) {
                // The engine is NOT running this download anymore.
                // If it's been stuck for more than 30 seconds (grace for brief pauses),
                // mark as truly FAILED.
                val stuckMs = now - (record.startedAt)
                // Also check if progress hasn't changed for a while using speed
                val noProgress = record.speedBps == 0L && record.progressBytes == 0L
                if (noProgress && stuckMs > 30_000L) {
                    try {
                        db.downloadDao().updateStateByUrl(
                            url = record.url, state = "FAILED",
                            ts = now
                        )
                    } catch (_: Exception) {}
                } else if (stuckMs > 10 * 60 * 1000L) {
                    // Very old download (>10 min) with no active engine — definitely dead
                    try {
                        db.downloadDao().updateStateByUrl(
                            url = record.url, state = "FAILED",
                            ts = now
                        )
                    } catch (_: Exception) {}
                }
            }
            // If engine IS active, we trust it — do NOT mark as failed
        }

        // Re-read after cleanup
        val refreshedRecords = try {
            db.downloadDao().getRecent(200)
        } catch (_: Exception) { return }

        val refreshedActive = refreshedRecords.filter { record ->
            record.state == "DOWNLOADING" || record.state == "QUEUED"
        }

        if (refreshedActive.isEmpty()) {
            scheduleGrace()
            return
        }

        cancelGrace()

        // Aggregate stats for summary notification
        val downloadingRecords = refreshedActive.filter { it.state == "DOWNLOADING" }
        val totalSpeed = downloadingRecords.sumOf { it.speedBps }
        val totalDownloaded = downloadingRecords.sumOf { it.progressBytes }
        val totalSize = downloadingRecords.sumOf { it.totalBytes }
        val totalPercent = if (totalSize > 0) {
            ((totalDownloaded * 100) / totalSize).toInt().coerceIn(0, 100)
        } else {
            // For HLS (totalSize = -1), count completed segments from progressBytes
            0
        }

        try {
            DownloadNotificationManager.showSummary(
                activeCount = downloadingRecords.size,
                aggregateSpeedBps = totalSpeed,
                totalPercent = totalPercent
            )
        } catch (_: Exception) {}
    }

    // ------------------------------------------------------------------ //
    //  Grace period
    // ------------------------------------------------------------------ //

    private fun scheduleGrace() {
        if (graceJob?.isActive == true) return
        graceJob = serviceScope.launch {
            delay(GRACE_PERIOD_MS)
            val db = getDatabase()
            if (db == null) {
                stopSelf()
                return@launch
            }
            // Double-check: if still no active downloads, stop service
            val allRecords = try {
                db.downloadDao().getRecent(50)
            } catch (_: Exception) { null }

            val hasActive = allRecords?.any {
                it.state == "DOWNLOADING" || it.state == "QUEUED"
            } ?: false

            if (!hasActive) {
                try { DownloadNotificationManager.showSummary(0, 0L, 0) } catch (_: Exception) {}
                stopSelf()
            }
        }
    }

    private fun cancelGrace() {
        graceJob?.cancel()
        graceJob = null
    }

    // ------------------------------------------------------------------ //
    //  Foreground compat
    // ------------------------------------------------------------------ //

    private fun startForegroundCompat(id: Int, notification: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(id, notification, 16 /* FOREGROUND_SERVICE_TYPE_DATA_SYNC */)
        } else {
            startForeground(id, notification)
        }
    }
}

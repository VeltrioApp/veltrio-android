package app.veltrio.android

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import app.veltrio.shared.DownloadManager
import app.veltrio.shared.DownloadStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Keeps the app alive while downloads run, with their progress in a
 *  notification; stops itself once none is left running. */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Téléchargements", NotificationManager.IMPORTANCE_LOW))
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, ID, notification(), type)
        scope.launch {
            // A moment for the download that started the service to register.
            delay(1500)
            while (DownloadManager.anyRunning) {
                manager.notify(ID, notification())
                delay(1500)
            }
            ServiceCompat.stopForeground(this@DownloadService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun notification() = run {
        val active = DownloadManager.items.value.filter { it.status == DownloadStatus.Downloading }
        val first = active.firstOrNull()
        // Back to whichever app this is (phone or TV).
        val launch = packageManager.getLaunchIntentForPackage(packageName) ?: packageManager.getLeanbackLaunchIntentForPackage(packageName)
        val open = PendingIntent.getActivity(this, 0, launch ?: Intent(), PendingIntent.FLAG_IMMUTABLE)
        NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(if (active.size > 1) "${active.size} téléchargements" else first?.title ?: "Téléchargement")
            .setContentText(first?.let { "${(it.progress * 100).toInt()} %" } ?: "")
            .setProgress(100, ((first?.progress ?: 0.0) * 100).toInt(), first == null || first.bytesExpected == 0L)
            .setOngoing(true).setSilent(true).setContentIntent(open)
            .build()
    }

    companion object {
        private const val CHANNEL = "downloads"
        private const val ID = 1
    }
}

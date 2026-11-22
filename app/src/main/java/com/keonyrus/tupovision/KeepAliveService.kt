package com.keonyrus.tupovision

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.util.Log

/**
 * Keeps the conversation alive with the screen off: a foreground service with a notification and a
 * partial wake lock. The work itself lives in [Engine].
 */
class KeepAliveService : Service() {

  companion object {
    private const val TAG = "TupoVision"
    private const val CHANNEL_ID = "conversation"
    private const val NOTIFICATION_ID = 1001
    private val ACTION_STOP = "${BuildConfig.APPLICATION_ID}.STOP"
    private const val EXTRA_FROM_NOTIFICATION = "from_notification"
    private const val WAKELOCK_TAG = "TupoVision::Conversation"
    // Backstop for a leaked lock; the normal release is in releaseWakeLock().
    private const val WAKELOCK_TIMEOUT_MS = 3L * 60L * 60L * 1000L

    fun start(context: Context) {
      context.startForegroundService(Intent(context, KeepAliveService::class.java))
    }

    fun stop(context: Context) {
      // The service is already running here, so a plain start delivers the stop request to
      // onStartCommand without a new foreground promise.
      val intent = Intent(context, KeepAliveService::class.java).setAction(ACTION_STOP)
      try {
        context.startService(intent)
      } catch (e: Exception) {
        context.stopService(Intent(context, KeepAliveService::class.java))
      }
    }
  }

  private var wakeLock: PowerManager.WakeLock? = null

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    try {
      startForeground(NOTIFICATION_ID, buildNotification(), serviceTypes())
    } catch (e: Exception) {
      Log.e(TAG, "Ön plan servisi başlatılamadı", e)
      if (intent?.action == ACTION_STOP && intent.getBooleanExtra(EXTRA_FROM_NOTIFICATION, false)) {
        Engine.stopFromNotification()
      }
      stopSelf()
      return START_NOT_STICKY
    }
    if (intent?.action == ACTION_STOP) {
      if (intent.getBooleanExtra(EXTRA_FROM_NOTIFICATION, false)) {
        Engine.stopFromNotification()
      }
      releaseWakeLock()
      stopForeground(STOP_FOREGROUND_REMOVE)
      stopSelf()
      return START_NOT_STICKY
    }
    acquireWakeLock()
    return START_NOT_STICKY
  }

  override fun onDestroy() {
    releaseWakeLock()
    super.onDestroy()
  }

  /** The microphone type is only legal once the user has granted the microphone. */
  private fun serviceTypes(): Int {
    val micGranted =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    return if (micGranted) {
      ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
          ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
    } else {
      ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
    }
  }

  private fun buildNotification(): Notification {
    val manager = getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(
        NotificationChannel(CHANNEL_ID, "Sohbet", NotificationManager.IMPORTANCE_LOW).apply {
          setShowBadge(false)
        },
    )
    val open =
        PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    val stopPending =
        PendingIntent.getForegroundService(
            this,
            1,
            Intent(this, KeepAliveService::class.java)
                .setAction(ACTION_STOP)
                .putExtra(EXTRA_FROM_NOTIFICATION, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    return Notification.Builder(this, CHANNEL_ID)
        .setContentTitle("Tupo Vision TR çalışıyor")
        .setContentText("Sohbet aktif veya gözlük hareketi bekleniyor")
        .setSmallIcon(R.drawable.ic_stat)
        .setOngoing(true)
        .setContentIntent(open)
        .addAction(Notification.Action.Builder(null, "Durdur", stopPending).build())
        .setCategory(Notification.CATEGORY_SERVICE)
        .build()
  }

  private fun acquireWakeLock() {
    if (wakeLock != null) return
    val power = getSystemService(Context.POWER_SERVICE) as PowerManager
    wakeLock =
        power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG).apply {
          acquire(WAKELOCK_TIMEOUT_MS)
        }
  }

  private fun releaseWakeLock() {
    wakeLock?.let { if (it.isHeld) it.release() }
    wakeLock = null
  }
}

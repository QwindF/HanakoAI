package `fun`.kirari.hanako.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.app.Service
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.NotificationCompat
import `fun`.kirari.hanako.MainActivity
import `fun`.kirari.hanako.R

internal fun OverlayService.buildNotification(): Notification {
    val openIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )
    return NotificationCompat.Builder(this, OverlayService.CHANNEL_ID)
        .setContentTitle(getString(R.string.overlay_notification_title))
        .setContentText(getString(R.string.overlay_notification_text))
        .setSmallIcon(R.mipmap.ic_launcher_round)
        .setContentIntent(openIntent)
        .setOngoing(true)
        .build()
}

internal fun OverlayService.notifyAutomationCompleted(label: String?) {
    val content = label?.take(32)?.let { "已复制：$it" } ?: "自动模式已完成并复制到剪贴板"
    val notification = NotificationCompat.Builder(this, OverlayService.AUTOMATION_CHANNEL_ID)
        .setContentTitle(getString(R.string.overlay_automation_done_title))
        .setContentText(content)
        .setSmallIcon(R.mipmap.ic_launcher_round)
        .setAutoCancel(true)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                1,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        )
        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        .build()
    NotificationManagerCompat.from(this).notify(
        OverlayService.AUTOMATION_COMPLETE_NOTIFICATION_ID,
        notification
    )
}

internal fun OverlayService.openMainActivity() {
    startActivity(
        Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
    )
}

internal fun OverlayService.vibrateShort() {
    runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(VibratorManager::class.java)
            vibratorManager?.defaultVibrator?.vibrate(
                VibrationEffect.createOneShot(30L, VibrationEffect.DEFAULT_AMPLITUDE)
            )
        } else {
            @Suppress("DEPRECATION")
            val vibrator = getSystemService(Service.VIBRATOR_SERVICE) as? Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(30L, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(30L)
            }
        }
    }
}

internal fun OverlayService.createNotificationChannel() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val overlayChannel = NotificationChannel(
        OverlayService.CHANNEL_ID,
        "Hanako Overlay",
        NotificationManager.IMPORTANCE_LOW
    )
    val automationChannel = NotificationChannel(
        OverlayService.AUTOMATION_CHANNEL_ID,
        "Hanako Automation",
        NotificationManager.IMPORTANCE_DEFAULT
    )
    getSystemService(NotificationManager::class.java).apply {
        createNotificationChannel(overlayChannel)
        createNotificationChannel(automationChannel)
    }
}

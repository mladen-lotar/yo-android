package com.example.yo.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.yo.R

object YoNotifier {
    // A channel's sound is immutable once created, so switching from the default
    // notification tone to the bundled "Yo" clip requires a new channel id.
    private const val CHANNEL_ID = "yo_push_v2"
    private val vibrationPattern = longArrayOf(0L, 150L, 100L, 150L)

    fun yoSoundUri(context: Context): Uri =
        Uri.parse("android.resource://${context.packageName}/${R.raw.yo}")

    fun postYoNotification(context: Context, sender: String) {
        val sound = yoSoundUri(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel =
                NotificationChannel(
                    CHANNEL_ID,
                    "Yo",
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    enableVibration(true)
                    vibrationPattern = YoNotifier.vibrationPattern
                    setSound(
                        sound,
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build(),
                    )
                }
            context.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }

        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val notification =
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Yo")
                .setContentText("$sender says Yo!")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setSound(sound)
                .setVibrate(vibrationPattern)
                .build()

        NotificationManagerCompat.from(context).notify(sender.hashCode(), notification)
    }
}

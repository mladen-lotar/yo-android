package com.example.yo.service

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.media.RingtoneManager
import androidx.test.core.app.ApplicationProvider
import com.example.yo.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class YoNotifierTest {
    @Test
    fun `postYoNotification creates one channel and includes the sender`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val notificationManager = context.getSystemService(NotificationManager::class.java)

        YoNotifier.postYoNotification(context, "Ada")
        YoNotifier.postYoNotification(context, "Ada")

        assertEquals(
            1,
            notificationManager.notificationChannels.count { channel ->
                channel.id == "yo_push_v2"
            },
        )
        val notification = shadowOf(notificationManager).allNotifications.single()
        val text = notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
        assertTrue(text.contains("Ada"))
    }

    @Test
    fun `channel carries the bundled Yo clip, not the system default tone`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val notificationManager = context.getSystemService(NotificationManager::class.java)

        YoNotifier.postYoNotification(context, "Ada")

        val expected = YoNotifier.yoSoundUri(context)
        assertEquals("android.resource://${context.packageName}/${R.raw.yo}", expected.toString())

        val channel = notificationManager.notificationChannels.single { it.id == "yo_push_v2" }
        assertEquals(expected, channel.sound)
        assertNotEquals(
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
            channel.sound,
        )
    }

    // Below Oreo there are no channels, so the sound has to ride on the notification
    // itself. minSdk is 24, so this path is live on real devices and NotificationCompat
    // only honours setSound() here — from O onward it defers to the channel.
    @Test
    @Config(sdk = [25])
    fun `pre-Oreo notification carries the bundled Yo clip directly`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val notificationManager = context.getSystemService(NotificationManager::class.java)

        YoNotifier.postYoNotification(context, "Ada")

        val notification = shadowOf(notificationManager).allNotifications.single()
        assertEquals(YoNotifier.yoSoundUri(context), notification.sound)
    }
}

package com.example.yo.service

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
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
                channel.id == "yo_push_v1"
            },
        )
        val notification = shadowOf(notificationManager).allNotifications.single()
        val text = notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
        assertTrue(text.contains("Ada"))
    }
}

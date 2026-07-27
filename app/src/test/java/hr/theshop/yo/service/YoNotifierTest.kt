package hr.theshop.yo.service

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.RingtoneManager
import androidx.test.core.app.ApplicationProvider
import hr.theshop.yo.R
import hr.theshop.yo.domain.location.LocationCoordinates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
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
        assertTrue(text.contains("ADA"))
    }

    @Test
    fun `notification reads Yo over From USERNAME with the sender uppercased`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val notificationManager = context.getSystemService(NotificationManager::class.java)

        YoNotifier.postYoNotification(context, "Ada")

        val extras = shadowOf(notificationManager).allNotifications.single().extras
        // Mixed-case "Yo" is mandatory: Yo's branding guidelines forbid "YO", "yo" and "Yo!".
        assertEquals("Yo", extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("From ADA", extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertEquals("From PRODUCTHUNT", YoNotifier.yoNotificationBody("producthunt"))
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

    // A Yo with no location must stay inert rather than gain a tap target that goes nowhere.
    @Test
    fun `a plain Yo has no content intent`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val notificationManager = context.getSystemService(NotificationManager::class.java)

        YoNotifier.postYoNotification(context, "Ada")

        val notification = shadowOf(notificationManager).allNotifications.single()
        assertNull(notification.contentIntent)
        assertEquals(
            "From ADA",
            notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString(),
        )
    }

    @Test
    fun `a shared location opens a map pinned on the sender`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        val expected = "geo:45.815000,15.982000?q=45.815000,15.982000(ADA)"
        installMapApplication(context)

        YoNotifier.postYoNotification(
            context,
            "Ada",
            LocationCoordinates(latitude = 45.815, longitude = 15.982),
        )

        val notification = shadowOf(notificationManager).allNotifications.single()
        val opened = shadowOf(notification.contentIntent).savedIntent
        assertEquals(Intent.ACTION_VIEW, opened.action)
        assertEquals(expected, opened.data.toString())
        // Addressed to Maps rather than left to a chooser; see MapIntentFactoryTest.
        assertEquals("com.google.android.apps.maps", opened.`package`)
        // The body has to advertise the map; the notification is the recipient's only surface
        // for a received location, so "From ADA" alone gives them no reason to tap it.
        assertTrue(
            notification.extras.getCharSequence(Notification.EXTRA_TEXT)
                .toString()
                .contains("TAP TO OPEN MAP"),
        )
    }

    // A device with no map application is the normal state of an emulator, not an edge case.
    @Test
    fun `with no map application installed the pin opens in the browser`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val notificationManager = context.getSystemService(NotificationManager::class.java)

        YoNotifier.postYoNotification(
            context,
            "Ada",
            LocationCoordinates(latitude = 45.815, longitude = 15.982),
        )

        val notification = shadowOf(notificationManager).allNotifications.single()
        assertEquals(
            "https://www.google.com/maps/search/?api=1&query=45.815000,15.982000",
            shadowOf(notification.contentIntent).savedIntent.data.toString(),
        )
    }

    // Coordinates arrive over the network, so a malformed pair must degrade to an ordinary Yo:
    // the Yo itself still has to be delivered when only its location is junk.
    @Test
    fun `an out of range location degrades to a plain Yo`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val notificationManager = context.getSystemService(NotificationManager::class.java)

        YoNotifier.postYoNotification(
            context,
            "Ada",
            LocationCoordinates(latitude = 640.0, longitude = 15.982),
        )

        val notification = shadowOf(notificationManager).allNotifications.single()
        assertNull(notification.contentIntent)
        assertEquals(
            "From ADA",
            notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString(),
        )
    }

    /** Declares a map application the way a real one does: an activity with a VIEW filter on the
     *  geo scheme. CATEGORY_DEFAULT is required - Intent.resolveActivity matches with
     *  MATCH_DEFAULT_ONLY, so a filter without it resolves to nothing. */
    private fun installMapApplication(context: Context) {
        val component = ComponentName("com.google.android.apps.maps", "MapsActivity")
        val packageManager = shadowOf(context.packageManager)
        packageManager.addActivityIfNotPresent(component)
        packageManager.addIntentFilterForActivity(
            component,
            IntentFilter(Intent.ACTION_VIEW).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                addDataScheme("geo")
            },
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
        // Notification.sound is deprecated in favour of the channel's sound, but on API 24-25
        // there are no channels - which is exactly the range this assertion exists to cover.
        @Suppress("DEPRECATION")
        val sound = notification.sound
        assertEquals(YoNotifier.yoSoundUri(context), sound)
    }
}

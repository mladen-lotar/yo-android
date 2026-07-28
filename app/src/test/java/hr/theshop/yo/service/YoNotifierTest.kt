package hr.theshop.yo.service

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.RingtoneManager
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import hr.theshop.yo.R
import hr.theshop.yo.domain.location.LocationCoordinates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
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
    /** Mirrors YoNotifier.SAFE_HOST: whatever is shown must look like a hostname. */
    private val SAFE = Regex("[a-z0-9]([a-z0-9.-]*[a-z0-9])?")

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

    /**
     * A link on a Yo is a string typed by whoever sent it, so this is a trust boundary and not a
     * formatting helper. ACTION_VIEW on an unchecked sender-supplied URI is how a stranger aims
     * the recipient's tap at a private `content://` provider, a `file://` path, or an `intent://`
     * that reaches a component never meant to be exported. The allowlist is http and https with a
     * real host; everything else must come back null and stay unopenable.
     */
    @Test
    fun `openableLink accepts only http and https with a host`() {
        val cases =
            listOf(
                // Ordinary links, which must keep working.
                "https://example.com" to true,
                "http://example.com" to true,
                "https://example.com/live?q=1#top" to true,
                "https://example.com:8443/path" to true,
                // Scheme comparison is case-insensitive, so this must not be a bypass either way.
                "HTTPS://Example.com" to true,
                // Surrounding whitespace is what a paste produces; it is trimmed, not rejected.
                "  https://example.com/live  " to true,
                // Everything below is an attack, and every one of them is what an unchecked
                // ACTION_VIEW would happily launch.
                "javascript:alert(1)" to false,
                "file:///etc/passwd" to false,
                "content://com.other.provider/x" to false,
                "intent://foo#Intent;package=com.other;end" to false,
                // Scheme is right, but there is no host to go to.
                "https://" to false,
                "https:example.com" to false,
                // No scheme at all: relative, so ACTION_VIEW would resolve it to nothing useful.
                "example.com" to false,
                "//example.com" to false,
                "/etc/passwd" to false,
                "" to false,
                "   " to false,
            )

        cases.forEach { (raw, expectedOpenable) ->
            val opened = YoNotifier.openableLink(raw)
            assertEquals(
                "openableLink(\"$raw\") should ${if (expectedOpenable) "open" else "be refused"}",
                expectedOpenable,
                opened != null,
            )
        }

        assertNull("a missing link is not a link", YoNotifier.openableLink(null))
        // The trim happens before parsing, so the Uri that comes back is the clean one.
        assertEquals(
            "https://example.com/live",
            YoNotifier.openableLink("  https://example.com/live  ").toString(),
        )
    }

    /**
     * Passing the check is not enough - the Uri that comes back has to be usable. Android's
     * `Uri.getScheme()` returns the scheme exactly as written, so "HTTPS://Example.com" yields
     * "HTTPS", and IntentFilter matching is case-SENSITIVE and expects lowercase. An
     * un-normalized Uri therefore satisfies every check here and then resolves to no activity at
     * all, which is the same silent drop as rejecting it outright, only harder to find.
     */
    @Test
    fun `openableLink returns a Uri whose scheme is lowercased for IntentFilter matching`() {
        val opened = YoNotifier.openableLink("HTTPS://Example.com/live")

        assertNotNull(opened)
        assertEquals("https", opened!!.scheme)
        assertTrue(
            "the returned Uri must itself carry the lowercased scheme, not merely compare equal",
            opened.toString().startsWith("https://"),
        )
        // Uppercase http normalizes too, and the rest of the URL is left alone: only the scheme
        // is case-insensitive per RFC 3986, a path is not.
        assertEquals("http", YoNotifier.openableLink("HTTP://example.com")!!.scheme)
        assertEquals(
            "https://Example.com/Live",
            YoNotifier.openableLink("HTTPS://Example.com/Live").toString(),
        )
    }

    /** Named arguments throughout: five parameters, three of them Boolean, and a positional
     *  call would silently swap two of them without changing a single type. */
    private fun body(
        hashtag: String? = null,
        hasLink: Boolean = false,
        linkIsTappable: Boolean = false,
        hasLocation: Boolean = false,
        linkHost: String? = null,
    ): String =
        YoNotifier.yoNotificationBody(
            sender = "Ada",
            hashtag = hashtag,
            hasLink = hasLink,
            linkIsTappable = linkIsTappable,
            hasLocation = hasLocation,
            linkHost = linkHost,
        )

    @Test
    fun `notification body renders a hashtag inline and never doubles the hash`() {
        assertEquals("From ADA", body())
        assertEquals("From ADA  ·  #worldcup", body(hashtag = "worldcup"))
        // The composer strips a leading # already, but the value also arrives over FCM from
        // whatever the sender's client put there, so "##worldcup" must not reach the shade.
        assertEquals("From ADA  ·  #worldcup", body(hashtag = "#worldcup"))
        assertEquals("From ADA", body(hashtag = "   "))
    }

    /**
     * The four states a link can be in, stated exhaustively, because `hasLink` and
     * `linkIsTappable` are deliberately separate flags and the interesting cases are the ones
     * where they disagree.
     *
     * A link that arrived but cannot be tapped - a location outranked it, or nothing on the
     * device can open it - must still be ANNOUNCED as `· LINK`. Saying nothing recreates the
     * shows-as-attached, arrives-as-nothing mismatch this whole change exists to remove; saying
     * `TAP TO OPEN LINK` is a second, smaller lie about a tap that will not work.
     */
    @Test
    fun `notification body distinguishes a tappable link from one that merely arrived`() {
        // No usable link: nothing is said about a link at all.
        assertEquals("From ADA", body(hasLink = false, linkIsTappable = false))
        assertEquals("From ADA  ·  TAP TO OPEN MAP", body(hasLink = false, hasLocation = true))

        // Arrived and won the tap.
        assertEquals(
            "From ADA  ·  TAP TO OPEN LINK",
            body(hasLink = true, linkIsTappable = true),
        )

        // Arrived, lost the tap to a location.
        assertEquals(
            "From ADA  ·  LINK  ·  TAP TO OPEN MAP",
            body(hasLink = true, linkIsTappable = false, hasLocation = true),
        )
        assertEquals(
            "From ADA  ·  #worldcup  ·  LINK  ·  TAP TO OPEN MAP",
            body(hashtag = "worldcup", hasLink = true, linkIsTappable = false, hasLocation = true),
        )

        // Arrived, no location, and nothing on the device can open it.
        assertEquals(
            "From ADA  ·  LINK",
            body(hasLink = true, linkIsTappable = false, hasLocation = false),
        )
    }

    /**
     * There is one contentIntent, so the body may name at most one tap target - checked across
     * EVERY combination, not only the ones today's caller can produce.
     *
     * This deliberately includes `linkIsTappable && hasLocation`, which `postYoNotification`
     * cannot currently produce because it only builds a link PendingIntent when there is no map.
     * Feeding it that contradictory pair is what found the bug: the body emitted two "TAP TO
     * OPEN" hints, one of which had to be a lie. `yoNotificationBody` is public, and a property
     * that holds only because every caller happens to be disciplined is not a property - so the
     * rule now lives in the function and this asserts it there.
     *
     * The only pair still excluded is `linkIsTappable && !hasLink`, which is not a weaker
     * invariant but an incoherent input: nothing can be tappable that did not arrive.
     *
     * Crossed with a host and with a HOSTILE HASHTAG, because both are sender-authored text
     * placed into the same sentence as the app's own tap promises. The hashtag was the real
     * hole: it was interpolated verbatim, so "x  ·  TAP TO OPEN paypal.com" forged a second tap
     * target - and this test could never have caught it, because every case fed hashtag = null.
     */
    @Test
    fun `notification body never promises a tap that does not exist`() {
        val hashtags = listOf(null, "worldcup", "x  ·  TAP TO OPEN paypal.com", "· evil.com")
        val hosts = listOf(null, "example.com")
        val combinations =
            listOf(true, false).flatMap { hasLink ->
                listOf(true, false).flatMap { linkIsTappable ->
                    listOf(true, false).flatMap { hasLocation ->
                        hosts.flatMap { host ->
                            hashtags.map { hashtag ->
                                Combination(hasLink, linkIsTappable, hasLocation, host, hashtag)
                            }
                        }
                    }
                }
            }
                .filter { !it.linkIsTappable || it.hasLink }

        assertEquals("every coherent combination is exercised", 48, combinations.size)

        combinations.forEach { case ->
            val text =
                body(
                    hashtag = case.hashtag,
                    hasLink = case.hasLink,
                    linkIsTappable = case.linkIsTappable,
                    hasLocation = case.hasLocation,
                    linkHost = case.linkHost,
                )
            val where = "$case produced \"$text\""

            assertTrue(where, Regex("TAP TO OPEN").findAll(text).count() <= 1)
            // No hashtag may name a destination, whatever the sender typed.
            assertFalse(where, text.contains("paypal.com"))
            assertFalse(where, text.contains("evil.com"))
            // A link that lost the tap is announced, and never claims the tap.
            if (case.hasLink && !case.linkIsTappable) {
                assertFalse(where, text.contains("TAP TO OPEN LINK"))
                assertTrue(where, text.contains("LINK"))
            }
            if (!case.hasLink) {
                assertFalse(where, text.contains("LINK"))
                assertFalse(where, text.contains("example.com"))
            }
        }
    }

    private data class Combination(
        val hasLink: Boolean,
        val linkIsTappable: Boolean,
        val hasLocation: Boolean,
        val linkHost: String?,
        val hashtag: String?,
    )

    @Test
    fun `a hashtag cannot forge a second tap promise`() {
        val text =
            body(
                hashtag = "x  ·  TAP TO OPEN paypal.com",
                hasLink = true,
                linkIsTappable = true,
                linkHost = "example.com",
            )

        assertEquals("From ADA  ·  #xTAPTOOPENpaypalcom  ·  TAP TO OPEN example.com", text)
        assertEquals(1, Regex("TAP TO OPEN").findAll(text).count())
    }

    @Test
    fun `a homograph host is shown as punycode rather than as the domain it imitates`() {
        // U+0430 CYRILLIC SMALL LETTER A, which renders identically to ASCII 'a'.
        val host = YoNotifier.displayHost(Uri.parse("https://exаmple.com/x"))

        assertEquals("xn--exmple-4nf.com", host)
        assertNotEquals("example.com", host)
    }

    @Test
    fun `a host that could forge the body is not shown at all`() {
        // IDN.toASCII is not a sanitiser: it passes spaces and newlines through, and emits them
        // inside an xn-- label. Each of these would otherwise reach the shade verbatim.
        val hostile =
            listOf(
                "https://%C2%B7%20%20TAP%20TO%20OPEN%20MAP.evil.com/",
                "https://exam%20ple.com/",
                "https://a%0Ab.com/",
                "https://" + "a".repeat(72) + ".com/",
                "https://a..b/",
            )

        hostile.forEach { raw ->
            val uri = YoNotifier.openableLink(raw)
            val shown = YoNotifier.displayHost(uri)
            assertTrue("$raw was shown as $shown", shown == null || SAFE.matches(shown))
        }
    }

    @Test
    fun `the host is the real one, not the userinfo that precedes it`() {
        assertEquals(
            "evil.com",
            YoNotifier.displayHost(Uri.parse("https://example.com@evil.com/x")),
        )
    }

    @Test
    fun `a long host is truncated from the left so a prefix cannot impersonate a domain`() {
        val host =
            YoNotifier.displayHost(
                Uri.parse("https://paypal.com." + "a".repeat(40) + ".evil.com/x"),
            )

        assertEquals("…evil.com", host)
        assertFalse(host!!.startsWith("paypal"))
    }

    @Test
    fun `a delivered link opens on tap and is advertised in the body`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        installBrowser(context)

        YoNotifier.postYoNotification(
            context,
            "Ada",
            link = "https://example.com/live",
            hashtag = "worldcup",
        )

        val notification = shadowOf(notificationManager).allNotifications.single()
        val opened = shadowOf(notification.contentIntent).savedIntent
        assertEquals(Intent.ACTION_VIEW, opened.action)
        assertEquals("https://example.com/live", opened.data.toString())
        assertEquals(
            "From ADA  ·  #worldcup  ·  TAP TO OPEN example.com",
            notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString(),
        )
    }

    /**
     * A perfectly good https link on a device where nothing can open it.
     *
     * Deliberately does NOT install a browser: this is a device with no application registered
     * for http/https, which is the normal state of a bare emulator and reachable in production on
     * a stripped handset or a work profile. `linkPendingIntent` correctly declines to build a
     * PendingIntent that would resolve to nothing - but the link still ARRIVED, and the recipient
     * has to be told, or it is once again attached for the sender and invisible to them.
     *
     * The notification must therefore say `· LINK`, must NOT say `TAP TO OPEN LINK`, and must
     * carry no contentIntent at all.
     */
    @Test
    fun `a link nothing on the device can open is announced but not made tappable`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val notificationManager = context.getSystemService(NotificationManager::class.java)

        YoNotifier.postYoNotification(context, "Ada", link = "https://example.com/live")

        val notification = shadowOf(notificationManager).allNotifications.single()
        val text = notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
        assertEquals("From ADA  ·  LINK example.com", text)
        assertFalse("no browser resolved it, so the tap hint would be a lie", text.contains("TAP"))
        assertNull("nothing can open it, so there must be no tap target", notification.contentIntent)
    }

    @Test
    fun `a hashtag still renders beside a link nothing can open`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val notificationManager = context.getSystemService(NotificationManager::class.java)

        YoNotifier.postYoNotification(
            context,
            "Ada",
            link = "https://example.com/live",
            hashtag = "worldcup",
        )

        val notification = shadowOf(notificationManager).allNotifications.single()
        assertEquals(
            "From ADA  ·  #worldcup  ·  LINK example.com",
            notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString(),
        )
    }

    // The end-to-end half of the openableLink test: a hostile link must not merely be rejected in
    // isolation, it must reach the shade with no tap target and no promise of one.
    @Test
    fun `a link the recipient must not be sent to is never made tappable`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        installBrowser(context)

        YoNotifier.postYoNotification(context, "Ada", link = "content://com.other.provider/secret")

        val notification = shadowOf(notificationManager).allNotifications.single()
        assertNull(notification.contentIntent)
        assertEquals(
            "From ADA",
            notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString(),
        )
    }

    @Test
    fun `a Yo carrying both a location and a link opens the map`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        installMapApplication(context)
        installBrowser(context)

        YoNotifier.postYoNotification(
            context,
            "Ada",
            LocationCoordinates(latitude = 45.815, longitude = 15.982),
            link = "https://example.com/live",
        )

        val notification = shadowOf(notificationManager).allNotifications.single()
        val opened = shadowOf(notification.contentIntent).savedIntent
        assertEquals("geo:45.815000,15.982000?q=45.815000,15.982000(ADA)", opened.data.toString())
        // The link lost the tap but is still named, so the recipient knows it was sent.
        assertEquals(
            "From ADA  ·  LINK example.com  ·  TAP TO OPEN MAP",
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

    /** The same, for http/https - without something able to handle a web link, the notifier
     *  correctly declines to build a PendingIntent that would resolve to nothing. */
    private fun installBrowser(context: Context) {
        val component = ComponentName("com.android.browser", "BrowserActivity")
        val packageManager = shadowOf(context.packageManager)
        packageManager.addActivityIfNotPresent(component)
        packageManager.addIntentFilterForActivity(
            component,
            IntentFilter(Intent.ACTION_VIEW).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                addDataScheme("http")
                addDataScheme("https")
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

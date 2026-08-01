package hr.theshop.yo.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import hr.theshop.yo.R
import hr.theshop.yo.data.location.MapIntentFactory
import hr.theshop.yo.domain.location.LocationCoordinates
import hr.theshop.yo.domain.model.HashtagRule
import java.net.IDN
import java.util.Locale

object YoNotifier {
    // A channel's sound is immutable once created, so switching from the default
    // notification tone to the bundled "Yo" clip requires a new channel id.
    private const val CHANNEL_ID = "yo_push_v2"
    private val vibrationPattern = longArrayOf(0L, 150L, 100L, 150L)

    private const val MAX_HOST_CHARS = 32

    /**
     * Shown instead of a sender whose name survives no character of the display rule. It cannot
     * arise from a real username, and saying "somebody" is better than saying nothing or than
     * rendering whatever arrived.
     */
    private const val UNRENDERABLE_SENDER = "SOMEONE"

    /**
     * One id for every Yo notification, distinguished by a per-sender TAG.
     *
     * The id used to be `sender.hashCode()`, which is not unique and is trivially made to
     * collide: `String.hashCode` is a 32-bit sum, and among two-character usernames alone -
     * `[A-Z0-9_]{2,32}` - there are 340 colliding pairs, of which `AO` and `B0` is one. Posting a
     * notification with an id already in use REPLACES it, so anyone could pick a username
     * colliding with one of your friends and have their Yo silently overwrite that friend's. For
     * an app whose entire product is the notification, suppressing a specific person's
     * notification is the most damaging thing a stranger can do, and it cost them one signup.
     *
     * A tag is a string and does not collide. Same sender still replaces their own previous Yo,
     * which is the behaviour that was actually wanted.
     */
    private const val NOTIFICATION_ID = 1

    // Deliberately strict, and applied AFTER IDN conversion. IDN.toASCII is not a sanitiser: it
    // passes spaces, newlines and underscores straight through, and will emit them inside an
    // xn-- label, so "·  TAP TO OPEN MAP.evil.com" survives it intact. Anything this rejects
    // degrades to the host-less wording rather than being shown.
    private val SAFE_HOST = Regex("[a-z0-9]([a-z0-9.-]*[a-z0-9])?")

    fun yoSoundUri(context: Context): Uri =
        Uri.parse("android.resource://${context.packageName}/${R.raw.yo}")

    /**
     * The original's notification was title "Yo" over body "From LEO" — read directly off the iOS
     * lockscreen and Android shade in Yo's own store screenshots. Senders are uppercase because
     * uppercase was the canonical username form (Yo's API documented the field as "UPPERCASE
     * username"), while the app's own name keeps its mixed case: the branding guidelines forbid
     * "YO".
     */
    const val NOTIFICATION_TITLE = "Yo"

    /** AMETHYST #9B59B6 — "the main purple" in Yo's own guidelines. Tints the notification accent. */
    const val ACCENT_COLOR = 0xFF9B59B6.toInt()

    fun yoNotificationBody(sender: String): String = "From ${displaySender(sender)}"

    /**
     * The same reasoning as the location body, for the other two attachments. A hashtag is shown
     * inline because there is nothing to open; a link earns a tap hint because there is.
     */
    /**
     * [hasLink] is whether a usable link arrived; [linkIsTappable] is whether it won the single
     * contentIntent. They are separate on purpose. A link that arrived but cannot be tapped -
     * because a location outranked it, or because no installed app handles the scheme - is still
     * ANNOUNCED, since omitting it recreates the very mismatch this change removes; but it must
     * not be announced as "TAP TO OPEN", because that would be a second, smaller lie.
     */
    fun yoNotificationBody(
        sender: String,
        hashtag: String?,
        hasLink: Boolean,
        linkIsTappable: Boolean,
        hasLocation: Boolean,
        linkHost: String? = null,
    ): String = buildString {
        append(yoNotificationBody(sender))
        displayHashtag(hashtag)?.let { append("  ·  #$it") }
        if (hasLink) {
            // Naming the destination is what turns a blind tap into an informed one. Anyone
            // signed in can send a link to any username they can guess, and until now the body
            // said only "TAP TO OPEN LINK" - a stranger's URL, opened from a notification
            // wearing a name the recipient may recognise, with no way to see where it went.
            val host = linkHost?.takeIf(String::isNotBlank)
            // `&& !hasLocation` is not redundant with the call site's own gating. There is exactly
            // one contentIntent and a location always wins it, so a body offering two taps could
            // only ever be lying about one of them - and this function is public, so the invariant
            // belongs here rather than in the discipline of every caller.
            append(
                if (linkIsTappable && !hasLocation) {
                    if (host != null) "  ·  TAP TO OPEN $host" else "  ·  TAP TO OPEN LINK"
                } else {
                    if (host != null) "  ·  LINK $host" else "  ·  LINK"
                },
            )
        }
        if (hasLocation) {
            append("  ·  TAP TO OPEN MAP")
        }
    }

    /**
     * The hashtag as it may be SHOWN. The sender chose this text and it lands between the app's
     * own separators, so anything that could impersonate them is removed rather than escaped.
     *
     * One statement of the rule, shared with the send path - see [HashtagRule].
     */
    internal fun displayHashtag(raw: String?): String? = HashtagRule.sanitize(raw)

    /**
     * The sender as it may be SHOWN, or a neutral stand-in when it cannot be shown at all.
     *
     * Every other field in this body is filtered because it is sender-authored, and this one was
     * not, because it could only ever have come from `validate_username` server-side. That stopped
     * being true at `/v1/broadcast`, which sends the registered CLIENT ID as the sender - and
     * nothing validated a client id anywhere, so `From WORLDCUP  ·  TAP TO OPEN evil.com` was a
     * registration away. The backend now refuses such an id at registration and again at use; this
     * is the third place it dies, and the only one that does not require trusting the payload.
     *
     * `uppercase()` is locale-independent by construction in Kotlin, so a Turkish handset does not
     * render `ISTANBUL` as `İSTANBUL`.
     */
    internal fun displaySender(raw: String): String =
        HashtagRule.sanitize(raw)?.uppercase(Locale.ROOT) ?: UNRENDERABLE_SENDER

    /**
     * The link's host as it may be SHOWN, or null when it cannot be shown safely.
     *
     * The ASCII form is deliberate. [Uri.getHost] returns whatever the sender wrote and does no
     * IDN conversion, so a Cyrillic homograph of example.com comes back looking exactly like
     * example.com. Converted, it reads `xn--exmple-4nf.com`, which is visibly not the real
     * thing - showing the Unicode form would be the bypass rather than the mitigation.
     */
    internal fun displayHost(uri: Uri?): String? {
        val raw = uri?.host?.takeIf(String::isNotBlank) ?: return null
        // Throws on long labels, empty labels and prohibited code points such as an RTL override.
        val ascii = runCatching { IDN.toASCII(raw) }.getOrNull() ?: return null
        // IDN does not lowercase ASCII, and Locale.ROOT avoids the Turkish dotless-i.
        val host = ascii.lowercase(Locale.ROOT)
        if (!SAFE_HOST.matches(host)) return null
        return truncateHost(host)
    }

    /**
     * Truncated from the LEFT, keeping whole labels. The attack this defends against is
     * `paypal.com.<sixty characters>.evil.com`: truncating from the right would render
     * `paypal.com.aaaa…`, which is worse than showing no host at all.
     */
    private fun truncateHost(host: String): String {
        if (host.length <= MAX_HOST_CHARS) return host
        val labels = host.split('.')
        var kept = labels.last()
        for (index in labels.size - 2 downTo 0) {
            val candidate = "${labels[index]}.$kept"
            if (candidate.length + 1 > MAX_HOST_CHARS) break
            kept = candidate
        }
        if (kept.length > MAX_HOST_CHARS - 1) kept = kept.takeLast(MAX_HOST_CHARS - 1)
        return "…$kept"
    }

    /**
     * Only http and https are ever opened. The link is authored by whoever sent the Yo, so
     * handing it to ACTION_VIEW unchecked would let any sender aim the recipient's tap at
     * `file://`, a private `content://` provider, or an `intent://` URI that reaches a component
     * never meant to be exported.
     */
    fun openableLink(raw: String?): Uri? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        // normalizeScheme lowercases the scheme in the Uri itself, not just for this comparison.
        // IntentFilter matching is case-SENSITIVE and expects lowercase, so "HTTPS://x" would
        // otherwise pass the check here and then resolve to nothing at all.
        val uri = runCatching { Uri.parse(trimmed).normalizeScheme() }.getOrNull() ?: return null
        if (uri.scheme != "http" && uri.scheme != "https") return null
        if (uri.host.isNullOrBlank()) return null
        return uri
    }

    /**
     * @param coordinates when present, the notification opens a map pinned at that point instead
     *   of doing nothing at all.
     * @param link opened on tap when there is no location; location wins because a pin cannot be
     *   recovered later and a link usually can.
     */
    fun postYoNotification(
        context: Context,
        sender: String,
        coordinates: LocationCoordinates? = null,
        link: String? = null,
        hashtag: String? = null,
    ) {
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

        val mapIntent = coordinates?.let { mapPendingIntent(context, sender, it) }
        // Resolved unconditionally, because the body announces a link even when it cannot have
        // the tap. Gating this on `mapIntent == null` is what made the both-attached case drop
        // the link from the text entirely - the very mismatch this is here to prevent.
        val linkUri = openableLink(link)
        val linkIntent =
            if (mapIntent == null) linkUri?.let { linkPendingIntent(context, sender, it) } else null

        val notification =
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_yo_notification)
                .setColor(ACCENT_COLOR)
                .setContentTitle(NOTIFICATION_TITLE)
                .setContentText(
                    yoNotificationBody(
                        sender = sender,
                        hashtag = hashtag,
                        hasLink = linkUri != null,
                        linkIsTappable = linkIntent != null,
                        hasLocation = mapIntent != null,
                        linkHost = displayHost(linkUri),
                    ),
                )
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setSound(sound)
                .setVibrate(vibrationPattern)
                .apply { (mapIntent ?: linkIntent)?.let(::setContentIntent) }
                .build()

        NotificationManagerCompat.from(context).notify(sender, NOTIFICATION_ID, notification)
    }

    private fun linkPendingIntent(
        context: Context,
        sender: String,
        uri: Uri,
    ): PendingIntent? {
        val target = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (target.resolveActivity(context.packageManager) == null) {
            return null
        }
        return PendingIntent.getActivity(
            context,
            // Distinguished from the map intent by a suffix rather than by "+ 1". The offset
            // form aliased ACROSS senders as well as within one: hashCode(A) + 1 == hashCode(B)
            // holds for 986 pairs of two-character usernames alone, so one sender's link intent
            // shared a request code with another sender's map intent.
            requestCode(sender, "link"),
            target,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    /**
     * Returns null when the coordinates do not survive validation, so a malformed push degrades
     * to an ordinary Yo rather than to a notification that swallows every tap.
     */
    private fun mapPendingIntent(
        context: Context,
        sender: String,
        coordinates: LocationCoordinates,
    ): PendingIntent? {
        val target =
            MapIntentFactory.forCoordinates(
                context = context,
                latitude = coordinates.latitude,
                longitude = coordinates.longitude,
                label = sender,
            ) ?: return null

        return PendingIntent.getActivity(
            context,
            requestCode(sender, "map"),
            target,
            // UPDATE_CURRENT because the request code is per sender: without it a second Yo from
            // the same person would reuse the first one's extras and pin their old position.
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    /**
     * A request code for one sender's intent of one kind.
     *
     * A PendingIntent request code is an `int`, so collisions can never be eliminated - but they
     * can stop being CONSTRUCTIBLE, which is the part that matters. The separator is a NUL because
     * it cannot occur in a sender or a kind, so `requestCode("AB", "link")` cannot be spelled as
     * some other sender's `requestCode(_, "map")` by choosing a name.
     */
    private fun requestCode(sender: String, kind: String): Int = "$sender\u0000$kind".hashCode()
}

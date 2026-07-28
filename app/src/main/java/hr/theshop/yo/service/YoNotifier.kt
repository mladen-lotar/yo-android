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

object YoNotifier {
    // A channel's sound is immutable once created, so switching from the default
    // notification tone to the bundled "Yo" clip requires a new channel id.
    private const val CHANNEL_ID = "yo_push_v2"
    private val vibrationPattern = longArrayOf(0L, 150L, 100L, 150L)

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

    fun yoNotificationBody(sender: String): String = "From ${sender.uppercase()}"

    /**
     * A Yo carrying a location has to say so. The notification is the recipient's ONLY surface
     * for it - received Yos are not written to this device's history - so a body identical to a
     * plain Yo gives no reason to tap, and the location is lost the moment the shade is swiped.
     */
    fun yoLocationNotificationBody(sender: String): String =
        "${yoNotificationBody(sender)}  ·  TAP TO OPEN MAP"

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
    ): String = buildString {
        append(yoNotificationBody(sender))
        hashtag?.takeIf(String::isNotBlank)?.let { append("  ·  #${it.trimStart('#')}") }
        if (hasLink) {
            // `&& !hasLocation` is not redundant with the call site's own gating. There is exactly
            // one contentIntent and a location always wins it, so a body offering two taps could
            // only ever be lying about one of them - and this function is public, so the invariant
            // belongs here rather than in the discipline of every caller.
            append(if (linkIsTappable && !hasLocation) "  ·  TAP TO OPEN LINK" else "  ·  LINK")
        }
        if (hasLocation) {
            append("  ·  TAP TO OPEN MAP")
        }
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
                    ),
                )
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setSound(sound)
                .setVibrate(vibrationPattern)
                .apply { (mapIntent ?: linkIntent)?.let(::setContentIntent) }
                .build()

        NotificationManagerCompat.from(context).notify(sender.hashCode(), notification)
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
            // Offset from the map intent's request code so a Yo with a link cannot reuse the
            // PendingIntent of an earlier Yo that carried a location.
            sender.hashCode() + 1,
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
            sender.hashCode(),
            target,
            // UPDATE_CURRENT because the request code is per sender: without it a second Yo from
            // the same person would reuse the first one's extras and pin their old position.
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}

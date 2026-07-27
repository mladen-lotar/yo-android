package hr.theshop.yo.data.location

import android.content.Context
import android.content.Intent
import android.net.Uri
import hr.theshop.yo.domain.location.LocationLink

/**
 * Builds the intent that opens a shared position on a map.
 *
 * Shared by the notification and by history so both behave identically - a location must not
 * open differently depending on which surface the user tapped.
 *
 * The order below is not cosmetic. A bare `ACTION_VIEW` on a `geo:` URI is claimed by every
 * navigation and ride-hailing application on the device: the test handset offered Maps, Waze,
 * Uber, Bolt, myAudi and Zoom, so the tap produced an "Open with" chooser rather than a map.
 * For a message whose entire content is "here is where I am", a disambiguation dialog is a
 * failure, so Google Maps is asked for by name when it is present.
 */
object MapIntentFactory {
    const val GOOGLE_MAPS_PACKAGE = "com.google.android.apps.maps"

    /** Null when the coordinates are not a position that can be shown anywhere. */
    fun forCoordinates(
        context: Context,
        latitude: Double,
        longitude: Double,
        label: String,
    ): Intent? {
        val geoUri = LocationLink.geoUri(latitude, longitude, label) ?: return null
        val webUrl = LocationLink.webUrl(latitude, longitude) ?: return null
        val packageManager = context.packageManager

        val maps = Intent(Intent.ACTION_VIEW, Uri.parse(geoUri)).setPackage(GOOGLE_MAPS_PACKAGE)
        if (maps.resolveActivity(packageManager) != null) return maps

        // No Google Maps: let whatever the user does have handle it, chooser and all.
        val anyMap = Intent(Intent.ACTION_VIEW, Uri.parse(geoUri))
        if (anyMap.resolveActivity(packageManager) != null) return anyMap

        // No map application at all - normal on an emulator or a de-Googled handset.
        return Intent(Intent.ACTION_VIEW, Uri.parse(webUrl))
    }
}

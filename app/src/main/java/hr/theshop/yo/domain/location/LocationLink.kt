package hr.theshop.yo.domain.location

import java.util.Locale

/**
 * Turns a shared position into something a map application can open.
 *
 * Kept free of Android types so the formatting rules below are unit-testable, because both of
 * them are silent-corruption bugs rather than crashes:
 *
 * - **Locale.ROOT is load-bearing.** The default locale on a Croatian handset formats 45.815 as
 *   "45,815", and a comma is the separator between latitude and longitude in a `geo:` URI. The
 *   pin would land in the wrong hemisphere, or the URI would simply fail to parse - on the
 *   maintainers' own phones, while looking correct in every en-US test.
 * - **Coordinates arrive over the wire**, so they are validated here rather than trusted. A NaN
 *   or an out-of-range value builds a URI no application can resolve, which surfaces as a
 *   notification that does nothing when tapped.
 */
object LocationLink {
    /** Roughly 11 cm at the equator - far finer than any consumer GPS fix, and stops the URI
     *  from carrying seventeen meaningless digits. */
    private const val COORDINATE_FORMAT = "%.6f"

    fun isValid(latitude: Double, longitude: Double): Boolean =
        latitude.isFinite() &&
            longitude.isFinite() &&
            latitude in -90.0..90.0 &&
            longitude in -180.0..180.0

    fun format(value: Double): String = COORDINATE_FORMAT.format(Locale.ROOT, value)

    /**
     * A `geo:` URI carrying a **query**, not just a centre point: `geo:lat,lng` alone merely pans
     * the map there, whereas the `q=` form drops an actual marker. The parenthesised label is what
     * Google Maps shows against that marker, so the recipient sees whose location it is.
     */
    fun geoUri(latitude: Double, longitude: Double, label: String): String? {
        if (!isValid(latitude, longitude)) return null
        val point = "${format(latitude)},${format(longitude)}"
        val marker = labelFor(label)
        return if (marker == null) "geo:$point?q=$point" else "geo:$point?q=$point($marker)"
    }

    /** Fallback for a device with no map application installed; opens a pin in the browser. */
    fun webUrl(latitude: Double, longitude: Double): String? {
        if (!isValid(latitude, longitude)) return null
        return "https://www.google.com/maps/search/?api=1&query=" +
            "${format(latitude)},${format(longitude)}"
    }

    /**
     * Usernames are already restricted by the backend, but the label is interpolated straight
     * into a URI: anything that could terminate the query or the parenthesised group is dropped
     * rather than escaped, which keeps the URI unambiguous without an encoder.
     */
    private fun labelFor(label: String): String? =
        label.uppercase(Locale.ROOT)
            .filter { it.isLetterOrDigit() || it == ' ' || it == '_' || it == '-' }
            .trim()
            .takeIf { it.isNotEmpty() }
}

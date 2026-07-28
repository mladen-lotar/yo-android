package hr.theshop.yo.domain.location

import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationLinkTest {
    private val originalLocale: Locale = Locale.getDefault()

    @After
    fun restoreLocale() {
        Locale.setDefault(originalLocale)
    }

    @Test
    fun `geo uri carries a query so the map drops a pin, not just a viewport`() {
        assertEquals(
            "geo:45.815000,15.982000?q=45.815000,15.982000(ADA)",
            LocationLink.geoUri(45.815, 15.982, "Ada"),
        )
    }

    // The bug this exists for: Croatian (and most of Europe) formats 45.815 as "45,815", and a
    // comma is exactly what separates latitude from longitude in a geo: URI. Formatted with the
    // default locale, "geo:45,815000,15,982000" is either unparseable or a pin in the wrong place
    // - and it would only ever misbehave on the phones of the people who wrote it.
    @Test
    fun `coordinates use a dot separator even under a comma-decimal locale`() {
        Locale.setDefault(Locale.forLanguageTag("hr-HR"))

        assertEquals("45.815000", LocationLink.format(45.815))
        assertEquals(
            "geo:45.815000,15.982000?q=45.815000,15.982000(ADA)",
            LocationLink.geoUri(45.815, 15.982, "Ada"),
        )
        assertEquals(
            "https://www.google.com/maps/search/?api=1&query=45.815000,15.982000",
            LocationLink.webUrl(45.815, 15.982),
        )
    }

    @Test
    fun `southern and western coordinates keep their sign`() {
        assertEquals(
            "geo:-33.868800,-151.209300?q=-33.868800,-151.209300(LIN)",
            LocationLink.geoUri(-33.8688, -151.2093, "Lin"),
        )
    }

    @Test
    fun `out of range and non-finite coordinates produce no link at all`() {
        assertFalse(LocationLink.isValid(91.0, 0.0))
        assertFalse(LocationLink.isValid(-91.0, 0.0))
        assertFalse(LocationLink.isValid(0.0, 181.0))
        assertFalse(LocationLink.isValid(0.0, -181.0))
        assertFalse(LocationLink.isValid(Double.NaN, 0.0))
        assertFalse(LocationLink.isValid(0.0, Double.POSITIVE_INFINITY))

        assertNull(LocationLink.geoUri(91.0, 0.0, "Ada"))
        assertNull(LocationLink.webUrl(Double.NaN, 0.0))
    }

    @Test
    fun `the poles and the antimeridian are valid positions`() {
        assertTrue(LocationLink.isValid(90.0, 180.0))
        assertTrue(LocationLink.isValid(-90.0, -180.0))
        assertTrue(LocationLink.isValid(0.0, 0.0))
    }

    // The label is interpolated straight into the URI, so anything that could close the
    // parenthesised group or start a new query parameter is dropped rather than escaped.
    @Test
    fun `label is stripped of characters that would break the uri`() {
        assertEquals(
            "geo:0.000000,0.000000?q=0.000000,0.000000(ADA LOVELACE)",
            LocationLink.geoUri(0.0, 0.0, "Ada (Lovelace)"),
        )

        val injected = LocationLink.geoUri(0.0, 0.0, "Ada)&q=51.5,0.1(evil")!!
        // Anything able to close the marker group or open a second query parameter is gone, so
        // the pin cannot be moved by a chosen username.
        assertFalse(injected.contains("&"))
        assertEquals(1, injected.count { it == '(' })
        assertEquals(1, injected.count { it == ')' })
        assertTrue(injected.startsWith("geo:0.000000,0.000000?q=0.000000,0.000000("))
    }

    @Test
    fun `a label with nothing usable left yields a pin with no label`() {
        assertEquals(
            "geo:0.000000,0.000000?q=0.000000,0.000000",
            LocationLink.geoUri(0.0, 0.0, "()&"),
        )
    }
}

package hr.theshop.yo.data.location

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MapIntentFactoryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    // The bug this exists for: a bare geo: intent is claimed by every navigation and ride-hailing
    // app on the phone. The test handset offered Maps, Waze, Uber, Bolt, myAudi and Zoom, so
    // tapping a shared location produced an "Open with" dialog instead of a map.
    @Test
    fun `google maps is asked for by name when it is installed`() {
        installMapApplication("com.waze")
        installMapApplication(MapIntentFactory.GOOGLE_MAPS_PACKAGE)

        val intent = MapIntentFactory.forCoordinates(context, 45.815, 15.982, "Ada")!!

        assertEquals(MapIntentFactory.GOOGLE_MAPS_PACKAGE, intent.`package`)
        assertEquals(
            "geo:45.815000,15.982000?q=45.815000,15.982000(ADA)",
            intent.data.toString(),
        )
    }

    @Test
    fun `without google maps any other map application may handle it`() {
        installMapApplication("com.waze")

        val intent = MapIntentFactory.forCoordinates(context, 45.815, 15.982, "Ada")!!

        // Unpackaged on purpose: with no Google Maps, the user's own choice is the right answer.
        assertNull(intent.`package`)
        assertEquals(
            "geo:45.815000,15.982000?q=45.815000,15.982000(ADA)",
            intent.data.toString(),
        )
    }

    @Test
    fun `with no map application at all the pin opens in a browser`() {
        val intent = MapIntentFactory.forCoordinates(context, 45.815, 15.982, "Ada")!!

        assertEquals(
            "https://www.google.com/maps/search/?api=1&query=45.815000,15.982000",
            intent.data.toString(),
        )
    }

    @Test
    fun `coordinates that are not a position produce no intent`() {
        assertNull(MapIntentFactory.forCoordinates(context, 640.0, 15.982, "Ada"))
        assertNull(MapIntentFactory.forCoordinates(context, Double.NaN, 15.982, "Ada"))
    }

    /** Declares an application the way a real one does: an activity with a VIEW filter on the geo
     *  scheme. CATEGORY_DEFAULT is required - resolveActivity matches with MATCH_DEFAULT_ONLY. */
    private fun installMapApplication(packageName: String) {
        val component = ComponentName(packageName, "$packageName.MapActivity")
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
}

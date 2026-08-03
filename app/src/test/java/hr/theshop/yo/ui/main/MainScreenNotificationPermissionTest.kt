package hr.theshop.yo.ui.main

import android.os.Build
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the decision MainScreen's notification-permission LaunchedEffect makes each time it fires
 * - which is once per session appearing, since MainScreen only composes while signed in (see
 * MainActivity: it lives in the else-branch of the session == null check). That composition
 * timing is what actually fixes the defect (asking on the sign-in screen, before an account
 * exists) and is not independently exercisable here: this project's own test suite documents that
 * a live Compose UI test (MainScreenTest, in androidTest) hits a reproducible Hilt/kapt tooling
 * failure (see the KDoc on MainViewModelTest), and `ui-test-junit4` is not on the unit-test
 * classpath either, so nothing in `src/test` can drive real composition. What IS pinned here is
 * the actual permission decision - version guard plus "only when missing" - which is the part
 * that regresses silently if a future edit drops either check.
 */
class MainScreenNotificationPermissionTest {

    @Test
    fun `requests when Tiramisu or above and not already granted`() {
        assertTrue(
            shouldRequestNotificationPermission(
                sdkInt = Build.VERSION_CODES.TIRAMISU,
                alreadyGranted = false,
            ),
        )
    }

    @Test
    fun `does not request when already granted`() {
        assertFalse(
            shouldRequestNotificationPermission(
                sdkInt = Build.VERSION_CODES.TIRAMISU,
                alreadyGranted = true,
            ),
        )
    }

    @Test
    fun `does not request below Tiramisu even if not granted`() {
        assertFalse(
            shouldRequestNotificationPermission(
                sdkInt = Build.VERSION_CODES.TIRAMISU - 1,
                alreadyGranted = false,
            ),
        )
    }
}

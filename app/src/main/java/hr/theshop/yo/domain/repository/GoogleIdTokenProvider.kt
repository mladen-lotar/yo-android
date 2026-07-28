package hr.theshop.yo.domain.repository

import android.content.Context

/**
 * Obtains a Google ID token by showing the device's account picker.
 *
 * An interface rather than a direct Credential Manager call so the sign-in logic can be tested
 * without Play services: the real picker needs an Activity and a Google-signed device, neither of
 * which exists under Robolectric.
 */
interface GoogleIdTokenProvider {
    /**
     * False when this build carries no OAuth client id. The sign-in screen hides its Google band
     * rather than offering a button that could only ever fail.
     */
    val isConfigured: Boolean

    /** Shows the account picker. [context] must be an Activity - the picker is UI. */
    suspend fun requestIdToken(context: Context): GoogleIdTokenResult
}

sealed interface GoogleIdTokenResult {
    data class Success(val idToken: String) : GoogleIdTokenResult

    /**
     * The picker was dismissed. Distinct from a failure because it is not one: the screen must
     * say nothing at all, the way dismissing any picker says nothing.
     */
    data object Cancelled : GoogleIdTokenResult

    /**
     * Credential Manager had no credential to return for this request.
     *
     * This is NOT the same as "the device has no Google account", however much the name of the
     * underlying `NoCredentialException` suggests it. Play services answers identically when the
     * phone is signed into several Google accounts but none of them may be used here - most often
     * because the project owning the server client id has no Android OAuth client registered for
     * this package and signing certificate. Observed on an S25 with four Google accounts present.
     * So anything shown to the user must not assert that they have no account.
     */
    data object NoUsableAccount : GoogleIdTokenResult

    /** Play services is missing or too old, or this build's client id is wrong. */
    data object Unavailable : GoogleIdTokenResult
}

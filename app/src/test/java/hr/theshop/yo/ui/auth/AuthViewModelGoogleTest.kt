package hr.theshop.yo.ui.auth

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import hr.theshop.yo.domain.model.AuthFailure
import hr.theshop.yo.domain.model.GoogleAuthResult
import hr.theshop.yo.domain.model.YoSession
import hr.theshop.yo.domain.repository.GoogleIdTokenResult
import hr.theshop.yo.testing.FakeGoogleIdTokenProvider
import hr.theshop.yo.testing.FakeSessionStore
import hr.theshop.yo.testing.StubYoBackendApi
import hr.theshop.yo.testing.TEST_GOOGLE_ID_TOKEN
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * "Continue with Google". Robolectric only because showing the account picker needs a real
 * Context; nothing here touches Play services, which the [FakeGoogleIdTokenProvider] stands in for.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AuthViewModelGoogleTest {
    private val dispatcher = StandardTestDispatcher()
    private val context: Context = ApplicationProvider.getApplicationContext()

    private companion object {
        val SESSION = YoSession(username = "LEO", token = "token-1")
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `a known google account signs straight in`() = runTest {
        val sessionStore = FakeSessionStore(initial = null)
        val backendApi = FakeGoogleApi(GoogleAuthResult.Success(SESSION))
        val viewModel = createViewModel(backendApi, sessionStore)

        viewModel.continueWithGoogle(context)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(SESSION, sessionStore.current())
        assertFalse(viewModel.state.value.awaitingUsername)
        // No username is offered on the first attempt: the server decides whether it needs one.
        assertEquals(listOf(TEST_GOOGLE_ID_TOKEN to null), backendApi.googleCalls)
    }

    @Test
    fun `an unrecognised google account is asked for a username`() = runTest {
        val sessionStore = FakeSessionStore(initial = null)
        val viewModel =
            createViewModel(FakeGoogleApi(GoogleAuthResult.UsernameRequired), sessionStore)

        viewModel.continueWithGoogle(context)
        dispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.state.value
        assertTrue(state.awaitingUsername)
        assertEquals(TEST_GOOGLE_ID_TOKEN, state.pendingGoogleToken)
        assertNotNull(state.message)
        assertFalse(state.busy)
        assertNull(sessionStore.current())
    }

    @Test
    fun `claiming a username posts the same token back and never asks google again`() = runTest {
        val sessionStore = FakeSessionStore(initial = null)
        val backendApi = FakeGoogleApi(GoogleAuthResult.UsernameRequired)
        val provider = FakeGoogleIdTokenProvider()
        val viewModel = createViewModel(backendApi, sessionStore, provider)

        viewModel.continueWithGoogle(context)
        dispatcher.scheduler.advanceUntilIdle()
        backendApi.result = GoogleAuthResult.Success(SESSION)
        viewModel.confirmGoogleUsername("  leo ")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            listOf(
                TEST_GOOGLE_ID_TOKEN to null,
                TEST_GOOGLE_ID_TOKEN to "LEO",
            ),
            backendApi.googleCalls,
        )
        // The picker appeared once. Showing it twice for one sign-in would be a bug the user sees.
        assertEquals(1, provider.calls)
        assertEquals(SESSION, sessionStore.current())
    }

    @Test
    fun `a rejected username leaves the prompt open to try another`() = runTest {
        val backendApi = FakeGoogleApi(GoogleAuthResult.UsernameRequired)
        val viewModel = createViewModel(backendApi)

        viewModel.continueWithGoogle(context)
        dispatcher.scheduler.advanceUntilIdle()
        backendApi.result = GoogleAuthResult.Failure(AuthFailure.UsernameTaken)
        viewModel.confirmGoogleUsername("leo")
        dispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.state.value
        assertTrue("the sign-in was thrown away instead of retried", state.awaitingUsername)
        assertEquals(AuthFailure.UsernameTaken.message(), state.message)
        assertFalse(state.busy)
    }

    @Test
    fun `an empty username never reaches the backend`() = runTest {
        val backendApi = FakeGoogleApi(GoogleAuthResult.UsernameRequired)
        val viewModel = createViewModel(backendApi)

        viewModel.continueWithGoogle(context)
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.confirmGoogleUsername("   ")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, backendApi.googleCalls.size)
        assertTrue(viewModel.state.value.awaitingUsername)
    }

    @Test
    fun `confirming a username does nothing when no sign-in is pending`() = runTest {
        val backendApi = FakeGoogleApi(GoogleAuthResult.Success(SESSION))
        val viewModel = createViewModel(backendApi)

        viewModel.confirmGoogleUsername("LEO")
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(backendApi.googleCalls.isEmpty())
    }

    @Test
    fun `dismissing the picker says nothing at all`() = runTest {
        val backendApi = FakeGoogleApi(GoogleAuthResult.Success(SESSION))
        val provider = FakeGoogleIdTokenProvider(result = GoogleIdTokenResult.Cancelled)
        val viewModel = createViewModel(backendApi, provider = provider)

        viewModel.continueWithGoogle(context)
        dispatcher.scheduler.advanceUntilIdle()

        // Changing your mind is not an error and must not be reported as one.
        assertNull(viewModel.state.value.message)
        assertFalse(viewModel.state.value.busy)
        assertTrue(backendApi.googleCalls.isEmpty())
    }

    @Test
    fun `a picker that cannot run explains itself and sends nothing`() = runTest {
        for (outcome in listOf(GoogleIdTokenResult.NoUsableAccount, GoogleIdTokenResult.Unavailable)) {
            val backendApi = FakeGoogleApi(GoogleAuthResult.Success(SESSION))
            val viewModel =
                createViewModel(backendApi, provider = FakeGoogleIdTokenProvider(result = outcome))

            viewModel.continueWithGoogle(context)
            dispatcher.scheduler.advanceUntilIdle()

            assertNotNull("nothing shown for $outcome", viewModel.state.value.message)
            assertFalse(viewModel.state.value.busy)
            assertTrue(backendApi.googleCalls.isEmpty())
        }
    }

    @Test
    fun `an unusable account is never reported as the phone having none`() = runTest {
        // Credential Manager answers NoCredentialException both when the device has no Google
        // account and when it has several that may not be used here - which is what an S25 with
        // four accounts actually did, against a project missing its Android OAuth client. Telling
        // that user "no Google account on this phone" sends them looking for a problem they do
        // not have.
        val viewModel =
            createViewModel(
                provider = FakeGoogleIdTokenProvider(result = GoogleIdTokenResult.NoUsableAccount),
            )

        viewModel.continueWithGoogle(context)
        dispatcher.scheduler.advanceUntilIdle()

        val message = viewModel.state.value.message
        assertNotNull(message)
        assertFalse(
            "message asserts a device fact Credential Manager cannot know: $message",
            message!!.contains("PHONE") || message.contains("DEVICE"),
        )
    }

    @Test
    fun `cancelling clears the pending token`() = runTest {
        val viewModel = createViewModel(FakeGoogleApi(GoogleAuthResult.UsernameRequired))

        viewModel.continueWithGoogle(context)
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.cancelGoogle()

        assertFalse(viewModel.state.value.awaitingUsername)
        assertNull(viewModel.state.value.message)
    }

    @Test
    fun `a second tap while the picker is open is ignored`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val backendApi = FakeGoogleApi(GoogleAuthResult.Success(SESSION), gate = gate)
        val provider = FakeGoogleIdTokenProvider()
        val viewModel = createViewModel(backendApi, provider = provider)

        viewModel.continueWithGoogle(context)
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(viewModel.state.value.busy)
        viewModel.continueWithGoogle(context)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, provider.calls)
        gate.complete(Unit)
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, backendApi.googleCalls.size)
    }

    @Test
    fun `the band is offered only when the build has a client id`() {
        assertTrue(
            createViewModel(provider = FakeGoogleIdTokenProvider(isConfigured = true))
                .googleAvailable,
        )
        assertFalse(
            createViewModel(provider = FakeGoogleIdTokenProvider(isConfigured = false))
                .googleAvailable,
        )
    }

    private fun createViewModel(
        backendApi: FakeGoogleApi = FakeGoogleApi(GoogleAuthResult.Success(SESSION)),
        sessionStore: FakeSessionStore = FakeSessionStore(initial = null),
        provider: FakeGoogleIdTokenProvider = FakeGoogleIdTokenProvider(),
    ): AuthViewModel =
        AuthViewModel(
            backendApi = backendApi,
            sessionStore = sessionStore,
            googleIdTokenProvider = provider,
        )

    private class FakeGoogleApi(
        var result: GoogleAuthResult,
        private val gate: CompletableDeferred<Unit>? = null,
    ) : StubYoBackendApi() {
        override suspend fun deleteAccount(): Boolean = true

        val googleCalls = mutableListOf<Pair<String, String?>>()

        override suspend fun signInWithGoogle(
            idToken: String,
            username: String?,
        ): GoogleAuthResult {
            googleCalls += idToken to username
            gate?.await()
            return result
        }
    }
}

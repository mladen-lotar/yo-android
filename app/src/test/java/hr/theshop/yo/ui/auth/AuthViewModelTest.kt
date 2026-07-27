package hr.theshop.yo.ui.auth

import hr.theshop.yo.domain.model.AuthFailure
import hr.theshop.yo.domain.model.AuthResult
import hr.theshop.yo.domain.model.YoSession
import hr.theshop.yo.testing.FakeGoogleIdTokenProvider
import hr.theshop.yo.testing.FakeSessionStore
import hr.theshop.yo.testing.StubYoBackendApi
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

/**
 * Covers the sign-in gate that replaced the hardcoded "me" identity: what is allowed to reach the
 * backend, what shape it arrives in, and what happens to the session afterwards.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    private companion object {
        const val VALID_PASSWORD = "correct-horse"
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
    fun `an empty username never reaches the backend`() = runTest {
        val backendApi = FakeAuthApi()
        val viewModel = createViewModel(backendApi)

        viewModel.signUp("", VALID_PASSWORD)
        dispatcher.scheduler.advanceUntilIdle()

        // Rejecting locally is what keeps an obvious typo from spending one of the ten
        // rate-limited attempts the backend allows.
        assertEquals(0, backendApi.callCount)
        assertNotNull(viewModel.state.value.message)
        assertFalse(viewModel.state.value.busy)
    }

    @Test
    fun `a username of only spaces never reaches the backend`() = runTest {
        val backendApi = FakeAuthApi()
        val viewModel = createViewModel(backendApi)

        viewModel.signUp("   ", VALID_PASSWORD)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(0, backendApi.callCount)
        assertNotNull(viewModel.state.value.message)
    }

    @Test
    fun `a password shorter than the minimum never reaches the backend`() = runTest {
        val backendApi = FakeAuthApi()
        val viewModel = createViewModel(backendApi)
        val tooShort = "a".repeat(MIN_PASSWORD_LENGTH - 1)

        viewModel.signUp("LEO", tooShort)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(0, backendApi.callCount)
        val message = viewModel.state.value.message
        assertNotNull(message)
        assertTrue(message!!.contains(MIN_PASSWORD_LENGTH.toString()))
    }

    @Test
    fun `a short password is rejected on the log in path too`() = runTest {
        val backendApi = FakeAuthApi()
        val viewModel = createViewModel(backendApi)

        viewModel.logIn("LEO", "short")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(0, backendApi.callCount)
    }

    @Test
    fun `the username is trimmed and uppercased before being sent`() = runTest {
        val backendApi = FakeAuthApi()
        val viewModel = createViewModel(backendApi)

        viewModel.signUp("  leo ", VALID_PASSWORD)
        dispatcher.scheduler.advanceUntilIdle()

        // The password is passed through untouched — only the username is normalised.
        assertEquals(listOf("LEO" to VALID_PASSWORD), backendApi.signUpCalls)
    }

    @Test
    fun `log in normalises the username the same way sign up does`() = runTest {
        val backendApi = FakeAuthApi()
        val viewModel = createViewModel(backendApi)

        viewModel.logIn(" leo\t", VALID_PASSWORD)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf("LEO" to VALID_PASSWORD), backendApi.logInCalls)
        assertTrue(backendApi.signUpCalls.isEmpty())
    }

    @Test
    fun `a successful sign up saves the returned session`() = runTest {
        val sessionStore = FakeSessionStore(initial = null)
        val viewModel = createViewModel(FakeAuthApi(result = AuthResult.Success(SESSION)), sessionStore)

        viewModel.signUp("leo", VALID_PASSWORD)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(SESSION, sessionStore.current())
        assertNull(viewModel.state.value.message)
    }

    @Test
    fun `a successful log in saves the returned session`() = runTest {
        val sessionStore = FakeSessionStore(initial = null)
        val viewModel = createViewModel(FakeAuthApi(result = AuthResult.Success(SESSION)), sessionStore)

        viewModel.logIn("leo", VALID_PASSWORD)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(SESSION, sessionStore.current())
    }

    @Test
    fun `the saved session is the one the server returned, not the one that was typed`() = runTest {
        // The server is the authority on the stored spelling of the name.
        val serverSession = YoSession(username = "LEO", token = "token-from-server")
        val sessionStore = FakeSessionStore(initial = null)
        val viewModel =
            createViewModel(FakeAuthApi(result = AuthResult.Success(serverSession)), sessionStore)

        viewModel.logIn("leo", VALID_PASSWORD)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("token-from-server", sessionStore.current()?.token)
    }

    @Test
    fun `every failure shows its own message and saves no session`() = runTest {
        val shown = mutableMapOf<AuthFailure, String>()

        for (failure in AuthFailure.values()) {
            val sessionStore = FakeSessionStore(initial = null)
            val viewModel =
                createViewModel(FakeAuthApi(result = AuthResult.Failure(failure)), sessionStore)

            viewModel.logIn("leo", VALID_PASSWORD)
            dispatcher.scheduler.advanceUntilIdle()

            val state = viewModel.state.value
            assertNotNull("no message shown for $failure", state.message)
            assertFalse("still busy after $failure", state.busy)
            assertNull("a failed log in saved a session for $failure", sessionStore.current())
            assertEquals("wrong wording for $failure", failure.message(), state.message)
            shown[failure] = state.message!!
        }

        // Distinct wording matters: the screen is the only place a caller learns whether the name
        // was taken, the password was wrong, or the phone is simply offline.
        assertEquals(AuthFailure.values().size, shown.values.toSet().size)
    }

    @Test
    fun `a second submit while a request is in flight is ignored`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val backendApi = FakeAuthApi(gate = gate)
        val viewModel = createViewModel(backendApi)

        viewModel.signUp("leo", VALID_PASSWORD)
        // Runs the first attempt up to the point where it is parked inside the backend call.
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(viewModel.state.value.busy)

        viewModel.signUp("leo", VALID_PASSWORD)
        viewModel.logIn("leo", VALID_PASSWORD)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, backendApi.callCount)

        gate.complete(Unit)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, backendApi.callCount)
    }

    @Test
    fun `a failed attempt clears busy so the caller can try again`() = runTest {
        val backendApi = FakeAuthApi(result = AuthResult.Failure(AuthFailure.InvalidCredentials))
        val viewModel = createViewModel(backendApi)

        viewModel.logIn("leo", VALID_PASSWORD)
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.logIn("leo", VALID_PASSWORD)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(2, backendApi.callCount)
    }

    private fun createViewModel(
        backendApi: FakeAuthApi = FakeAuthApi(),
        sessionStore: FakeSessionStore = FakeSessionStore(initial = null),
    ): AuthViewModel =
        AuthViewModel(
            backendApi = backendApi,
            sessionStore = sessionStore,
            googleIdTokenProvider = FakeGoogleIdTokenProvider(),
        )

    private class FakeAuthApi(
        private val result: AuthResult = AuthResult.Success(SESSION),
        private val gate: CompletableDeferred<Unit>? = null,
    ) : StubYoBackendApi() {
        val signUpCalls = mutableListOf<Pair<String, String>>()
        val logInCalls = mutableListOf<Pair<String, String>>()

        val callCount: Int
            get() = signUpCalls.size + logInCalls.size

        override suspend fun signUp(username: String, password: String): AuthResult {
            signUpCalls += username to password
            gate?.await()
            return result
        }

        override suspend fun logIn(username: String, password: String): AuthResult {
            logInCalls += username to password
            gate?.await()
            return result
        }
    }
}

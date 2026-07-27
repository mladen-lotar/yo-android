package hr.theshop.yo.testing

import hr.theshop.yo.data.remote.AddFriendOutcome
import hr.theshop.yo.data.remote.YoBackendApi
import hr.theshop.yo.domain.model.AuthFailure
import android.content.Context
import hr.theshop.yo.domain.model.AuthResult
import hr.theshop.yo.domain.model.GoogleAuthResult
import hr.theshop.yo.domain.model.YoSession
import hr.theshop.yo.domain.repository.GoogleIdTokenProvider
import hr.theshop.yo.domain.repository.GoogleIdTokenResult
import hr.theshop.yo.domain.repository.SessionStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Every method answers benignly so a test can override only what it actually cares about.
 * Shared because [YoBackendApi] has ten methods and hand-rolling a full fake per test file makes
 * every future interface change a multi-file edit.
 */
open class StubYoBackendApi : YoBackendApi {
    override suspend fun deleteAccount(): Boolean = true

    override suspend fun signUp(username: String, password: String): AuthResult =
        AuthResult.Failure(AuthFailure.Unreachable)

    override suspend fun logIn(username: String, password: String): AuthResult =
        AuthResult.Failure(AuthFailure.Unreachable)

    override suspend fun signInWithGoogle(
        idToken: String,
        username: String?,
    ): GoogleAuthResult = GoogleAuthResult.Failure(AuthFailure.Unreachable)

    override suspend fun logOut(): Boolean = true

    override suspend fun register(fcmToken: String): Boolean = true

    override suspend fun fetchFriends(): List<String> = emptyList()

    override suspend fun addFriend(username: String): AddFriendOutcome = AddFriendOutcome.Added

    override suspend fun removeFriend(username: String): Boolean = true

    override suspend fun block(username: String): Boolean = true

    override suspend fun sendYo(recipient: String): Boolean = true

    override suspend fun uploadPhoto(
        messageId: String,
        base64Data: String,
        mimeType: String,
        recipient: String?,
    ): Boolean = true
}

/**
 * Stands in for the device account picker. [result] is what the picker "returns"; [calls] records
 * that it was shown at all, which is how tests tell "did not ask Google" apart from "asked and
 * was refused".
 */
class FakeGoogleIdTokenProvider(
    var result: GoogleIdTokenResult = GoogleIdTokenResult.Success(TEST_GOOGLE_ID_TOKEN),
    override val isConfigured: Boolean = true,
) : GoogleIdTokenProvider {
    var calls = 0
        private set

    override suspend fun requestIdToken(context: Context): GoogleIdTokenResult {
        calls++
        return result
    }
}

const val TEST_USERNAME = "ME"

const val TEST_GOOGLE_ID_TOKEN = "google.id.token"

class FakeSessionStore(
    initial: YoSession? = YoSession(username = TEST_USERNAME, token = "test-token"),
) : SessionStore {
    private val state = MutableStateFlow(initial)

    override val session: StateFlow<YoSession?> = state.asStateFlow()

    override fun current(): YoSession? = state.value

    override fun save(session: YoSession) {
        state.value = session
    }

    override fun clear() {
        state.value = null
    }
}

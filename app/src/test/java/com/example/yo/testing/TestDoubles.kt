package com.example.yo.testing

import com.example.yo.data.remote.AddFriendOutcome
import com.example.yo.data.remote.YoBackendApi
import com.example.yo.domain.model.AuthFailure
import com.example.yo.domain.model.AuthResult
import com.example.yo.domain.model.YoSession
import com.example.yo.domain.repository.SessionStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Every method answers benignly so a test can override only what it actually cares about.
 * Shared because [YoBackendApi] has ten methods and hand-rolling a full fake per test file makes
 * every future interface change a multi-file edit.
 */
open class StubYoBackendApi : YoBackendApi {
    override suspend fun signUp(username: String, password: String): AuthResult =
        AuthResult.Failure(AuthFailure.Unreachable)

    override suspend fun logIn(username: String, password: String): AuthResult =
        AuthResult.Failure(AuthFailure.Unreachable)

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

const val TEST_USERNAME = "ME"

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

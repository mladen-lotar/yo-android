package hr.theshop.yo.ui.auth

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import hr.theshop.yo.data.remote.YoBackendApi
import hr.theshop.yo.domain.model.AuthResult
import hr.theshop.yo.domain.model.GoogleAuthResult
import hr.theshop.yo.domain.repository.GoogleIdTokenProvider
import hr.theshop.yo.domain.repository.GoogleIdTokenResult
import hr.theshop.yo.domain.repository.SessionStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Minimum accepted password length. Mirrors yo_auth.MIN_PASSWORD_LENGTH on the backend. */
const val MIN_PASSWORD_LENGTH = 8

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val backendApi: YoBackendApi,
    private val sessionStore: SessionStore,
    private val googleIdTokenProvider: GoogleIdTokenProvider,
) : ViewModel() {
    data class State(
        val busy: Boolean = false,
        val message: String? = null,
        /**
         * Set once Google has identified somebody the backend has never seen. While it is
         * non-null the screen is asking for a username, and the same token is posted again with
         * the answer - Google is not consulted twice.
         */
        val pendingGoogleToken: String? = null,
    ) {
        val awaitingUsername: Boolean get() = pendingGoogleToken != null
    }

    /** False when this build has no OAuth client id, so the screen leaves the band off. */
    val googleAvailable: Boolean get() = googleIdTokenProvider.isConfigured

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun signUp(username: String, password: String) {
        submit(username, password) { backendApi.signUp(it.first, it.second) }
    }

    fun logIn(username: String, password: String) {
        submit(username, password) { backendApi.logIn(it.first, it.second) }
    }

    /** Show the device account picker, then exchange whatever it returns for a session. */
    fun continueWithGoogle(context: Context) {
        if (_state.value.busy) {
            return
        }
        viewModelScope.launch {
            _state.value = State(busy = true)
            when (val outcome = googleIdTokenProvider.requestIdToken(context)) {
                is GoogleIdTokenResult.Success -> exchange(outcome.idToken, username = null)
                // Dismissing the picker is an answer, not an error. Saying anything here would
                // scold the user for changing their mind.
                GoogleIdTokenResult.Cancelled -> _state.value = State()
                // Deliberately does not claim the phone has no Google account. It may have
                // several and still land here - see GoogleIdTokenResult.NoUsableAccount.
                GoogleIdTokenResult.NoUsableAccount ->
                    _state.value = State(message = "NO GOOGLE ACCOUNT AVAILABLE")
                GoogleIdTokenResult.Unavailable ->
                    _state.value = State(message = "GOOGLE SIGN-IN UNAVAILABLE")
            }
        }
    }

    /** Answer the [State.awaitingUsername] prompt for a Google account seen for the first time. */
    fun confirmGoogleUsername(username: String) {
        val pending = _state.value.pendingGoogleToken ?: return
        if (_state.value.busy) {
            return
        }
        val trimmed = username.trim().uppercase()
        if (trimmed.isEmpty()) {
            _state.value = _state.value.copy(message = "ENTER A USERNAME")
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, message = null)
            exchange(pending, trimmed)
        }
    }

    /** Abandon a half-finished Google sign-in and return to the ordinary two bands. */
    fun cancelGoogle() {
        _state.value = State()
    }

    private suspend fun exchange(idToken: String, username: String?) {
        when (val result = backendApi.signInWithGoogle(idToken, username)) {
            // Saving the session flips the gate in MainActivity, so this screen goes away.
            is GoogleAuthResult.Success -> sessionStore.save(result.session)
            GoogleAuthResult.UsernameRequired ->
                _state.value = State(pendingGoogleToken = idToken, message = "PICK A USERNAME")
            is GoogleAuthResult.Failure ->
                // The pending token is deliberately kept: a rejected username should leave the
                // user on the prompt to try another, not throw the whole sign-in away.
                _state.value =
                    State(
                        pendingGoogleToken = _state.value.pendingGoogleToken,
                        message = result.reason.message(),
                    )
        }
    }

    private fun submit(
        username: String,
        password: String,
        call: suspend (Pair<String, String>) -> AuthResult,
    ) {
        if (_state.value.busy) {
            return
        }
        val trimmed = username.trim().uppercase()
        // Caught here rather than at the server so an obvious typo does not spend one of the
        // caller's ten rate-limited attempts.
        val localProblem = when {
            trimmed.isEmpty() -> "ENTER A USERNAME"
            password.length < MIN_PASSWORD_LENGTH -> "PASSWORD NEEDS $MIN_PASSWORD_LENGTH+ CHARACTERS"
            else -> null
        }
        if (localProblem != null) {
            _state.value = State(busy = false, message = localProblem)
            return
        }

        viewModelScope.launch {
            _state.value = State(busy = true)
            when (val result = call(trimmed to password)) {
                // Saving the session flips the gate in MainActivity, so this screen goes away.
                is AuthResult.Success -> sessionStore.save(result.session)
                is AuthResult.Failure ->
                    _state.value = State(busy = false, message = result.reason.message())
            }
        }
    }
}

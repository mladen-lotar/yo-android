package com.example.yo.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.yo.data.remote.YoBackendApi
import com.example.yo.domain.model.AuthResult
import com.example.yo.domain.repository.SessionStore
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
) : ViewModel() {
    data class State(
        val busy: Boolean = false,
        val message: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun signUp(username: String, password: String) {
        submit(username, password) { backendApi.signUp(it.first, it.second) }
    }

    fun logIn(username: String, password: String) {
        submit(username, password) { backendApi.logIn(it.first, it.second) }
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

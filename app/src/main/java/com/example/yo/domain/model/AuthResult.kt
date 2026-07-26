package com.example.yo.domain.model

/** The outcome of a sign-up or log-in attempt. */
sealed interface AuthResult {
    data class Success(val session: YoSession) : AuthResult

    data class Failure(val reason: AuthFailure) : AuthResult
}

enum class AuthFailure {
    /** Sign-up only: somebody already has that name. */
    UsernameTaken,

    /** Log-in only. Deliberately the same for an unknown user and a wrong password. */
    InvalidCredentials,

    /** The username or password broke the rules before the server ever saw it. */
    Rejected,

    /** Too many attempts from this address. */
    RateLimited,

    /** Could not reach the backend at all. */
    Unreachable,
}

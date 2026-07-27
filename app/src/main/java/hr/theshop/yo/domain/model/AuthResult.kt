package hr.theshop.yo.domain.model

/** The outcome of a sign-up or log-in attempt. */
sealed interface AuthResult {
    data class Success(val session: YoSession) : AuthResult

    data class Failure(val reason: AuthFailure) : AuthResult
}

/**
 * The outcome of exchanging a Google ID token for a Yo session.
 *
 * [UsernameRequired] is the one case with no equivalent in password sign-in: Google supplies a
 * subject and an email, and neither is a Yo username, so the first sign-in for a Google account
 * has to stop and ask for one.
 */
sealed interface GoogleAuthResult {
    data class Success(val session: YoSession) : GoogleAuthResult

    data object UsernameRequired : GoogleAuthResult

    data class Failure(val reason: AuthFailure) : GoogleAuthResult
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

    /** Google sign-in only: the backend would not accept the ID token. */
    GoogleRejected,

    /** Google sign-in only: this deployment has no client id, or Google could not be reached. */
    GoogleUnavailable,

    /** Could not reach the backend at all. */
    Unreachable,
}

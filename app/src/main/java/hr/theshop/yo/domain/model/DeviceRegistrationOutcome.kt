package hr.theshop.yo.domain.model

/**
 * Why registering this device for push did or did not work.
 *
 * A plain `Boolean` could not tell [NotSignedIn] apart from [Failed], so the caller had no way to
 * know whether to warn: before sign-in there is nothing to register and nothing is wrong, while a
 * genuine failure leaves the device silently unreachable (gap G17).
 */
sealed interface DeviceRegistrationOutcome {
    /** The backend now holds a current FCM token for this account. */
    data object Registered : DeviceRegistrationOutcome

    /** Nobody is signed in yet, so there is no account to bind a token to. Not an error. */
    data object NotSignedIn : DeviceRegistrationOutcome

    /** Firebase would not issue a token, or the backend would not accept it. Yos will not arrive. */
    data object Failed : DeviceRegistrationOutcome
}

package hr.theshop.yo.domain.model

/**
 * The signed-in account and the bearer token the backend issued for this device.
 *
 * This replaces the former hardcoded `YoIdentity.CURRENT_USERNAME = "me"` (gap G4) and the single
 * shared API key that used to ship inside every APK (gap G3). The token is per device: revoking it
 * server-side logs out exactly one install.
 */
data class YoSession(
    val username: String,
    val token: String,
)

package hr.theshop.yo.domain.repository

import hr.theshop.yo.domain.model.DeviceRegistration

interface DeviceRegistrationStore {
    fun isRegistered(registration: DeviceRegistration): Boolean

    fun markRegistered(registration: DeviceRegistration)

    /**
     * Forgets which account this device is registered for. Without this, the next account to
     * sign in on this phone looks already-registered and never posts its own token, so it
     * silently receives nothing.
     */
    fun clear()
}

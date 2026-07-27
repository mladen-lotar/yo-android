package hr.theshop.yo.domain.repository

import hr.theshop.yo.domain.model.DeviceRegistration

interface DeviceRegistrationStore {
    fun isRegistered(registration: DeviceRegistration): Boolean

    fun markRegistered(registration: DeviceRegistration)
}

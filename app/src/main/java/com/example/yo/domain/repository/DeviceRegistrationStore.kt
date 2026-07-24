package com.example.yo.domain.repository

import com.example.yo.domain.model.DeviceRegistration

interface DeviceRegistrationStore {
    fun isRegistered(registration: DeviceRegistration): Boolean

    fun markRegistered(registration: DeviceRegistration)
}

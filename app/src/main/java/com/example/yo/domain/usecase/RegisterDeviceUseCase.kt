package com.example.yo.domain.usecase

import com.example.yo.data.remote.YoBackendApi
import com.example.yo.domain.model.DeviceRegistration
import com.example.yo.domain.repository.DeviceRegistrationStore
import com.example.yo.domain.repository.FcmTokenProvider
import com.example.yo.domain.repository.SessionStore
import javax.inject.Inject

class RegisterDeviceUseCase @Inject constructor(
    private val backendApi: YoBackendApi,
    private val tokenProvider: FcmTokenProvider,
    private val registrationStore: DeviceRegistrationStore,
    private val sessionStore: SessionStore,
) {
    suspend operator fun invoke(
        fcmToken: String? = null,
        force: Boolean = false,
    ): Boolean {
        // Registering binds an FCM token to an account, so there is nothing to bind it to
        // until someone has signed in.
        val username = sessionStore.current()?.username ?: return false
        val token =
            fcmToken
                ?: runCatching { tokenProvider.getToken() }.getOrNull()
                ?: return false
        if (token.isBlank()) {
            return false
        }

        val registration =
            DeviceRegistration(
                username = username,
                fcmToken = token,
            )
        if (!force && runCatching { registrationStore.isRegistered(registration) }.getOrDefault(false)) {
            return true
        }

        val registered =
            runCatching {
                backendApi.register(fcmToken = registration.fcmToken)
            }.getOrDefault(false)
        if (registered) {
            runCatching { registrationStore.markRegistered(registration) }
        }
        return registered
    }
}

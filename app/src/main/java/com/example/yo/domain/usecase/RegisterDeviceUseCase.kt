package com.example.yo.domain.usecase

import com.example.yo.data.remote.YoBackendApi
import com.example.yo.domain.model.DeviceRegistration
import com.example.yo.domain.model.YoIdentity
import com.example.yo.domain.repository.DeviceRegistrationStore
import com.example.yo.domain.repository.FcmTokenProvider
import javax.inject.Inject

class RegisterDeviceUseCase @Inject constructor(
    private val backendApi: YoBackendApi,
    private val tokenProvider: FcmTokenProvider,
    private val registrationStore: DeviceRegistrationStore,
) {
    suspend operator fun invoke(
        fcmToken: String? = null,
        force: Boolean = false,
    ): Boolean {
        val token =
            fcmToken
                ?: runCatching { tokenProvider.getToken() }.getOrNull()
                ?: return false
        if (token.isBlank()) {
            return false
        }

        val registration =
            DeviceRegistration(
                username = YoIdentity.CURRENT_USERNAME,
                fcmToken = token,
            )
        if (!force && runCatching { registrationStore.isRegistered(registration) }.getOrDefault(false)) {
            return true
        }

        val registered =
            runCatching {
                backendApi.register(
                    username = registration.username,
                    fcmToken = registration.fcmToken,
                )
            }.getOrDefault(false)
        if (registered) {
            runCatching { registrationStore.markRegistered(registration) }
        }
        return registered
    }
}

package com.example.yo.domain.usecase

import com.example.yo.data.remote.YoBackendApi
import com.example.yo.domain.model.DeviceRegistration
import com.example.yo.domain.model.DeviceRegistrationOutcome
import com.example.yo.domain.repository.DeviceRegistrationStore
import com.example.yo.domain.repository.FcmTokenProvider
import com.example.yo.domain.repository.SessionStore
import javax.inject.Inject
import kotlinx.coroutines.delay

class RegisterDeviceUseCase @Inject constructor(
    private val backendApi: YoBackendApi,
    private val tokenProvider: FcmTokenProvider,
    private val registrationStore: DeviceRegistrationStore,
    private val sessionStore: SessionStore,
) {
    suspend operator fun invoke(
        fcmToken: String? = null,
        force: Boolean = false,
    ): DeviceRegistrationOutcome {
        // Registering binds an FCM token to an account, so there is nothing to bind it to
        // until someone has signed in.
        val username =
            sessionStore.current()?.username ?: return DeviceRegistrationOutcome.NotSignedIn
        val token = fcmToken ?: fetchToken() ?: return DeviceRegistrationOutcome.Failed
        if (token.isBlank()) {
            return DeviceRegistrationOutcome.Failed
        }

        val registration =
            DeviceRegistration(
                username = username,
                fcmToken = token,
            )
        if (!force && runCatching { registrationStore.isRegistered(registration) }.getOrDefault(false)) {
            return DeviceRegistrationOutcome.Registered
        }

        val registered =
            runCatching {
                backendApi.register(fcmToken = registration.fcmToken)
            }.getOrDefault(false)
        if (!registered) {
            return DeviceRegistrationOutcome.Failed
        }
        runCatching { registrationStore.markRegistered(registration) }
        return DeviceRegistrationOutcome.Registered
    }

    /**
     * Firebase returns `SERVICE_NOT_AVAILABLE` for reasons that clear on their own — a dozing
     * handset is the one seen in practice — and logs "Won't retry the operation", which is literal.
     * Nothing else in the app asks again: `YoFirebaseMessagingService.onNewToken` fires when a token
     * rotates, not when the app starts. So one unlucky launch used to leave the device unreachable
     * until something happened to rotate the token, which may be never.
     */
    private suspend fun fetchToken(): String? {
        repeat(TOKEN_ATTEMPTS) { attempt ->
            val token = runCatching { tokenProvider.getToken() }.getOrNull()
            if (!token.isNullOrBlank()) {
                return token
            }
            if (attempt < TOKEN_ATTEMPTS - 1) {
                delay(FIRST_RETRY_DELAY_MILLIS * (1L shl attempt))
            }
        }
        return null
    }

    private companion object {
        const val TOKEN_ATTEMPTS = 3
        const val FIRST_RETRY_DELAY_MILLIS = 1_000L
    }
}

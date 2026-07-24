package com.example.yo.domain.usecase

import com.example.yo.data.remote.YoBackendApi
import com.example.yo.domain.model.DeviceRegistration
import com.example.yo.domain.model.YoIdentity
import com.example.yo.domain.repository.DeviceRegistrationStore
import com.example.yo.domain.repository.FcmTokenProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RegisterDeviceUseCaseTest {
    @Test
    fun `fetched token is registered with the current username`() = runTest {
        val backendApi = FakeYoBackendApi()
        val tokenProvider = FakeFcmTokenProvider(token = "token-1")
        val registrationStore = FakeDeviceRegistrationStore()
        val useCase = RegisterDeviceUseCase(backendApi, tokenProvider, registrationStore)

        val registered = useCase()

        assertTrue(registered)
        assertEquals(
            listOf(DeviceRegistration(YoIdentity.CURRENT_USERNAME, "token-1")),
            backendApi.registrations,
        )
        assertEquals(backendApi.registrations, registrationStore.markedRegistrations)
    }

    @Test
    fun `registered token guard skips repeat backend registration`() = runTest {
        val backendApi = FakeYoBackendApi()
        val tokenProvider = FakeFcmTokenProvider(token = "token-1")
        val registrationStore = FakeDeviceRegistrationStore()
        val useCase = RegisterDeviceUseCase(backendApi, tokenProvider, registrationStore)

        assertTrue(useCase())
        assertTrue(useCase())

        assertEquals(2, tokenProvider.callCount)
        assertEquals(1, backendApi.registrations.size)
        assertEquals(1, registrationStore.markedRegistrations.size)
    }

    @Test
    fun `forced rotated token bypasses registered token guard`() = runTest {
        val rotatedRegistration =
            DeviceRegistration(
                username = YoIdentity.CURRENT_USERNAME,
                fcmToken = "token-2",
            )
        val backendApi = FakeYoBackendApi()
        val tokenProvider = FakeFcmTokenProvider(token = "unused-token")
        val registrationStore =
            FakeDeviceRegistrationStore(initialRegistrations = setOf(rotatedRegistration))
        val useCase = RegisterDeviceUseCase(backendApi, tokenProvider, registrationStore)

        val registered = useCase(fcmToken = rotatedRegistration.fcmToken, force = true)

        assertTrue(registered)
        assertEquals(0, tokenProvider.callCount)
        assertEquals(listOf(rotatedRegistration), backendApi.registrations)
    }

    @Test
    fun `token provider failure returns false without calling backend`() = runTest {
        val backendApi = FakeYoBackendApi()
        val tokenProvider = FakeFcmTokenProvider(failure = IllegalStateException("no token"))
        val registrationStore = FakeDeviceRegistrationStore()
        val useCase = RegisterDeviceUseCase(backendApi, tokenProvider, registrationStore)

        val registered = useCase()

        assertFalse(registered)
        assertTrue(backendApi.registrations.isEmpty())
        assertTrue(registrationStore.markedRegistrations.isEmpty())
    }

    @Test
    fun `backend failure returns false without marking token registered`() = runTest {
        val backendApi = FakeYoBackendApi(registerFailure = IllegalStateException("offline"))
        val tokenProvider = FakeFcmTokenProvider(token = "token-1")
        val registrationStore = FakeDeviceRegistrationStore()
        val useCase = RegisterDeviceUseCase(backendApi, tokenProvider, registrationStore)

        val registered = useCase()

        assertFalse(registered)
        assertEquals(1, backendApi.registrations.size)
        assertTrue(registrationStore.markedRegistrations.isEmpty())
    }

    private class FakeYoBackendApi(
        private val registerFailure: Throwable? = null,
    ) : YoBackendApi {
        val registrations = mutableListOf<DeviceRegistration>()

        override suspend fun register(
            username: String,
            fcmToken: String,
        ): Boolean {
            registrations += DeviceRegistration(username, fcmToken)
            registerFailure?.let { throw it }
            return true
        }

        override suspend fun fetchFriends(): List<String> = emptyList()

        override suspend fun sendYo(
            sender: String,
            recipient: String,
        ): Boolean = true
    }

    private class FakeFcmTokenProvider(
        private val token: String = "",
        private val failure: Throwable? = null,
    ) : FcmTokenProvider {
        var callCount = 0
            private set

        override suspend fun getToken(): String {
            callCount += 1
            failure?.let { throw it }
            return token
        }
    }

    private class FakeDeviceRegistrationStore(
        initialRegistrations: Set<DeviceRegistration> = emptySet(),
    ) : DeviceRegistrationStore {
        private val registrations = initialRegistrations.toMutableSet()
        val markedRegistrations = mutableListOf<DeviceRegistration>()

        override fun isRegistered(registration: DeviceRegistration): Boolean =
            registration in registrations

        override fun markRegistered(registration: DeviceRegistration) {
            registrations += registration
            markedRegistrations += registration
        }
    }
}

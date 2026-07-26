package com.example.yo.domain.usecase

import com.example.yo.data.remote.YoBackendApi
import com.example.yo.domain.model.DeviceRegistration
import com.example.yo.domain.model.DeviceRegistrationOutcome
import com.example.yo.domain.repository.DeviceRegistrationStore
import com.example.yo.domain.repository.FcmTokenProvider
import com.example.yo.testing.FakeSessionStore
import com.example.yo.testing.StubYoBackendApi
import com.example.yo.testing.TEST_USERNAME
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RegisterDeviceUseCaseTest {
    @Test
    fun `fetched token is registered with the current username`() = runTest {
        val backendApi = FakeYoBackendApi()
        val tokenProvider = FakeFcmTokenProvider(token = "token-1")
        val registrationStore = FakeDeviceRegistrationStore()
        val useCase = RegisterDeviceUseCase(backendApi, tokenProvider, registrationStore, FakeSessionStore())

        val outcome = useCase()

        assertEquals(DeviceRegistrationOutcome.Registered, outcome)
        assertEquals(
            listOf(DeviceRegistration(TEST_USERNAME, "token-1")),
            backendApi.registrations,
        )
        assertEquals(backendApi.registrations, registrationStore.markedRegistrations)
    }

    @Test
    fun `registered token guard skips repeat backend registration`() = runTest {
        val backendApi = FakeYoBackendApi()
        val tokenProvider = FakeFcmTokenProvider(token = "token-1")
        val registrationStore = FakeDeviceRegistrationStore()
        val useCase = RegisterDeviceUseCase(backendApi, tokenProvider, registrationStore, FakeSessionStore())

        assertEquals(DeviceRegistrationOutcome.Registered, useCase())
        assertEquals(DeviceRegistrationOutcome.Registered, useCase())

        assertEquals(2, tokenProvider.callCount)
        assertEquals(1, backendApi.registrations.size)
        assertEquals(1, registrationStore.markedRegistrations.size)
    }

    @Test
    fun `forced rotated token bypasses registered token guard`() = runTest {
        val rotatedRegistration =
            DeviceRegistration(
                username = TEST_USERNAME,
                fcmToken = "token-2",
            )
        val backendApi = FakeYoBackendApi()
        val tokenProvider = FakeFcmTokenProvider(token = "unused-token")
        val registrationStore =
            FakeDeviceRegistrationStore(initialRegistrations = setOf(rotatedRegistration))
        val useCase = RegisterDeviceUseCase(backendApi, tokenProvider, registrationStore, FakeSessionStore())

        val outcome = useCase(fcmToken = rotatedRegistration.fcmToken, force = true)

        assertEquals(DeviceRegistrationOutcome.Registered, outcome)
        assertEquals(0, tokenProvider.callCount)
        assertEquals(listOf(rotatedRegistration), backendApi.registrations)
    }

    @Test
    fun `token provider failure reports failure without calling backend`() = runTest {
        val backendApi = FakeYoBackendApi()
        val tokenProvider = FakeFcmTokenProvider(failure = IllegalStateException("no token"))
        val registrationStore = FakeDeviceRegistrationStore()
        val useCase = RegisterDeviceUseCase(backendApi, tokenProvider, registrationStore, FakeSessionStore())

        val outcome = useCase()

        assertEquals(DeviceRegistrationOutcome.Failed, outcome)
        assertTrue(backendApi.registrations.isEmpty())
        assertTrue(registrationStore.markedRegistrations.isEmpty())
    }

    @Test
    fun `backend failure reports failure without marking token registered`() = runTest {
        val backendApi = FakeYoBackendApi(registerFailure = IllegalStateException("offline"))
        val tokenProvider = FakeFcmTokenProvider(token = "token-1")
        val registrationStore = FakeDeviceRegistrationStore()
        val useCase = RegisterDeviceUseCase(backendApi, tokenProvider, registrationStore, FakeSessionStore())

        val outcome = useCase()

        assertEquals(DeviceRegistrationOutcome.Failed, outcome)
        assertEquals(1, backendApi.registrations.size)
        assertTrue(registrationStore.markedRegistrations.isEmpty())
    }

    @Test
    fun `no session is reported separately from a failure`() = runTest {
        // Registering binds an FCM token to an account, so before sign-in there is nothing to bind
        // it to — and the call would go out with no bearer token attached.
        val backendApi = FakeYoBackendApi()
        val tokenProvider = FakeFcmTokenProvider(token = "token-1")
        val registrationStore = FakeDeviceRegistrationStore()
        val useCase =
            RegisterDeviceUseCase(
                backendApi,
                tokenProvider,
                registrationStore,
                FakeSessionStore(initial = null),
            )

        val outcome = useCase()

        assertEquals(DeviceRegistrationOutcome.NotSignedIn, outcome)
        assertTrue(backendApi.registrations.isEmpty())
        assertEquals(0, tokenProvider.callCount)
        assertTrue(registrationStore.markedRegistrations.isEmpty())
    }

    @Test
    fun `no session is reported even when the caller forces a known token`() = runTest {
        val backendApi = FakeYoBackendApi()
        val registrationStore = FakeDeviceRegistrationStore()
        val useCase =
            RegisterDeviceUseCase(
                backendApi,
                FakeFcmTokenProvider(token = "token-1"),
                registrationStore,
                FakeSessionStore(initial = null),
            )

        assertEquals(
            DeviceRegistrationOutcome.NotSignedIn,
            useCase(fcmToken = "token-2", force = true),
        )
        assertTrue(backendApi.registrations.isEmpty())
    }

    @Test
    fun `a transient token failure is retried rather than giving up`() = runTest {
        // The real one: Firebase answers SERVICE_NOT_AVAILABLE on a dozing handset, says it will
        // not retry, and means it. One unlucky launch used to leave the device unreachable.
        val backendApi = FakeYoBackendApi()
        val tokenProvider = FakeFcmTokenProvider(token = "token-1", failuresBeforeSuccess = 2)
        val registrationStore = FakeDeviceRegistrationStore()
        val useCase = RegisterDeviceUseCase(backendApi, tokenProvider, registrationStore, FakeSessionStore())

        val outcome = useCase()

        assertEquals(DeviceRegistrationOutcome.Registered, outcome)
        assertEquals(3, tokenProvider.callCount)
        assertEquals(
            listOf(DeviceRegistration(TEST_USERNAME, "token-1")),
            backendApi.registrations,
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `retries back off instead of hammering`() = runTest {
        // Asserted on the virtual clock, so this pins the schedule without costing 3 real seconds.
        // Without it nothing would notice if the delays disappeared entirely.
        val useCase =
            RegisterDeviceUseCase(
                FakeYoBackendApi(),
                FakeFcmTokenProvider(failure = IllegalStateException("no token")),
                FakeDeviceRegistrationStore(),
                FakeSessionStore(),
            )

        val startedAt = testScheduler.currentTime
        useCase()

        assertEquals(3_000L, testScheduler.currentTime - startedAt)
    }

    @Test
    fun `retrying is bounded rather than endless`() = runTest {
        val tokenProvider = FakeFcmTokenProvider(failure = IllegalStateException("no token"))
        val useCase =
            RegisterDeviceUseCase(
                FakeYoBackendApi(),
                tokenProvider,
                FakeDeviceRegistrationStore(),
                FakeSessionStore(),
            )

        assertEquals(DeviceRegistrationOutcome.Failed, useCase())
        assertEquals(3, tokenProvider.callCount)
    }

    @Test
    fun `a blank token is a failure worth retrying, not a token`() = runTest {
        // Firebase can hand back an empty string; registering it would bind the account to a token
        // that can never receive anything, which reads as success everywhere downstream.
        val backendApi = FakeYoBackendApi()
        val tokenProvider = FakeFcmTokenProvider(token = "")
        val useCase =
            RegisterDeviceUseCase(
                backendApi,
                tokenProvider,
                FakeDeviceRegistrationStore(),
                FakeSessionStore(),
            )

        assertEquals(DeviceRegistrationOutcome.Failed, useCase())
        assertEquals(3, tokenProvider.callCount)
        assertTrue(backendApi.registrations.isEmpty())
    }

    private class FakeYoBackendApi(
        private val registerFailure: Throwable? = null,
    ) : StubYoBackendApi() {
        val registrations = mutableListOf<DeviceRegistration>()

        override suspend fun register(fcmToken: String): Boolean {
            registrations += DeviceRegistration(TEST_USERNAME, fcmToken)
            registerFailure?.let { throw it }
            return true
        }
    }

    private class FakeFcmTokenProvider(
        private val token: String = "",
        private val failure: Throwable? = null,
        private val failuresBeforeSuccess: Int = 0,
    ) : FcmTokenProvider {
        var callCount = 0
            private set

        override suspend fun getToken(): String {
            callCount += 1
            if (callCount <= failuresBeforeSuccess) {
                throw IOException("SERVICE_NOT_AVAILABLE")
            }
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

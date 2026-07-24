package com.example.yo.ui.main

import com.example.yo.data.remote.YoBackendApi
import com.example.yo.domain.model.DeviceRegistration
import com.example.yo.domain.model.YoMessage
import com.example.yo.domain.repository.DeviceRegistrationStore
import com.example.yo.domain.repository.FcmTokenProvider
import com.example.yo.domain.repository.YoRepository
import com.example.yo.domain.usecase.FetchFriendsUseCase
import com.example.yo.domain.usecase.RegisterDeviceUseCase
import com.example.yo.domain.usecase.SendYoUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Covers the same button-tap-to-history-update interaction as MainScreen's "Yo" button, at the
 * ViewModel level. A live Hilt-wired Compose UI test (MainScreenTest, in androidTest) hit a
 * reproducible kapt/Hilt-androidTest-variant tooling failure in this Gradle 8.5 / AGP 8.2.0 /
 * Kotlin 1.9.20 / JDK 17 combination (kaptDebugAndroidTestKotlin fails to resolve
 * HiltAndroidRule/HiltAndroidTest even though the dependency is present on the resolved
 * classpath). Per the scope plan's own fallback clause, this ViewModel-level test substitutes for
 * that Compose UI test; the underlying tap -> send -> history-update path was also manually
 * verified end-to-end on a live emulator (screenshots attached to the issue).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `sendYo tapped for a recipient adds a message to history`() = runTest {
        val repository = FakeYoRepository()
        val viewModel = createViewModel(repository = repository)

        // history is a WhileSubscribed StateFlow; it only starts collecting upstream once it has
        // a subscriber, mirroring how MainScreen's collectAsState() subscribes in the real UI.
        val collectorJob = launch { viewModel.history.collect {} }
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.sendYo("Alice")
        dispatcher.scheduler.advanceUntilIdle()

        val history = viewModel.history.value
        assertEquals(1, history.size)
        assertEquals("Alice", history.single().recipient)
        assertEquals("me", history.single().sender)

        collectorJob.cancel()
    }

    @Test
    fun `friends reflects fetched backend friends`() = runTest {
        val viewModel = createViewModel(friends = listOf("Ada", "Lin"))

        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf("Ada", "Lin"), viewModel.friends.value)
        assertFalse(viewModel.friendsLoadFailed.value)
    }

    @Test
    fun `friends fetch failure yields empty list and failure flag`() = runTest {
        val viewModel =
            createViewModel(
                friendsFailure = IllegalStateException("offline"),
            )

        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.friends.value.isEmpty())
        assertTrue(viewModel.friendsLoadFailed.value)
    }

    private fun createViewModel(
        repository: FakeYoRepository = FakeYoRepository(),
        friends: List<String> = emptyList(),
        friendsFailure: Throwable? = null,
    ): MainViewModel {
        val backendApi = FakeYoBackendApi(friends, friendsFailure)
        return MainViewModel(
            sendYoUseCase = SendYoUseCase(repository),
            fetchFriendsUseCase = FetchFriendsUseCase(backendApi),
            registerDeviceUseCase =
                RegisterDeviceUseCase(
                    backendApi = backendApi,
                    tokenProvider = FakeFcmTokenProvider(),
                    registrationStore = FakeDeviceRegistrationStore(),
                ),
            repository = repository,
        )
    }

    private class FakeYoRepository : YoRepository {
        private val state = MutableStateFlow<List<YoMessage>>(emptyList())

        override suspend fun saveSent(message: YoMessage) {
            state.value = listOf(message) + state.value
        }

        override fun observeHistory(): Flow<List<YoMessage>> = state
    }

    private class FakeYoBackendApi(
        private val friends: List<String>,
        private val friendsFailure: Throwable?,
    ) : YoBackendApi {
        override suspend fun register(
            username: String,
            fcmToken: String,
        ): Boolean = true

        override suspend fun fetchFriends(): List<String> {
            friendsFailure?.let { throw it }
            return friends
        }

        override suspend fun sendYo(
            sender: String,
            recipient: String,
        ): Boolean = true
    }

    private class FakeFcmTokenProvider : FcmTokenProvider {
        override suspend fun getToken(): String = "test-token"
    }

    private class FakeDeviceRegistrationStore : DeviceRegistrationStore {
        override fun isRegistered(registration: DeviceRegistration): Boolean = false

        override fun markRegistered(registration: DeviceRegistration) = Unit
    }
}

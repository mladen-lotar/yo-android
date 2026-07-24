package com.example.yo.ui.main

import com.example.yo.domain.location.LocationCoordinates
import com.example.yo.domain.location.OneShotLocationProvider
import com.example.yo.domain.model.YoMessage
import com.example.yo.domain.repository.YoRepository
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
import org.junit.Assert.assertNull
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
        val viewModel = MainViewModel(SendYoUseCase(repository), repository, FakeOneShotLocationProvider())

        // history is a WhileSubscribed StateFlow; it only starts collecting upstream once it has
        // a subscriber, mirroring how MainScreen's collectAsState() subscribes in the real UI.
        val collectorJob = launch { viewModel.history.collect {} }
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.sendYo("Alice", link = null, hashtag = null, attachLocation = false)
        dispatcher.scheduler.advanceUntilIdle()

        val history = viewModel.history.value
        assertEquals(1, history.size)
        assertEquals("Alice", history.single().recipient)
        assertEquals("me", history.single().sender)

        collectorJob.cancel()
    }

    @Test
    fun `sendYo with link and hashtag saves them on the message`() = runTest {
        val repository = FakeYoRepository()
        val viewModel = MainViewModel(SendYoUseCase(repository), repository, FakeOneShotLocationProvider())

        val collectorJob = launch { viewModel.history.collect {} }
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.sendYo("Alice", link = "https://example.com", hashtag = "worldcup", attachLocation = false)
        dispatcher.scheduler.advanceUntilIdle()

        val history = viewModel.history.value
        assertEquals("https://example.com", history.single().link)
        assertEquals("worldcup", history.single().hashtag)

        collectorJob.cancel()
    }

    @Test
    fun `sendYo with attachLocation true calls the location provider and saves returned coordinates`() = runTest {
        val repository = FakeYoRepository()
        val locationProvider = FakeOneShotLocationProvider(
            coordinates = LocationCoordinates(latitude = 45.815, longitude = 15.982),
        )
        val viewModel = MainViewModel(SendYoUseCase(repository), repository, locationProvider)

        val collectorJob = launch { viewModel.history.collect {} }
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.sendYo("Alice", link = null, hashtag = null, attachLocation = true)
        dispatcher.scheduler.advanceUntilIdle()

        val history = viewModel.history.value
        assertEquals(1, locationProvider.callCount)
        assertEquals(45.815, history.single().latitude)
        assertEquals(15.982, history.single().longitude)

        collectorJob.cancel()
    }

    @Test
    fun `sendYo with attachLocation false never calls the location provider`() = runTest {
        val repository = FakeYoRepository()
        val locationProvider = FakeOneShotLocationProvider(
            coordinates = LocationCoordinates(latitude = 45.815, longitude = 15.982),
        )
        val viewModel = MainViewModel(SendYoUseCase(repository), repository, locationProvider)

        val collectorJob = launch { viewModel.history.collect {} }
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.sendYo("Alice", link = null, hashtag = null, attachLocation = false)
        dispatcher.scheduler.advanceUntilIdle()

        val history = viewModel.history.value
        assertEquals(0, locationProvider.callCount)
        assertNull(history.single().latitude)
        assertNull(history.single().longitude)

        collectorJob.cancel()
    }

    private class FakeYoRepository : YoRepository {
        private val state = MutableStateFlow<List<YoMessage>>(emptyList())

        override suspend fun saveSent(message: YoMessage) {
            state.value = listOf(message) + state.value
        }

        override fun observeHistory(): Flow<List<YoMessage>> = state
    }

    private class FakeOneShotLocationProvider(
        private val coordinates: LocationCoordinates? = null,
    ) : OneShotLocationProvider {
        var callCount = 0
            private set

        override suspend fun getCurrentLocation(): LocationCoordinates? {
            callCount++
            return coordinates
        }
    }
}

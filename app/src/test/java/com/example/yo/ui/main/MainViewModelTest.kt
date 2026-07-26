package com.example.yo.ui.main

import com.example.yo.data.remote.YoBackendApi
import com.example.yo.domain.location.LocationCoordinates
import com.example.yo.domain.location.OneShotLocationProvider
import com.example.yo.domain.model.DeviceRegistration
import com.example.yo.domain.model.Group
import com.example.yo.domain.model.PhoneContact
import com.example.yo.domain.model.YoIdentity
import com.example.yo.domain.model.YoMessage
import com.example.yo.domain.repository.ContactsRepository
import com.example.yo.domain.repository.DeviceRegistrationStore
import com.example.yo.domain.repository.FcmTokenProvider
import com.example.yo.domain.repository.GroupRepository
import com.example.yo.domain.repository.YoRepository
import com.example.yo.domain.usecase.BuildInviteMessageUseCase
import com.example.yo.domain.usecase.FetchFriendsUseCase
import com.example.yo.domain.usecase.RegisterDeviceUseCase
import com.example.yo.domain.usecase.SendYoToGroupUseCase
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
import org.junit.Assert.assertNull
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

    private companion object {
        const val INVITE_URL = "https://yo.example/install"
    }

    @Test
    fun `refreshContacts publishes the address book for the invite sheet`() = runTest {
        val contacts = listOf(
            PhoneContact(id = "1", displayName = "Alice Smith"),
            PhoneContact(id = "2", displayName = "Bob"),
        )
        val viewModel = createViewModel(contactsRepository = FakeContactsRepository(contacts))

        viewModel.refreshContacts()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf("Alice Smith", "Bob"), viewModel.contacts.value.map { it.displayName })
    }

    @Test
    fun `contacts start empty so nothing is read before the sheet is opened`() = runTest {
        val repository = FakeContactsRepository(listOf(PhoneContact("1", "Alice")))
        val viewModel = createViewModel(contactsRepository = repository)
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.contacts.value.isEmpty())
        assertEquals(0, repository.loadCount)
    }

    @Test
    fun `a failing contacts read leaves the list empty instead of crashing the screen`() = runTest {
        val viewModel = createViewModel(
            contactsRepository = FakeContactsRepository(failure = SecurityException("denied")),
        )

        viewModel.refreshContacts()
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.contacts.value.isEmpty())
    }

    @Test
    fun `invite message carries the configured url and greets the chosen contact`() {
        val viewModel = createViewModel()

        val message = viewModel.inviteMessageFor(PhoneContact("1", "Alice Smith"))

        assertTrue(message.startsWith("Hey Alice, "))
        assertTrue(message.contains(INVITE_URL))
    }

    @Test
    fun `invite message without a contact still carries the url`() {
        val viewModel = createViewModel()

        val message = viewModel.inviteMessageFor(null)

        assertFalse(message.contains("Hey"))
        assertTrue(message.contains(INVITE_URL))
    }

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

        viewModel.sendYo("Alice", link = null, hashtag = null, attachLocation = false)
        dispatcher.scheduler.advanceUntilIdle()

        val history = viewModel.history.value
        assertEquals(1, history.size)
        assertEquals("Alice", history.single().recipient)
        assertEquals(YoIdentity.CURRENT_USERNAME, history.single().sender)

        collectorJob.cancel()
    }

    @Test
    fun `sendYo with link and hashtag saves them on the message`() = runTest {
        val repository = FakeYoRepository()
        val viewModel = createViewModel(repository = repository)

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
    fun `sendYo strips a leading hashtag character from user-entered hashtag text`() = runTest {
        val repository = FakeYoRepository()
        val viewModel = createViewModel(repository = repository)

        val collectorJob = launch { viewModel.history.collect {} }
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.sendYo("Alice", link = null, hashtag = "#worldcup", attachLocation = false)
        dispatcher.scheduler.advanceUntilIdle()

        val history = viewModel.history.value
        assertEquals("worldcup", history.single().hashtag)

        collectorJob.cancel()
    }

    @Test
    fun `sendYo with hashtag of only hash characters stores null`() = runTest {
        val repository = FakeYoRepository()
        val viewModel = createViewModel(repository = repository)

        val collectorJob = launch { viewModel.history.collect {} }
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.sendYo("Alice", link = null, hashtag = "##", attachLocation = false)
        dispatcher.scheduler.advanceUntilIdle()

        val history = viewModel.history.value
        assertNull(history.single().hashtag)

        collectorJob.cancel()
    }

    @Test
    fun `sendYo with photoUri saves it on the message`() = runTest {
        val repository = FakeYoRepository()
        val viewModel = createViewModel(repository = repository)
        val photoUri = "content://com.example.yo.fileprovider/captured_photos/photo.jpg"

        val collectorJob = launch { viewModel.history.collect {} }
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.sendYo("Alice", photoUri = photoUri)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(photoUri, viewModel.history.value.single().photoUri)

        collectorJob.cancel()
    }

    @Test
    fun `sendYo with attachLocation true calls the location provider and saves returned coordinates`() = runTest {
        val repository = FakeYoRepository()
        val locationProvider = FakeOneShotLocationProvider(
            coordinates = LocationCoordinates(latitude = 45.815, longitude = 15.982),
        )
        val viewModel = createViewModel(repository = repository, locationProvider = locationProvider)

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
        val viewModel = createViewModel(repository = repository, locationProvider = locationProvider)

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

    @Test
    fun `sendYoToGroup fans out to history for every member`() = runTest {
        val group =
            Group(
                id = "group-1",
                name = "Friends",
                memberUsernames = listOf("Ada", "Lin", "Sam"),
            )
        val repository = FakeYoRepository()
        val groupRepository = FakeGroupRepository(listOf(group))
        val viewModel =
            createViewModel(
                repository = repository,
                groupRepository = groupRepository,
            )
        val collectorJob = launch { viewModel.history.collect {} }
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.sendYoToGroup(group.id)
        dispatcher.scheduler.advanceUntilIdle()

        val history = viewModel.history.value
        assertEquals(3, history.size)
        assertEquals(
            group.memberUsernames.toSet(),
            history.map { message -> message.recipient }.toSet(),
        )
        assertTrue(history.all { message -> message.sender == "me" })

        collectorJob.cancel()
    }

    @Test
    fun `createGroup adds a group to groups StateFlow`() = runTest {
        val groupRepository = FakeGroupRepository()
        val viewModel = createViewModel(groupRepository = groupRepository)
        val collectorJob = launch { viewModel.groups.collect {} }
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.createGroup("Friends", listOf("Ada", "Lin"))
        dispatcher.scheduler.advanceUntilIdle()

        val group = viewModel.groups.value.single()
        assertEquals("Friends", group.name)
        assertEquals(setOf("Ada", "Lin"), group.memberUsernames.toSet())

        collectorJob.cancel()
    }

    private fun createViewModel(
        repository: FakeYoRepository = FakeYoRepository(),
        groupRepository: FakeGroupRepository = FakeGroupRepository(),
        friends: List<String> = emptyList(),
        friendsFailure: Throwable? = null,
        locationProvider: OneShotLocationProvider = FakeOneShotLocationProvider(),
        contactsRepository: ContactsRepository = FakeContactsRepository(),
    ): MainViewModel {
        val backendApi = FakeYoBackendApi(friends, friendsFailure)
        val sendYoUseCase = SendYoUseCase(repository)
        return MainViewModel(
            sendYoUseCase = sendYoUseCase,
            sendYoToGroupUseCase =
                SendYoToGroupUseCase(
                    groupRepository = groupRepository,
                    sendYoUseCase = sendYoUseCase,
                ),
            fetchFriendsUseCase = FetchFriendsUseCase(backendApi),
            registerDeviceUseCase =
                RegisterDeviceUseCase(
                    backendApi = backendApi,
                    tokenProvider = FakeFcmTokenProvider(),
                    registrationStore = FakeDeviceRegistrationStore(),
                ),
            repository = repository,
            groupRepository = groupRepository,
            locationProvider = locationProvider,
            contactsRepository = contactsRepository,
            buildInviteMessage = BuildInviteMessageUseCase(),
            inviteUrl = INVITE_URL,
        )
    }

    private class FakeContactsRepository(
        private val contacts: List<PhoneContact> = emptyList(),
        private val failure: Throwable? = null,
    ) : ContactsRepository {
        var loadCount = 0
            private set

        override suspend fun loadContacts(): List<PhoneContact> {
            loadCount++
            failure?.let { throw it }
            return contacts
        }
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

    private class FakeGroupRepository(
        initialGroups: List<Group> = emptyList(),
    ) : GroupRepository {
        private val state = MutableStateFlow(initialGroups)

        override suspend fun createGroup(
            name: String,
            memberUsernames: List<String>,
        ): Group {
            val group = Group(name = name, memberUsernames = memberUsernames)
            state.value = state.value + group
            return group
        }

        override fun observeGroups(): Flow<List<Group>> = state

        override suspend fun getGroup(groupId: String): Group? =
            state.value.firstOrNull { group -> group.id == groupId }
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

        override suspend fun uploadPhoto(
            messageId: String,
            base64Data: String,
            mimeType: String,
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

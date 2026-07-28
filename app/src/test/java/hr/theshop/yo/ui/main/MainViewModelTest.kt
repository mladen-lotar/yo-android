package hr.theshop.yo.ui.main

import hr.theshop.yo.data.remote.AddFriendOutcome
import hr.theshop.yo.data.remote.YoBackendApi
import hr.theshop.yo.domain.location.LocationCoordinates
import hr.theshop.yo.domain.location.OneShotLocationProvider
import hr.theshop.yo.domain.model.DeviceRegistration
import hr.theshop.yo.domain.model.Group
import hr.theshop.yo.domain.model.PhoneContact
import hr.theshop.yo.domain.model.YoMessage
import hr.theshop.yo.domain.model.YoSession
import hr.theshop.yo.domain.repository.ContactsRepository
import hr.theshop.yo.domain.repository.DeviceRegistrationStore
import hr.theshop.yo.domain.repository.FcmTokenProvider
import hr.theshop.yo.testing.FakeSessionStore
import hr.theshop.yo.testing.StubYoBackendApi
import hr.theshop.yo.testing.TEST_USERNAME
import hr.theshop.yo.domain.repository.GroupRepository
import hr.theshop.yo.domain.repository.YoRepository
import hr.theshop.yo.domain.usecase.BuildInviteMessageUseCase
import hr.theshop.yo.domain.usecase.FetchFriendsUseCase
import hr.theshop.yo.domain.usecase.FilterContactsUseCase
import hr.theshop.yo.domain.usecase.RegisterDeviceUseCase
import hr.theshop.yo.domain.usecase.SendYoToGroupUseCase
import hr.theshop.yo.domain.usecase.SendYoUseCase
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
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
import org.junit.Assert.assertNotNull
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

        // contacts is a WhileSubscribed flow derived from the address book and the search query,
        // so it only emits while something collects it — mirroring collectAsState() in the sheet.
        val collector = launch { viewModel.contacts.collect {} }
        viewModel.refreshContacts()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf("Alice Smith", "Bob"), viewModel.contacts.value.map { it.displayName })

        collector.cancel()
    }

    @Test
    fun `contacts start empty so nothing is read before the sheet is opened`() = runTest {
        val repository = FakeContactsRepository(listOf(PhoneContact("1", "Alice")))
        val viewModel = createViewModel(contactsRepository = repository)
        // Subscribe, so "empty" means the address book genuinely was not read rather than merely
        // that nothing is collecting the flow.
        val collector = launch { viewModel.contacts.collect {} }
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.contacts.value.isEmpty())
        assertEquals(0, repository.loadCount)

        collector.cancel()
    }

    @Test
    fun `a failing contacts read leaves the list empty instead of crashing the screen`() = runTest {
        val viewModel = createViewModel(
            contactsRepository = FakeContactsRepository(failure = SecurityException("denied")),
        )

        val collector = launch { viewModel.contacts.collect {} }
        viewModel.refreshContacts()
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.contacts.value.isEmpty())

        collector.cancel()
    }

    @Test
    fun `typing in the search field narrows the invite list`() = runTest {
        val viewModel = createViewModel(
            contactsRepository = FakeContactsRepository(
                listOf(
                    PhoneContact("1", "Adam Marjanović"),
                    PhoneContact("2", "Petra Vego"),
                    PhoneContact("3", "Ada Lovelace"),
                ),
            ),
        )
        val collector = launch { viewModel.contacts.collect {} }
        viewModel.refreshContacts()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(3, viewModel.contacts.value.size)

        // ASCII query against an accented name — the case that matters on a Croatian address book.
        viewModel.onContactQueryChange("marjanovic")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf("Adam Marjanović"), viewModel.contacts.value.map { it.displayName })

        collector.cancel()
    }

    @Test
    fun `clearing the query restores the whole address book`() = runTest {
        val viewModel = createViewModel(
            contactsRepository = FakeContactsRepository(
                listOf(PhoneContact("1", "Alice"), PhoneContact("2", "Bob")),
            ),
        )
        val collector = launch { viewModel.contacts.collect {} }
        viewModel.refreshContacts()
        viewModel.onContactQueryChange("alice")
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, viewModel.contacts.value.size)

        viewModel.onContactQueryChange("")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(2, viewModel.contacts.value.size)

        collector.cancel()
    }

    @Test
    fun `a query matching nothing is distinguishable from having no contacts at all`() = runTest {
        val viewModel = createViewModel(
            contactsRepository = FakeContactsRepository(listOf(PhoneContact("1", "Alice"))),
        )
        val collector = launch { viewModel.contacts.collect {} }
        val flagCollector = launch { viewModel.contactsFilteredToNothing.collect {} }
        viewModel.refreshContacts()
        viewModel.onContactQueryChange("zzzz")
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.contacts.value.isEmpty())
        assertTrue(viewModel.contactsFilteredToNothing.value)

        collector.cancel()
        flagCollector.cancel()
    }

    @Test
    fun `an empty address book is not reported as filtered to nothing`() = runTest {
        val viewModel = createViewModel(contactsRepository = FakeContactsRepository(emptyList()))
        val flagCollector = launch { viewModel.contactsFilteredToNothing.collect {} }
        viewModel.refreshContacts()
        viewModel.onContactQueryChange("anything")
        dispatcher.scheduler.advanceUntilIdle()

        assertFalse(viewModel.contactsFilteredToNothing.value)

        flagCollector.cancel()
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
        assertEquals(TEST_USERNAME, history.single().sender)

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
        val photoUri = "content://hr.theshop.yo.fileprovider/captured_photos/photo.jpg"

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
    fun `a device that cannot register for push says so`() = runTest {
        // Gap G17: this used to be swallowed entirely, so an unreachable phone looked healthy and
        // the only symptom was that Yos sent to it silently vanished.
        val viewModel = createViewModel(tokenProvider = FakeFcmTokenProvider(failuresBeforeSuccess = 99))

        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.pushUnavailable.value)
    }

    @Test
    fun `a working registration never warns`() = runTest {
        val viewModel = createViewModel()

        dispatcher.scheduler.advanceUntilIdle()

        assertFalse(viewModel.pushUnavailable.value)
    }

    @Test
    fun `signed out is not reported as a push failure`() = runTest {
        // There is nothing to register before sign-in, so warning would be a lie.
        val viewModel = createViewModel(sessionStore = FakeSessionStore(initial = null))

        dispatcher.scheduler.advanceUntilIdle()

        assertFalse(viewModel.pushUnavailable.value)
    }

    @Test
    fun `retrying clears the warning once the cause passes`() = runTest {
        // The use case burns 3 attempts on init; the 4th, from the user's tap, succeeds.
        val viewModel = createViewModel(tokenProvider = FakeFcmTokenProvider(failuresBeforeSuccess = 3))
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(viewModel.pushUnavailable.value)

        viewModel.retryDeviceRegistration()
        dispatcher.scheduler.advanceUntilIdle()

        assertFalse(viewModel.pushUnavailable.value)
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
        assertTrue(history.all { message -> message.sender == TEST_USERNAME })

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

    @Test
    fun `addFriend trims and uppercases the typed name before sending it`() = runTest {
        val backendApi = FakeYoBackendApi()
        val viewModel = createViewModel(backendApi = backendApi)
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.addFriend("  ada ")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf("ADA"), backendApi.addedFriends)
    }

    @Test
    fun `a successful add re-fetches the list so the new band appears`() = runTest {
        val backendApi = FakeYoBackendApi(friends = listOf("LIN"))
        val viewModel = createViewModel(backendApi = backendApi)
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf("LIN"), viewModel.friends.value)

        viewModel.addFriend("ada")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf("LIN", "ADA"), viewModel.friends.value)
        assertEquals(AddFriendOutcome.Added, viewModel.addFriendOutcome.value)
        // One fetch at startup, one after the add.
        assertEquals(2, backendApi.fetchCount)
    }

    @Test
    fun `an unknown username is reported and does not re-fetch the list`() = runTest {
        val backendApi =
            FakeYoBackendApi(friends = listOf("LIN"), addOutcome = AddFriendOutcome.NoSuchUser)
        val viewModel = createViewModel(backendApi = backendApi)
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.addFriend("nobody")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(AddFriendOutcome.NoSuchUser, viewModel.addFriendOutcome.value)
        assertEquals(listOf("LIN"), viewModel.friends.value)
        assertEquals(1, backendApi.fetchCount)
    }

    @Test
    fun `clearAddFriendOutcome resets the reported outcome`() = runTest {
        val backendApi = FakeYoBackendApi(addOutcome = AddFriendOutcome.Rejected)
        val viewModel = createViewModel(backendApi = backendApi)
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.addFriend("ada")
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(AddFriendOutcome.Rejected, viewModel.addFriendOutcome.value)

        viewModel.clearAddFriendOutcome()

        assertNull(viewModel.addFriendOutcome.value)
    }

    @Test
    fun `a blank add friend input is ignored entirely`() = runTest {
        val backendApi = FakeYoBackendApi()
        val viewModel = createViewModel(backendApi = backendApi)
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.addFriend("")
        viewModel.addFriend("   ")
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(backendApi.addedFriends.isEmpty())
        assertNull(viewModel.addFriendOutcome.value)
        assertEquals(1, backendApi.fetchCount)
    }

    @Test
    fun `removeFriend re-fetches so the band disappears`() = runTest {
        val backendApi = FakeYoBackendApi(friends = listOf("ADA", "LIN"))
        val viewModel = createViewModel(backendApi = backendApi)
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.removeFriend("ADA")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf("ADA"), backendApi.removedFriends)
        assertEquals(listOf("LIN"), viewModel.friends.value)
        assertEquals(2, backendApi.fetchCount)
    }

    @Test
    fun `blockFriend re-fetches so the band disappears`() = runTest {
        val backendApi = FakeYoBackendApi(friends = listOf("ADA", "LIN"))
        val viewModel = createViewModel(backendApi = backendApi)
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.blockFriend("LIN")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf("LIN"), backendApi.blockedFriends)
        assertEquals(listOf("ADA"), viewModel.friends.value)
        assertEquals(2, backendApi.fetchCount)
    }

    @Test
    fun `logOut revokes server-side while the token is still present, then clears it`() = runTest {
        val backendApi = FakeYoBackendApi()
        val sessionStore = FakeSessionStore()
        backendApi.sessionProbe = { sessionStore.current() }
        val viewModel = createViewModel(backendApi = backendApi, sessionStore = sessionStore)
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.logOut()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, backendApi.logOutCount)
        // The order is the point: a revoke sent after the local wipe would carry no token and the
        // server would keep honouring the stolen one.
        assertNotNull(backendApi.sessionAtLogOut)
        assertEquals(TEST_USERNAME, backendApi.sessionAtLogOut?.username)
        assertNull(sessionStore.current())
        assertNull(sessionStore.session.value)
    }

    @Test
    fun `username comes from the session store`() = runTest {
        val sessionStore = FakeSessionStore(YoSession(username = "LEO", token = "t"))
        val viewModel = createViewModel(sessionStore = sessionStore)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("LEO", viewModel.username)

        sessionStore.clear()

        assertEquals("", viewModel.username)
    }

    @Test
    fun `deleting the account clears the session`() = runTest {
        val backendApi = FakeYoBackendApi()
        val sessionStore = FakeSessionStore()
        val viewModel = createViewModel(backendApi = backendApi, sessionStore = sessionStore)
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.deleteAccount()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, backendApi.deleteAccountCalls)
        assertNull(sessionStore.current())
        assertFalse(viewModel.deleteAccountFailed.value)
    }

    @Test
    fun `a refused deletion leaves the user signed in`() = runTest {
        // Signing out here would look like success and strand the user outside an account that
        // still exists.
        val backendApi = FakeYoBackendApi(deleteAccountSucceeds = false)
        val sessionStore = FakeSessionStore()
        val viewModel = createViewModel(backendApi = backendApi, sessionStore = sessionStore)
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.deleteAccount()
        dispatcher.scheduler.advanceUntilIdle()

        assertNotNull(sessionStore.current())
        assertTrue(viewModel.deleteAccountFailed.value)
    }

    @Test
    fun `deleting the account erases what this device stored`() = runTest {
        // History and groups are not scoped to an account, so anything left behind would be shown
        // to whoever signs in next on this phone.
        val repository = FakeYoRepository()
        val groupRepository = FakeGroupRepository()
        val registrationStore = FakeDeviceRegistrationStore()
        val viewModel =
            createViewModel(
                repository = repository,
                groupRepository = groupRepository,
                registrationStore = registrationStore,
            )
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.sendYo(recipient = "ANA")
        groupRepository.createGroup(name = "CREW", memberUsernames = listOf("ANA"))
        dispatcher.scheduler.advanceUntilIdle()
        // Asserted through the repository, not viewModel.history: that is a WhileSubscribed
        // StateFlow, so its .value stays at the initial value while nothing is collecting it.
        assertTrue(repository.observeHistory().first().isNotEmpty())

        viewModel.deleteAccount()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(emptyList<YoMessage>(), repository.observeHistory().first())
        assertEquals(emptyList<Group>(), groupRepository.observeGroups().first())
        // Otherwise the next account to sign in here looks already-registered, never posts its
        // own token, and silently receives nothing.
        assertTrue(registrationStore.cleared)
    }

    @Test
    fun `a second tap while deleting does not delete twice`() = runTest {
        val backendApi = FakeYoBackendApi()
        val viewModel = createViewModel(backendApi = backendApi)
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.deleteAccount()
        viewModel.deleteAccount()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, backendApi.deleteAccountCalls)
    }

    private fun createViewModel(
        repository: FakeYoRepository = FakeYoRepository(),
        groupRepository: FakeGroupRepository = FakeGroupRepository(),
        friends: List<String> = emptyList(),
        friendsFailure: Throwable? = null,
        locationProvider: OneShotLocationProvider = FakeOneShotLocationProvider(),
        contactsRepository: ContactsRepository = FakeContactsRepository(),
        backendApi: FakeYoBackendApi = FakeYoBackendApi(friends, friendsFailure),
        sessionStore: FakeSessionStore = FakeSessionStore(),
        tokenProvider: FcmTokenProvider = FakeFcmTokenProvider(),
        registrationStore: FakeDeviceRegistrationStore = FakeDeviceRegistrationStore(),
    ): MainViewModel {
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
                    tokenProvider = tokenProvider,
                    registrationStore = registrationStore,
                    sessionStore = sessionStore,
                ),
            yoRepository = repository,
            groupRepository = groupRepository,
            locationProvider = locationProvider,
            contactsRepository = contactsRepository,
            buildInviteMessage = BuildInviteMessageUseCase(),
            filterContacts = FilterContactsUseCase(),
            sessionStore = sessionStore,
            deviceRegistrationStore = registrationStore,
            backendApi = backendApi,
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

        override suspend fun clear() {
            state.value = emptyList()
        }
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

        override suspend fun clear() {
            state.value = emptyList()
        }
    }

    private class FakeYoBackendApi(
        friends: List<String> = emptyList(),
        private val friendsFailure: Throwable? = null,
        private val addOutcome: AddFriendOutcome = AddFriendOutcome.Added,
        private val deleteAccountSucceeds: Boolean = true,
    ) : StubYoBackendApi() {
        var deleteAccountCalls = 0
            private set

        override suspend fun deleteAccount(): Boolean {
            deleteAccountCalls++
            return deleteAccountSucceeds
        }

        /** Mutable, so a re-fetch after an add or a remove genuinely sees a different list. */
        private val stored = friends.toMutableList()

        val addedFriends = mutableListOf<String>()
        val removedFriends = mutableListOf<String>()
        val blockedFriends = mutableListOf<String>()

        var fetchCount = 0
            private set

        var logOutCount = 0
            private set

        /** Reads the session store at the moment logOut runs, to pin down the ordering. */
        var sessionProbe: (() -> YoSession?)? = null
        var sessionAtLogOut: YoSession? = null
            private set

        override suspend fun fetchFriends(): List<String> {
            fetchCount += 1
            friendsFailure?.let { throw it }
            return stored.toList()
        }

        override suspend fun addFriend(username: String): AddFriendOutcome {
            addedFriends += username
            if (addOutcome == AddFriendOutcome.Added) {
                stored += username
            }
            return addOutcome
        }

        override suspend fun removeFriend(username: String): Boolean {
            removedFriends += username
            stored -= username
            return true
        }

        override suspend fun block(username: String): Boolean {
            blockedFriends += username
            stored -= username
            return true
        }

        override suspend fun logOut(): Boolean {
            logOutCount += 1
            sessionAtLogOut = sessionProbe?.invoke()
            return true
        }
    }

    private class FakeFcmTokenProvider(
        private val failuresBeforeSuccess: Int = 0,
    ) : FcmTokenProvider {
        private var calls = 0

        override suspend fun getToken(): String {
            calls += 1
            if (calls <= failuresBeforeSuccess) {
                throw IOException("SERVICE_NOT_AVAILABLE")
            }
            return "test-token"
        }
    }

    private class FakeDeviceRegistrationStore : DeviceRegistrationStore {
        var cleared = false
            private set

        override fun isRegistered(registration: DeviceRegistration): Boolean = false

        override fun markRegistered(registration: DeviceRegistration) = Unit

        override fun clear() {
            cleared = true
        }
    }
}

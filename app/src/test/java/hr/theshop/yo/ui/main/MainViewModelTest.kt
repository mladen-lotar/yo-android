package hr.theshop.yo.ui.main

import hr.theshop.yo.data.remote.AddFriendOutcome
import hr.theshop.yo.data.remote.YoBackendApi
import hr.theshop.yo.domain.location.LocationCoordinates
import hr.theshop.yo.domain.location.OneShotLocationProvider
import hr.theshop.yo.domain.model.DeviceRegistration
import hr.theshop.yo.domain.model.Group
import hr.theshop.yo.domain.model.PhoneContact
import hr.theshop.yo.domain.model.YoMessage
import hr.theshop.yo.domain.model.YoSendOutcome
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
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
import org.junit.Assert.assertSame
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

    // Gap G25: the band flashed "YO!" the instant it was tapped, from a `remember` set on click.
    // A Yo that was rate-limited, sent to a device that never registered, or dropped by a dead
    // network looked exactly like one that arrived. Everything below is that lie, spelled out.
    @Test
    fun `a send the server refused is never reported as delivered`() = runTest {
        val repository = FakeYoRepository(outcome = YoSendOutcome.NotDelivered)
        val viewModel = createViewModel(repository = repository)
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.sendYo("Alice")
        dispatcher.scheduler.advanceUntilIdle()

        assertNull(viewModel.sendDeliveredTo.value)
        assertNull(viewModel.sendInFlightTo.value)
        assertEquals("Alice", viewModel.sendFailure.value?.label)
        assertEquals(YoSendOutcome.NotDelivered, viewModel.sendFailure.value?.outcome)
    }

    @Test
    fun `an unreachable send is reported as unreachable, not merely undelivered`() = runTest {
        val repository = FakeYoRepository(outcome = YoSendOutcome.Unreachable)
        val viewModel = createViewModel(repository = repository)
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.sendYo("Alice")
        dispatcher.scheduler.advanceUntilIdle()

        assertNull(viewModel.sendDeliveredTo.value)
        assertEquals(YoSendOutcome.Unreachable, viewModel.sendFailure.value?.outcome)
    }

    @Test
    fun `a delivered send flashes for the recipient and reports no failure`() = runTest {
        val viewModel = createViewModel()
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.sendYo("Alice")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("Alice", viewModel.sendDeliveredTo.value)
        assertNull(viewModel.sendFailure.value)
        assertNull(viewModel.sendInFlightTo.value)
    }

    @Test
    fun `a send that works clears the failure left by the one before it`() = runTest {
        val repository = FakeYoRepository(outcome = YoSendOutcome.NotDelivered)
        val viewModel = createViewModel(repository = repository)
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.sendYo("Alice")
        dispatcher.scheduler.advanceUntilIdle()
        assertNotNull(viewModel.sendFailure.value)

        repository.outcome = YoSendOutcome.Delivered
        viewModel.sendYo("Alice")
        dispatcher.scheduler.advanceUntilIdle()

        assertNull(viewModel.sendFailure.value)
        assertEquals("Alice", viewModel.sendDeliveredTo.value)
    }

    @Test
    fun `retrySend re-issues the Yo to the same recipient`() = runTest {
        val repository = FakeYoRepository(outcome = YoSendOutcome.Unreachable)
        val viewModel = createViewModel(repository = repository)
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.sendYo("Alice", link = "https://example.com", hashtag = "worldcup")
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, repository.savedMessages.size)

        repository.outcome = YoSendOutcome.Delivered
        viewModel.retrySend()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(2, repository.savedMessages.size)
        // Same recipient and the same attachments: a retry that dropped what the user typed
        // would be a different Yo wearing the first one's label.
        assertEquals(listOf("Alice", "Alice"), repository.savedMessages.map { it.recipient })
        assertEquals(
            listOf("https://example.com", "https://example.com"),
            repository.savedMessages.map { it.link },
        )
        assertEquals("Alice", viewModel.sendDeliveredTo.value)
        assertNull(viewModel.sendFailure.value)
    }

    @Test
    fun `retrySend does nothing when nothing has failed`() = runTest {
        val repository = FakeYoRepository()
        val viewModel = createViewModel(repository = repository)
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.sendYo("Alice")
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.retrySend()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, repository.savedMessages.size)
    }

    // The other half of retrySend's two guards: nothing has been attempted at all, so there is no
    // attempt to re-issue. Without it, a retry control that is somehow reachable before the first
    // send would either do nothing quietly or resend whatever was left over from last time.
    @Test
    fun `retrySend does nothing before anything has been sent`() = runTest {
        val repository = FakeYoRepository()
        val viewModel = createViewModel(repository = repository)
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.retrySend()
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(repository.savedMessages.isEmpty())
        assertNull(viewModel.sendInFlightTo.value)
        assertNull(viewModel.sendDeliveredTo.value)
        assertNull(viewModel.sendFailure.value)
    }

    // Regression: runSend cleared the previous failure but not the previous success, so tapping
    // Bob while Alice's "YO!" flash was still on screen left Alice reading delivered next to
    // Bob's failure. Reproduced against the pre-fix build, where sendDeliveredTo stayed "Alice".
    @Test
    fun `a send to a second recipient clears the first recipient's delivered flash`() = runTest {
        val repository = FakeYoRepository()
        val viewModel = createViewModel(repository = repository)
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.sendYo("Alice")
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals("Alice", viewModel.sendDeliveredTo.value)

        repository.outcome = YoSendOutcome.NotDelivered
        viewModel.sendYo("Bob")
        dispatcher.scheduler.advanceUntilIdle()

        assertNull(viewModel.sendDeliveredTo.value)
        assertEquals("Bob", viewModel.sendFailure.value?.label)
        assertEquals(YoSendOutcome.NotDelivered, viewModel.sendFailure.value?.outcome)
    }

    // The same bug, pinned to the moment it is visible. Clearing the flash only once the second
    // send finishes would satisfy the test above and still leave both states on screen together
    // for as long as the request takes - which is the whole window the user is looking at.
    @Test
    fun `the previous delivered flash is gone as soon as the next send starts`() = runTest {
        val repository = FakeYoRepository()
        val viewModel = createViewModel(repository = repository)
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.sendYo("Alice")
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals("Alice", viewModel.sendDeliveredTo.value)

        val gate = CompletableDeferred<Unit>()
        repository.gate = gate
        viewModel.sendYo("Bob")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("Bob", viewModel.sendInFlightTo.value)
        assertNull(viewModel.sendDeliveredTo.value)

        gate.complete(Unit)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("Bob", viewModel.sendDeliveredTo.value)
    }

    // ---- Two sends in flight at once -------------------------------------------------------
    //
    // Nothing stops a second tap while the first send is still open, and the two can finish in
    // either order. Before the generation token, a slow send to Alice landed its verdict after a
    // fast send to Bob: Bob's "YO!" stayed on screen beside Alice's failure row, and the retry
    // under Alice's label re-sent to Bob.

    @Test
    fun `a superseded send never publishes its outcome`() = runTest {
        val repository = FakeYoRepository()
        repository.outcomes["Alice"] = YoSendOutcome.NotDelivered
        repository.outcomes["Bob"] = YoSendOutcome.Delivered
        repository.gates["Alice"] = CompletableDeferred()
        repository.gates["Bob"] = CompletableDeferred()
        val viewModel = createViewModel(repository = repository)
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.sendYo("Alice")
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.sendYo("Bob")
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals("Bob", viewModel.sendInFlightTo.value)

        // Reverse order: the newer send answers first.
        repository.gates.getValue("Bob").complete(Unit)
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals("Bob", viewModel.sendDeliveredTo.value)
        assertNull(viewModel.sendFailure.value)

        // Alice's verdict arrives late and belongs to a send the user has already moved on from.
        repository.gates.getValue("Alice").complete(Unit)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("Bob", viewModel.sendDeliveredTo.value)
        assertNull(viewModel.sendFailure.value)
        assertNull(viewModel.sendInFlightTo.value)
    }

    @Test
    fun `a stale send does not clear the newer send's in-flight marker`() = runTest {
        val repository = FakeYoRepository()
        repository.gates["Alice"] = CompletableDeferred()
        repository.gates["Bob"] = CompletableDeferred()
        val viewModel = createViewModel(repository = repository)
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.sendYo("Alice")
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.sendYo("Bob")
        dispatcher.scheduler.advanceUntilIdle()

        // The superseded send finishes while the one the user is actually watching is still open.
        repository.gates.getValue("Alice").complete(Unit)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("Bob", viewModel.sendInFlightTo.value)
        assertNull(viewModel.sendDeliveredTo.value)
        assertNull(viewModel.sendFailure.value)

        repository.gates.getValue("Bob").complete(Unit)
        dispatcher.scheduler.advanceUntilIdle()

        assertNull(viewModel.sendInFlightTo.value)
        assertEquals("Bob", viewModel.sendDeliveredTo.value)
    }

    @Test
    fun `retry re-issues the attempt that failed, not the most recent tap`() = runTest {
        val repository = FakeYoRepository(outcome = YoSendOutcome.NotDelivered)
        repository.gates["Alice"] = CompletableDeferred()
        repository.gates["Bob"] = CompletableDeferred()
        val viewModel = createViewModel(repository = repository)
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.sendYo("Alice")
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.sendYo("Bob")
        dispatcher.scheduler.advanceUntilIdle()

        repository.gates.getValue("Bob").complete(Unit)
        dispatcher.scheduler.advanceUntilIdle()
        repository.gates.getValue("Alice").complete(Unit)
        dispatcher.scheduler.advanceUntilIdle()

        // Bob is the send that owns the screen, so Bob is the failure the user is looking at.
        assertEquals("Bob", viewModel.sendFailure.value?.label)
        assertEquals(2, repository.savedMessages.size)

        viewModel.retrySend()
        dispatcher.scheduler.advanceUntilIdle()

        // The invariant, stated directly: the Yo the retry sends must go to the person named on
        // the row the retry button sits in. Storing the attempt anywhere other than beside the
        // failure lets these two drift apart silently.
        assertEquals(3, repository.savedMessages.size)
        assertEquals("Bob", repository.savedMessages.last().recipient)
        assertEquals("Bob", viewModel.sendFailure.value?.label)
    }

    // ---- Link normalization ----------------------------------------------------------------

    // "example.com" is what people type. Without normalization the most likely input travels the
    // whole way and is discarded at the very last step, which is the same shows-as-attached,
    // arrives-as-nothing failure the delivery work exists to remove.
    @Test
    fun `normalizeLink adds a web scheme only where one is missing`() {
        val viewModel = createViewModel()

        val cases =
            listOf(
                "example.com" to "https://example.com",
                "www.example.com/live" to "https://www.example.com/live",
                "  example.com  " to "https://example.com",
                // Already schemed: returned exactly as typed.
                "https://example.com" to "https://example.com",
                "http://example.com" to "http://example.com",
                "HTTPS://Example.com" to "HTTPS://Example.com",
                "" to null,
                "   " to null,
            )

        cases.forEach { (raw, expected) ->
            assertEquals("normalizeLink(\"$raw\")", expected, viewModel.normalizeLink(raw))
        }
        assertNull(viewModel.normalizeLink(null))
    }

    // The failure mode to guard is a future "helpful" version that prepends https:// to anything
    // without a leading "http". That would rewrite `javascript:alert(1)` into
    // `https://javascript:alert(1)` - a URL with a host, which YoNotifier.openableLink would then
    // happily hand to ACTION_VIEW. Laundering a hostile scheme into an openable one is strictly
    // worse than leaving it alone: left alone it is rejected downstream (see YoNotifierTest,
    // `openableLink accepts only http and https with a host`).
    @Test
    fun `normalizeLink never rewrites a non-web scheme into a web one`() {
        val viewModel = createViewModel()

        listOf(
            "javascript:alert(1)",
            "file:///etc/passwd",
            "content://com.other.provider/secret",
            "intent://foo#Intent;package=com.other;end",
            "mailto:someone@example.com",
        ).forEach { hostile ->
            val normalized = viewModel.normalizeLink(hostile)
            assertEquals("normalizeLink must not touch \"$hostile\"", hostile, normalized)
            assertFalse(
                "\"$hostile\" was laundered into a web URL: $normalized",
                normalized!!.startsWith("https://") || normalized.startsWith("http://"),
            )
        }
    }

    @Test
    fun `a typed bare domain is stored on the message as an openable https link`() = runTest {
        val repository = FakeYoRepository()
        val viewModel = createViewModel(repository = repository)
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.sendYo("Alice", link = "example.com")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("https://example.com", repository.savedMessages.single().link)
    }

    @Test
    fun `a group send that reached nobody is not reported as delivered`() = runTest {
        val group =
            Group(
                id = "group-1",
                name = "CREW",
                memberUsernames = listOf("Ada", "Lin", "Sam"),
            )
        val repository = FakeYoRepository(outcome = YoSendOutcome.NotDelivered)
        val viewModel =
            createViewModel(
                repository = repository,
                groupRepository = FakeGroupRepository(listOf(group)),
            )
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.sendYoToGroup(group.id, label = group.name)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(3, repository.savedMessages.size)
        assertNull(viewModel.sendDeliveredTo.value)
        assertEquals("CREW", viewModel.sendFailure.value?.label)
        assertEquals(YoSendOutcome.NotDelivered, viewModel.sendFailure.value?.outcome)
    }

    // A group whose membership resolved to nothing sent nothing, so reporting success because
    // the loop finished without error is the old unconditional flash one level up.
    @Test
    fun `a group send with no members is not reported as delivered`() = runTest {
        val group = Group(id = "group-1", name = "EMPTY", memberUsernames = emptyList())
        val viewModel = createViewModel(groupRepository = FakeGroupRepository(listOf(group)))
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.sendYoToGroup(group.id, label = group.name)
        dispatcher.scheduler.advanceUntilIdle()

        assertNull(viewModel.sendDeliveredTo.value)
        assertEquals(YoSendOutcome.NotDelivered, viewModel.sendFailure.value?.outcome)
    }

    @Test
    fun `a group send everybody received is reported as delivered`() = runTest {
        val group =
            Group(id = "group-1", name = "CREW", memberUsernames = listOf("Ada", "Lin"))
        val viewModel = createViewModel(groupRepository = FakeGroupRepository(listOf(group)))
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.sendYoToGroup(group.id, label = group.name)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("CREW", viewModel.sendDeliveredTo.value)
        assertNull(viewModel.sendFailure.value)
    }

    // In flight is its own state and must not be mistaken for either end state - reporting
    // delivered while the request is still open is exactly the bug, just earlier.
    @Test
    fun `a send still in flight is neither delivered nor failed`() = runTest {
        val repository = FakeYoRepository()
        val gate = CompletableDeferred<Unit>()
        repository.gate = gate
        val viewModel = createViewModel(repository = repository)
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.sendYo("Alice")
        // Runs the send coroutine up to the point where the fake parks on the gate, and no
        // further: the scheduler has nothing else to do while the send is outstanding.
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("Alice", viewModel.sendInFlightTo.value)
        assertNull(viewModel.sendDeliveredTo.value)
        assertNull(viewModel.sendFailure.value)

        gate.complete(Unit)
        dispatcher.scheduler.advanceUntilIdle()

        assertNull(viewModel.sendInFlightTo.value)
        assertEquals("Alice", viewModel.sendDeliveredTo.value)
    }

    @Test
    fun `retrySend is ignored while a send is still outstanding`() = runTest {
        val repository = FakeYoRepository(outcome = YoSendOutcome.NotDelivered)
        val viewModel = createViewModel(repository = repository)
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.sendYo("Alice")
        dispatcher.scheduler.advanceUntilIdle()

        val gate = CompletableDeferred<Unit>()
        repository.gate = gate
        viewModel.retrySend()
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.retrySend()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("Alice", viewModel.sendInFlightTo.value)
        assertEquals(2, repository.savedMessages.size)

        gate.complete(Unit)
        dispatcher.scheduler.advanceUntilIdle()
    }

    // There is no clearSendFailure counterpart: a failure is dismissed by the next send, or by
    // retrying it, and a method nothing called was dead weight pretending to be covered.
    @Test
    fun `the delivered flash can be dismissed by the composable's timer`() = runTest {
        val viewModel = createViewModel()
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.sendYo("Alice")
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals("Alice", viewModel.sendDeliveredTo.value)

        viewModel.clearSendDelivered()

        assertNull(viewModel.sendDeliveredTo.value)
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

        viewModel.sendYoToGroup(group.id, label = group.name)
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

    // Without a blocked list a block is a one-way door: the band disappears and there is no
    // surface anywhere that names who was blocked, so it can never be undone.
    @Test
    fun `blocking someone puts them on the blocked list`() = runTest {
        val backendApi = FakeYoBackendApi(friends = listOf("ADA", "LIN"))
        val viewModel = createViewModel(backendApi = backendApi)
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.blockFriend("LIN")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf("LIN"), viewModel.blocked.value)
        assertEquals(listOf("ADA"), viewModel.friends.value)
    }

    @Test
    fun `unblocking removes them from the blocked list`() = runTest {
        val backendApi = FakeYoBackendApi(friends = listOf("ADA", "LIN"))
        val viewModel = createViewModel(backendApi = backendApi)
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.blockFriend("LIN")
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.unblock("LIN")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf("LIN"), backendApi.unblockedUsers)
        assertTrue(viewModel.blocked.value.isEmpty())
    }

    // A failed read must not empty the list. The blocked sheet is the only place a block can be
    // undone, so replacing a real list with an empty one on a dropped request tells the user
    // they have blocked nobody and quietly removes the undo.
    @Test
    fun `a failed blocked read keeps the list that was already loaded`() = runTest {
        val backendApi = FakeYoBackendApi(friends = listOf("ADA", "LIN"))
        val viewModel = createViewModel(backendApi = backendApi)
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.blockFriend("LIN")
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf("LIN"), viewModel.blocked.value)

        backendApi.fetchBlockedFailure = IOException("offline")
        viewModel.refreshBlocked()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf("LIN"), viewModel.blocked.value)
    }

    // The direct contract: a cancelled read is NOT a failed read, and must propagate rather than
    // be absorbed as a value the way a plain runCatching would. loadBlocked is `internal` for
    // exactly this - in production it only ever runs inside viewModelScope.launch, where the
    // rethrow cancels the child and reaches no caller a test could hold.
    @Test
    fun `a cancelled blocked read propagates rather than being swallowed`() = runTest {
        val backendApi = FakeYoBackendApi(friends = listOf("ADA"))
        val viewModel = createViewModel(backendApi = backendApi)
        dispatcher.scheduler.advanceUntilIdle()
        val cancellation = CancellationException("scope gone")
        backendApi.fetchBlockedFailure = cancellation

        val thrown =
            try {
                viewModel.loadBlocked()
                null
            } catch (e: CancellationException) {
                e
            }

        assertSame(cancellation, thrown)
    }

    // The observable consequence of the same rule, kept alongside it: a cancelled read leaves the
    // list intact and the ViewModel usable rather than wedged.
    @Test
    fun `a cancelled blocked read leaves the list intact and the screen usable`() = runTest {
        val backendApi = FakeYoBackendApi(friends = listOf("ADA", "LIN"))
        val viewModel = createViewModel(backendApi = backendApi)
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.blockFriend("LIN")
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf("LIN"), viewModel.blocked.value)

        backendApi.fetchBlockedFailure = CancellationException("scope gone")
        viewModel.refreshBlocked()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf("LIN"), viewModel.blocked.value)

        backendApi.fetchBlockedFailure = null
        viewModel.unblock("LIN")
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.blocked.value.isEmpty())
    }

    @Test
    fun `refreshBlocked re-reads the list when the sheet opens`() = runTest {
        val backendApi = FakeYoBackendApi()
        val viewModel = createViewModel(backendApi = backendApi)
        dispatcher.scheduler.advanceUntilIdle()
        val before = backendApi.fetchBlockedCount

        viewModel.refreshBlocked()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(before + 1, backendApi.fetchBlockedCount)
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

    private class FakeYoRepository(
        var outcome: YoSendOutcome = YoSendOutcome.Delivered,
    ) : YoRepository {
        private val state = MutableStateFlow<List<YoMessage>>(emptyList())

        val savedMessages = mutableListOf<YoMessage>()

        /**
         * When set, saveSent parks here until the test completes it. That is the only way to
         * observe a send while it is still in flight: nothing inside runSend suspends on its own,
         * so without a gate the coroutine runs start-to-finish on the first dispatch and the
         * in-flight state is never visible to an assertion.
         */
        var gate: CompletableDeferred<Unit>? = null

        /**
         * Per-recipient gates and verdicts, so a test can hold two sends open at once and then
         * release them in whichever order it wants. Two taps racing is the normal case on a
         * screen made of bands you tap, and the order they finish in is not the order they
         * started in.
         */
        val gates = mutableMapOf<String, CompletableDeferred<Unit>>()
        val outcomes = mutableMapOf<String, YoSendOutcome>()

        override suspend fun saveSent(message: YoMessage): YoSendOutcome {
            state.value = listOf(message) + state.value
            savedMessages += message
            gate?.await()
            gates[message.recipient]?.await()
            return outcomes[message.recipient] ?: outcome
        }

        override fun observeHistory(): Flow<List<YoMessage>> = state

        override suspend fun clear() {
            state.value = emptyList()
            savedMessages.clear()
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
            storedBlocked += username
            return true
        }

        /** The server's blocked list, so a block and an unblock genuinely change what is fetched. */
        private val storedBlocked = mutableListOf<String>()

        val unblockedUsers = mutableListOf<String>()

        var fetchBlockedCount = 0
            private set

        /** Set to make the next read fail; a CancellationException here is not a failed read. */
        var fetchBlockedFailure: Throwable? = null

        override suspend fun fetchBlocked(): List<String> {
            fetchBlockedCount += 1
            fetchBlockedFailure?.let { throw it }
            return storedBlocked.toList()
        }

        override suspend fun unblock(username: String): Boolean {
            unblockedUsers += username
            storedBlocked -= username
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

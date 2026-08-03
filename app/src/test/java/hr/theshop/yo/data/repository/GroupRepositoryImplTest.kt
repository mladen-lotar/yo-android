package hr.theshop.yo.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import hr.theshop.yo.data.local.YoDatabase
import hr.theshop.yo.domain.model.YoSession
import hr.theshop.yo.domain.repository.SessionStore
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GroupRepositoryImplTest {
    // Room's generated DAO wraps every call in withContext(database.queryContext), so the
    // repository cannot choose the dispatcher — the database does. Left at its default that is
    // ArchTaskExecutor's shared fixed 4-thread pool, and a starved handoff to it can outlast
    // runTest's 10s wall-clock budget for a query that takes microseconds of actual work.
    // Handing Room the test dispatcher keeps the DAO work on the test scheduler instead.
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var database: YoDatabase
    private lateinit var repository: GroupRepositoryImpl

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database =
            Room.inMemoryDatabaseBuilder(context, YoDatabase::class.java)
                .allowMainThreadQueries()
                .setQueryExecutor(testDispatcher.asExecutor())
                .setTransactionExecutor(testDispatcher.asExecutor())
                .build()
        // Room opens the database lazily on the first query, so whichever test runs first would
        // otherwise be charged the one-time SQLite open plus schema and invalidation-trigger
        // creation inside runTest's 10s budget. Force it here, outside the timed window.
        database.openHelper.writableDatabase
        repository = GroupRepositoryImpl(database.groupDao(), FakeSessionStore("me"))
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `createGroup persists the group and its members`() = runTest(testDispatcher) {
        val created = repository.createGroup("Friends", listOf("Ada", "Lin"))

        val persisted = repository.observeGroups().first().single()
        assertEquals(created.id, persisted.id)
        assertEquals("Friends", persisted.name)
        assertEquals(setOf("Ada", "Lin"), persisted.memberUsernames.toSet())
    }

    @Test
    fun `getGroup returns null for an unknown id`() = runTest(testDispatcher) {
        assertEquals(null, repository.getGroup("missing"))
    }

    @Test
    fun `createGroup with a duplicate name creates a distinct group`() = runTest(testDispatcher) {
        val first = repository.createGroup("Friends", listOf("Ada"))
        val second = repository.createGroup("Friends", listOf("Lin"))

        assertNotEquals(first.id, second.id)
        val groups = repository.observeGroups().first()
        assertEquals(2, groups.size)
        assertEquals(setOf(first.id, second.id), groups.map { group -> group.id }.toSet())
    }

    // Owner-scoping: a group created under one account must be invisible to another account on
    // the same device, and that other account's own getGroup lookup must not resolve it either -
    // otherwise a stale groupId could still be used to fan a Yo out to someone else's group.
    @Test
    fun `two accounts on the same device never see each other's groups`() = runTest(testDispatcher) {
        val aliceRepository = GroupRepositoryImpl(database.groupDao(), FakeSessionStore("alice"))
        val bobRepository = GroupRepositoryImpl(database.groupDao(), FakeSessionStore("bob"))

        val aliceGroup = aliceRepository.createGroup("Alice's Friends", listOf("Ada"))
        bobRepository.createGroup("Bob's Friends", listOf("Lin"))

        assertEquals(
            listOf("Alice's Friends"),
            aliceRepository.observeGroups().first().map { it.name },
        )
        assertEquals(
            listOf("Bob's Friends"),
            bobRepository.observeGroups().first().map { it.name },
        )
        assertNull(
            "a group id from another account must not resolve",
            bobRepository.getGroup(aliceGroup.id),
        )
    }

    // This is what ClearLocalAccountDataUseCase relies on: clear() must scope to whichever
    // account is signed in at the moment it runs, or logging out would cost the user their own
    // groups for nothing while never showing them to anyone else.
    @Test
    fun `clear removes only the signed-in account's groups`() = runTest(testDispatcher) {
        val aliceRepository = GroupRepositoryImpl(database.groupDao(), FakeSessionStore("alice"))
        val bobRepository = GroupRepositoryImpl(database.groupDao(), FakeSessionStore("bob"))

        aliceRepository.createGroup("Alice's Friends", listOf("Ada"))
        bobRepository.createGroup("Bob's Friends", listOf("Lin"))

        aliceRepository.clear()

        assertTrue(aliceRepository.observeGroups().first().isEmpty())
        assertEquals(
            listOf("Bob's Friends"),
            bobRepository.observeGroups().first().map { it.name },
        )
    }

    private class FakeSessionStore(username: String) : SessionStore {
        private val state = MutableStateFlow<YoSession?>(YoSession(username = username, token = "token"))

        override val session: StateFlow<YoSession?> = state.asStateFlow()

        override fun current(): YoSession? = state.value

        override fun save(session: YoSession) {
            state.value = session
        }

        override fun clear() {
            state.value = null
        }
    }
}

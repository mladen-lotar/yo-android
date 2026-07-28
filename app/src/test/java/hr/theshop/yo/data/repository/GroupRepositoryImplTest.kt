package hr.theshop.yo.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import hr.theshop.yo.data.local.YoDatabase
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
        repository = GroupRepositoryImpl(database.groupDao())
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
}

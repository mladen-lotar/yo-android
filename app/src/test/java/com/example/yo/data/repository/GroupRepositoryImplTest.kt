package com.example.yo.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.yo.data.local.YoDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GroupRepositoryImplTest {
    @Test
    fun `createGroup persists the group and its members`() = runTest {
        val database = createDatabase()
        try {
            val repository = GroupRepositoryImpl(database.groupDao())

            val created = repository.createGroup("Friends", listOf("Ada", "Lin"))

            val persisted = repository.observeGroups().first().single()
            assertEquals(created.id, persisted.id)
            assertEquals("Friends", persisted.name)
            assertEquals(setOf("Ada", "Lin"), persisted.memberUsernames.toSet())
        } finally {
            database.close()
        }
    }

    @Test
    fun `getGroup returns null for an unknown id`() = runTest {
        val database = createDatabase()
        try {
            val repository = GroupRepositoryImpl(database.groupDao())

            assertEquals(null, repository.getGroup("missing"))
        } finally {
            database.close()
        }
    }

    @Test
    fun `createGroup with a duplicate name creates a distinct group`() = runTest {
        val database = createDatabase()
        try {
            val repository = GroupRepositoryImpl(database.groupDao())

            val first = repository.createGroup("Friends", listOf("Ada"))
            val second = repository.createGroup("Friends", listOf("Lin"))

            assertNotEquals(first.id, second.id)
            val groups = repository.observeGroups().first()
            assertEquals(2, groups.size)
            assertEquals(setOf(first.id, second.id), groups.map { group -> group.id }.toSet())
        } finally {
            database.close()
        }
    }

    private fun createDatabase(): YoDatabase {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return Room.inMemoryDatabaseBuilder(context, YoDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }
}

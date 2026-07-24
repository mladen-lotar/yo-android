package com.example.yo.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.yo.data.local.YoDatabase
import com.example.yo.domain.model.YoMessage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class YoRepositoryImplTest {
    @Test
    fun `saveSent persists a message returned by observeHistory`() = runTest {
        val database = createDatabase()
        try {
            val repository = YoRepositoryImpl(database.yoDao())
            val message =
                YoMessage(
                    id = "message-1",
                    sender = "me",
                    recipient = "Ada",
                    timestamp = 1_000L,
                    link = "https://example.com",
                    hashtag = "#hello",
                    latitude = 45.815,
                    longitude = 15.982,
                    photoUri = "content://photos/message-1",
                )

            repository.saveSent(message)

            assertEquals(listOf(message), repository.observeHistory().first())
        } finally {
            database.close()
        }
    }

    @Test
    fun `observeHistory emits messages in descending timestamp order`() = runTest {
        val database = createDatabase()
        try {
            val repository = YoRepositoryImpl(database.yoDao())
            val older =
                YoMessage(
                    id = "older",
                    sender = "me",
                    recipient = "Ada",
                    timestamp = 1_000L,
                )
            val newer =
                YoMessage(
                    id = "newer",
                    sender = "me",
                    recipient = "Lin",
                    timestamp = 2_000L,
                )

            repository.saveSent(older)
            repository.saveSent(newer)

            assertEquals(listOf(newer, older), repository.observeHistory().first())
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

package com.example.yo.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.yo.data.local.YoDatabase
import com.example.yo.domain.model.YoMessage
import com.example.yo.domain.repository.YoRemoteDeliveryPort
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class YoRepositoryImplTest {
    @Test
    fun `saveSent persists locally and delivers the message exactly once`() = runTest {
        val database = createDatabase()
        try {
            val remoteDeliveryPort = FakeYoRemoteDeliveryPort()
            val repository = YoRepositoryImpl(database.yoDao(), remoteDeliveryPort)
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
            assertEquals(listOf(message), remoteDeliveryPort.deliveredMessages)
        } finally {
            database.close()
        }
    }

    @Test
    fun `saveSent keeps the local message when remote delivery returns false`() = runTest {
        val database = createDatabase()
        try {
            val remoteDeliveryPort = FakeYoRemoteDeliveryPort(deliveryResult = false)
            val repository = YoRepositoryImpl(database.yoDao(), remoteDeliveryPort)
            val message =
                YoMessage(
                    id = "message-false",
                    sender = "me",
                    recipient = "Ada",
                    timestamp = 1_000L,
                )

            repository.saveSent(message)

            assertEquals(listOf(message), repository.observeHistory().first())
            assertEquals(listOf(message), remoteDeliveryPort.deliveredMessages)
        } finally {
            database.close()
        }
    }

    @Test
    fun `saveSent keeps the local message when remote delivery throws`() = runTest {
        val database = createDatabase()
        try {
            val remoteDeliveryPort =
                FakeYoRemoteDeliveryPort(deliveryFailure = IllegalStateException("offline"))
            val repository = YoRepositoryImpl(database.yoDao(), remoteDeliveryPort)
            val message =
                YoMessage(
                    id = "message-throw",
                    sender = "me",
                    recipient = "Ada",
                    timestamp = 1_000L,
                )

            repository.saveSent(message)

            assertEquals(listOf(message), repository.observeHistory().first())
            assertEquals(listOf(message), remoteDeliveryPort.deliveredMessages)
        } finally {
            database.close()
        }
    }

    @Test
    fun `observeHistory emits messages in descending timestamp order`() = runTest {
        val database = createDatabase()
        try {
            val repository = YoRepositoryImpl(database.yoDao(), FakeYoRemoteDeliveryPort())
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

    private class FakeYoRemoteDeliveryPort(
        private val deliveryResult: Boolean = true,
        private val deliveryFailure: Throwable? = null,
    ) : YoRemoteDeliveryPort {
        val deliveredMessages = mutableListOf<YoMessage>()

        override suspend fun deliver(message: YoMessage): Boolean {
            deliveredMessages += message
            deliveryFailure?.let { throw it }
            return deliveryResult
        }
    }
}

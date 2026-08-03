package hr.theshop.yo.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import hr.theshop.yo.data.local.YoDatabase
import hr.theshop.yo.data.remote.YoRemoteDeliveryPortImpl
import hr.theshop.yo.domain.model.YoMessage
import hr.theshop.yo.domain.model.YoSendOutcome
import hr.theshop.yo.domain.model.YoSession
import hr.theshop.yo.domain.repository.SessionStore
import hr.theshop.yo.domain.repository.YoRemoteDeliveryPort
import hr.theshop.yo.testing.FakeYoBackendApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
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
            val repository = YoRepositoryImpl(database.yoDao(), remoteDeliveryPort, FakeSessionStore("me"))
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
                )

            val outcome = repository.saveSent(message)

            assertEquals(YoSendOutcome.Delivered, outcome)
            // The row now carries the VERDICT, which is the whole of G29: the transient band
            // said YO! and passed, while this row - the surface that lasts - said nothing.
            assertEquals(listOf(message.copy(delivered = true)), repository.observeHistory().first())
            assertEquals(listOf(message), remoteDeliveryPort.deliveredMessages)
        } finally {
            database.close()
        }
    }

    // The local row surviving is only half the contract. Asserting the row alone is what let a
    // send that the server refused look identical to one it accepted, all the way up to the band
    // flashing "YO!": the failure had nowhere to be reported, so nothing reported it.
    @Test
    fun `saveSent keeps the local message and reports NotDelivered when the server says no`() =
        runTest {
            val database = createDatabase()
            try {
                val remoteDeliveryPort = FakeYoRemoteDeliveryPort(outcome = YoSendOutcome.NotDelivered)
                val repository =
                    YoRepositoryImpl(database.yoDao(), remoteDeliveryPort, FakeSessionStore("me"))
                val message =
                    YoMessage(
                        id = "message-false",
                        sender = "me",
                        recipient = "Ada",
                        timestamp = 1_000L,
                    )

                val outcome = repository.saveSent(message)

                assertEquals(YoSendOutcome.NotDelivered, outcome)
                assertEquals(
                    listOf(message.copy(delivered = false)),
                    repository.observeHistory().first(),
                )
                assertEquals(listOf(message), remoteDeliveryPort.deliveredMessages)
            } finally {
                database.close()
            }
        }

    // RED-FIRST (defect 2): the port used to return a plain Boolean, which destroys the
    // 400-vs-500 distinction before this method ever sees it - a permanent server rejection and a
    // transient failure both collapsed to the same NotDelivered, so retrySend() kept re-issuing a
    // Yo the server had already refused for good. This wires the REAL YoRemoteDeliveryPortImpl in
    // front of the fake backend, rather than a hand-rolled port fake, specifically so it exercises
    // the exact collapse the production code path was performing.
    @Test
    fun `saveSent reports Rejected, not NotDelivered, when the backend permanently refuses the send`() =
        runTest {
            val database = createDatabase()
            try {
                val backendApi = FakeYoBackendApi(sendOutcome = YoSendOutcome.Rejected)
                val remoteDeliveryPort = YoRemoteDeliveryPortImpl(backendApi)
                val repository =
                    YoRepositoryImpl(database.yoDao(), remoteDeliveryPort, FakeSessionStore("me"))
                val message =
                    YoMessage(
                        id = "message-rejected",
                        sender = "me",
                        recipient = "Ada",
                        timestamp = 1_000L,
                    )

                val outcome = repository.saveSent(message)

                assertEquals(YoSendOutcome.Rejected, outcome)
                // Rejected is still not-delivered as far as history is concerned - the row must
                // not be shown as having arrived just because the failure was a permanent one.
                assertEquals(
                    listOf(message.copy(delivered = false)),
                    repository.observeHistory().first(),
                )
            } finally {
                database.close()
            }
        }

    // A transient failure must keep looking retryable through the same port the Rejected case
    // just went through, so the fix above cannot accidentally widen NotDelivered into Rejected.
    @Test
    fun `saveSent keeps reporting NotDelivered, not Rejected, when the backend answers a transient failure`() =
        runTest {
            val database = createDatabase()
            try {
                val backendApi = FakeYoBackendApi(sendOutcome = YoSendOutcome.NotDelivered)
                val remoteDeliveryPort = YoRemoteDeliveryPortImpl(backendApi)
                val repository =
                    YoRepositoryImpl(database.yoDao(), remoteDeliveryPort, FakeSessionStore("me"))
                val message =
                    YoMessage(
                        id = "message-transient",
                        sender = "me",
                        recipient = "Ada",
                        timestamp = 1_000L,
                    )

                val outcome = repository.saveSent(message)

                assertEquals(YoSendOutcome.NotDelivered, outcome)
            } finally {
                database.close()
            }
        }

    @Test
    fun `saveSent keeps the local message and reports Unreachable when delivery throws`() =
        runTest {
            val database = createDatabase()
            try {
                val remoteDeliveryPort =
                    FakeYoRemoteDeliveryPort(deliveryFailure = IllegalStateException("offline"))
                val repository =
                    YoRepositoryImpl(database.yoDao(), remoteDeliveryPort, FakeSessionStore("me"))
                val message =
                    YoMessage(
                        id = "message-throw",
                        sender = "me",
                        recipient = "Ada",
                        timestamp = 1_000L,
                    )

                val outcome = repository.saveSent(message)

                assertEquals(YoSendOutcome.Unreachable, outcome)
                // The link and hashtag exist only in this row, so discarding it on a failed send
                // would destroy what the user typed at the moment they want to retry.
                assertEquals(
                    listOf(message.copy(delivered = false)),
                    repository.observeHistory().first(),
                )
                assertEquals(listOf(message), remoteDeliveryPort.deliveredMessages)
            } finally {
                database.close()
            }
        }

    // Cancellation is not a delivery failure - it means the caller's scope is already gone. A
    // `runCatching`, or a `catch (e: Throwable)` without this case in front of it, turns it into
    // YoSendOutcome.Unreachable, which resumes a dead coroutine and breaks structured
    // concurrency. This is the assertion that tells those two implementations apart.
    @Test
    fun `saveSent propagates cancellation rather than reporting a failed send`() = runTest {
        val database = createDatabase()
        try {
            val cancellation = CancellationException("scope gone")
            val remoteDeliveryPort = FakeYoRemoteDeliveryPort(deliveryFailure = cancellation)
            val repository =
                YoRepositoryImpl(database.yoDao(), remoteDeliveryPort, FakeSessionStore("me"))
            val message =
                YoMessage(
                    id = "message-cancelled",
                    sender = "me",
                    recipient = "Ada",
                    timestamp = 1_000L,
                )

            var outcome: YoSendOutcome? = null
            val thrown =
                try {
                    outcome = repository.saveSent(message)
                    null
                } catch (e: CancellationException) {
                    e
                }

            assertSame(cancellation, thrown)
            assertEquals(null, outcome)
            // The row is still written: saveSent persists before it ever tries to deliver.
            // delivered stays NULL on purpose: a cancelled send did not fail, its outcome is
            // simply unknown, and marking it false would invent a failure the user never had.
            assertEquals(listOf(message), repository.observeHistory().first())
        } finally {
            database.close()
        }
    }

    @Test
    fun `observeHistory emits messages in descending timestamp order`() = runTest {
        val database = createDatabase()
        try {
            val repository =
                YoRepositoryImpl(database.yoDao(), FakeYoRemoteDeliveryPort(), FakeSessionStore("me"))
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

            assertEquals(
                listOf(newer.copy(delivered = true), older.copy(delivered = true)),
                repository.observeHistory().first(),
            )
        } finally {
            database.close()
        }
    }

    // Owner-scoping: the whole reason this column exists is that a device is not always the same
    // account. A row saved under one account must never surface in another account's history,
    // even though both live in the same yo_messages table on the same device.
    @Test
    fun `two accounts on the same device never see each other's history`() = runTest {
        val database = createDatabase()
        try {
            val remoteDeliveryPort = FakeYoRemoteDeliveryPort()
            val aliceRepository =
                YoRepositoryImpl(database.yoDao(), remoteDeliveryPort, FakeSessionStore("alice"))
            val bobRepository =
                YoRepositoryImpl(database.yoDao(), remoteDeliveryPort, FakeSessionStore("bob"))

            aliceRepository.saveSent(
                YoMessage(id = "alice-1", sender = "alice", recipient = "x", timestamp = 1_000L),
            )
            bobRepository.saveSent(
                YoMessage(id = "bob-1", sender = "bob", recipient = "y", timestamp = 2_000L),
            )

            assertEquals(
                listOf("alice-1"),
                aliceRepository.observeHistory().first().map { it.id },
            )
            assertEquals(
                listOf("bob-1"),
                bobRepository.observeHistory().first().map { it.id },
            )
        } finally {
            database.close()
        }
    }

    // This is what ClearLocalAccountDataUseCase relies on: clear() must scope to whichever
    // account is signed in at the moment it runs, not wipe the whole table, or logging out costs
    // the user their own history for nothing while never showing it to anyone else.
    @Test
    fun `clear removes only the signed-in account's history`() = runTest {
        val database = createDatabase()
        try {
            val remoteDeliveryPort = FakeYoRemoteDeliveryPort()
            val aliceRepository =
                YoRepositoryImpl(database.yoDao(), remoteDeliveryPort, FakeSessionStore("alice"))
            val bobRepository =
                YoRepositoryImpl(database.yoDao(), remoteDeliveryPort, FakeSessionStore("bob"))

            aliceRepository.saveSent(
                YoMessage(id = "alice-1", sender = "alice", recipient = "x", timestamp = 1_000L),
            )
            bobRepository.saveSent(
                YoMessage(id = "bob-1", sender = "bob", recipient = "y", timestamp = 2_000L),
            )

            aliceRepository.clear()

            assertTrue(aliceRepository.observeHistory().first().isEmpty())
            assertEquals(
                listOf("bob-1"),
                bobRepository.observeHistory().first().map { it.id },
            )
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
        private val outcome: YoSendOutcome = YoSendOutcome.Delivered,
        private val deliveryFailure: Throwable? = null,
    ) : YoRemoteDeliveryPort {
        val deliveredMessages = mutableListOf<YoMessage>()

        override suspend fun deliver(message: YoMessage): YoSendOutcome {
            deliveredMessages += message
            deliveryFailure?.let { throw it }
            return outcome
        }
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

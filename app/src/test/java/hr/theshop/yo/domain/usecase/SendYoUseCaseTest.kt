package hr.theshop.yo.domain.usecase

import hr.theshop.yo.domain.model.YoMessage
import hr.theshop.yo.domain.model.YoSendOutcome
import hr.theshop.yo.domain.repository.YoRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class SendYoUseCaseTest {
    // invoke now returns what happened to the Yo rather than the Yo itself. The message is still
    // fully observable through the repository it was handed to, which is where these moved.
    @Test
    fun `invoke saves a message with the supplied sender and recipient`() = runTest {
        val repository = FakeYoRepository()
        val useCase = SendYoUseCase(repository)

        useCase(sender = "me", recipient = "Ada")

        val saved = repository.savedMessages.single()
        assertEquals("me", saved.sender)
        assertEquals("Ada", saved.recipient)
    }

    @Test
    fun `invoke saves the constructed message exactly once`() = runTest {
        val repository = FakeYoRepository()
        val useCase = SendYoUseCase(repository)

        useCase(sender = "me", recipient = "Lin")

        assertEquals(1, repository.saveCallCount)
        assertEquals("Lin", repository.savedMessages.single().recipient)
    }

    @Test
    fun `invoke applies extras before saving`() = runTest {
        val repository = FakeYoRepository()
        val useCase = SendYoUseCase(repository)

        useCase(sender = "me", recipient = "Sam") {
            copy(link = "https://example.com")
        }

        assertEquals("https://example.com", repository.savedMessages.single().link)
    }

    // The whole point of the return type: a caller that cannot see the outcome cannot tell the
    // user their Yo did not arrive. Every case is covered, so a use case that hardcoded
    // Delivered would fail rather than pass two thirds of the time.
    @Test
    fun `invoke reports the outcome the repository produced`() = runTest {
        YoSendOutcome.entries.forEach { expected ->
            val repository = FakeYoRepository(outcome = expected)

            val outcome = SendYoUseCase(repository)(sender = "me", recipient = "Ada")

            assertEquals(expected, outcome)
        }
    }

    private class FakeYoRepository(
        private val outcome: YoSendOutcome = YoSendOutcome.Delivered,
    ) : YoRepository {
        override suspend fun clear() {
            savedMessages.clear()
        }

        val savedMessages = mutableListOf<YoMessage>()
        var saveCallCount = 0

        override suspend fun saveSent(message: YoMessage): YoSendOutcome {
            saveCallCount += 1
            savedMessages += message
            return outcome
        }

        override fun observeHistory(): Flow<List<YoMessage>> = flowOf(savedMessages.toList())
    }
}

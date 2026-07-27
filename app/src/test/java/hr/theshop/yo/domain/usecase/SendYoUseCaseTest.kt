package hr.theshop.yo.domain.usecase

import hr.theshop.yo.domain.model.YoMessage
import hr.theshop.yo.domain.repository.YoRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class SendYoUseCaseTest {
    @Test
    fun `invoke returns a message with the supplied sender and recipient`() = runTest {
        val repository = FakeYoRepository()
        val useCase = SendYoUseCase(repository)

        val message = useCase(sender = "me", recipient = "Ada")

        assertEquals("me", message.sender)
        assertEquals("Ada", message.recipient)
    }

    @Test
    fun `invoke saves the constructed message exactly once`() = runTest {
        val repository = FakeYoRepository()
        val useCase = SendYoUseCase(repository)

        val message = useCase(sender = "me", recipient = "Lin")

        assertEquals(1, repository.saveCallCount)
        assertEquals(message, repository.savedMessages.single())
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

    private class FakeYoRepository : YoRepository {
        val savedMessages = mutableListOf<YoMessage>()
        var saveCallCount = 0

        override suspend fun saveSent(message: YoMessage) {
            saveCallCount += 1
            savedMessages += message
        }

        override fun observeHistory(): Flow<List<YoMessage>> = flowOf(savedMessages.toList())
    }
}

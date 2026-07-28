package hr.theshop.yo.domain.usecase

import hr.theshop.yo.domain.model.Group
import hr.theshop.yo.domain.model.YoMessage
import hr.theshop.yo.domain.model.YoSendOutcome
import hr.theshop.yo.domain.repository.GroupRepository
import hr.theshop.yo.domain.repository.YoRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class SendYoToGroupUseCaseTest {
    @Test
    fun `invoke sends one Yo per group member through SendYoUseCase`() = runTest {
        val group =
            Group(
                id = "group-1",
                name = "Friends",
                memberUsernames = listOf("Ada", "Lin", "Sam"),
            )
        val yoRepository = FakeYoRepository()
        val useCase =
            SendYoToGroupUseCase(
                groupRepository = FakeGroupRepository(listOf(group)),
                sendYoUseCase = SendYoUseCase(yoRepository),
            )

        useCase(sender = "me", groupId = group.id)

        assertEquals(3, yoRepository.saveCallCount)
        assertEquals(
            group.memberUsernames,
            yoRepository.savedMessages.map { message -> message.recipient },
        )
    }

    @Test
    fun `invoke applies extras to every fanned-out message`() = runTest {
        val group =
            Group(
                id = "group-1",
                name = "Friends",
                memberUsernames = listOf("Ada", "Lin", "Sam"),
            )
        val yoRepository = FakeYoRepository()
        val useCase =
            SendYoToGroupUseCase(
                groupRepository = FakeGroupRepository(listOf(group)),
                sendYoUseCase = SendYoUseCase(yoRepository),
            )

        useCase(sender = "me", groupId = group.id) {
            copy(link = "https://example.com")
        }

        assertEquals(
            List(group.memberUsernames.size) { "https://example.com" },
            yoRepository.savedMessages.map { message -> message.link },
        )
    }

    // One outcome per member, not one for the fan-out: the caller cannot say "nobody got it"
    // unless it can see what happened to each of them.
    @Test
    fun `invoke returns one outcome per member`() = runTest {
        val group =
            Group(
                id = "group-1",
                name = "Friends",
                memberUsernames = listOf("Ada", "Lin", "Sam"),
            )
        val yoRepository = FakeYoRepository(outcome = YoSendOutcome.NotDelivered)
        val useCase =
            SendYoToGroupUseCase(
                groupRepository = FakeGroupRepository(listOf(group)),
                sendYoUseCase = SendYoUseCase(yoRepository),
            )

        val outcomes = useCase(sender = "me", groupId = group.id)

        assertEquals(List(3) { YoSendOutcome.NotDelivered }, outcomes)
    }

    @Test
    fun `invoke returns empty list when group does not exist`() = runTest {
        val yoRepository = FakeYoRepository()
        val useCase =
            SendYoToGroupUseCase(
                groupRepository = FakeGroupRepository(),
                sendYoUseCase = SendYoUseCase(yoRepository),
            )

        val result = useCase(sender = "me", groupId = "missing")

        assertEquals(0, yoRepository.saveCallCount)
        assertEquals(emptyList<YoSendOutcome>(), result)
    }

    @Test
    fun `invoke returns empty list for a group with no members`() = runTest {
        val group =
            Group(
                id = "group-1",
                name = "Empty",
                memberUsernames = emptyList(),
            )
        val yoRepository = FakeYoRepository()
        val useCase =
            SendYoToGroupUseCase(
                groupRepository = FakeGroupRepository(listOf(group)),
                sendYoUseCase = SendYoUseCase(yoRepository),
            )

        val result = useCase(sender = "me", groupId = group.id)

        assertEquals(0, yoRepository.saveCallCount)
        assertEquals(emptyList<YoSendOutcome>(), result)
    }

    private class FakeGroupRepository(
        private val groups: List<Group> = emptyList(),
    ) : GroupRepository {
        override suspend fun clear() = Unit

        override suspend fun createGroup(
            name: String,
            memberUsernames: List<String>,
        ): Group = Group(name = name, memberUsernames = memberUsernames)

        override fun observeGroups(): Flow<List<Group>> = flowOf(groups)

        override suspend fun getGroup(groupId: String): Group? =
            groups.firstOrNull { group -> group.id == groupId }
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

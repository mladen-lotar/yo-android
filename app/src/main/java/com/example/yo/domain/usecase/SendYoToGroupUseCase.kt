package com.example.yo.domain.usecase

import com.example.yo.domain.model.YoMessage
import com.example.yo.domain.repository.GroupRepository
import javax.inject.Inject

/**
 * Fans a single Yo out to every member of a group. Resolves membership then delegates to the
 * one shared SendYoUseCase per recipient -- this is a recipient-resolution step, not a second
 * send pipeline. Do not duplicate SendYoUseCase's persistence/delivery logic here.
 */
class SendYoToGroupUseCase @Inject constructor(
    private val groupRepository: GroupRepository,
    private val sendYoUseCase: SendYoUseCase,
) {
    suspend operator fun invoke(
        sender: String,
        groupId: String,
        extras: YoMessage.() -> YoMessage = { this },
    ): List<YoMessage> {
        val group = groupRepository.getGroup(groupId) ?: return emptyList()
        return group.memberUsernames.map { member ->
            sendYoUseCase(sender = sender, recipient = member, extras = extras)
        }
    }
}

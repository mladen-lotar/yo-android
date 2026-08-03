package hr.theshop.yo.data.repository

import hr.theshop.yo.data.local.GroupDao
import hr.theshop.yo.data.local.GroupEntity
import hr.theshop.yo.data.local.GroupMemberEntity
import hr.theshop.yo.data.local.GroupWithMembers
import hr.theshop.yo.domain.model.Group
import hr.theshop.yo.domain.repository.GroupRepository
import hr.theshop.yo.domain.repository.SessionStore
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

class GroupRepositoryImpl @Inject constructor(
    private val groupDao: GroupDao,
    private val sessionStore: SessionStore,
) : GroupRepository {
    override suspend fun createGroup(
        name: String,
        memberUsernames: List<String>,
    ): Group {
        val account = sessionStore.current()?.username.orEmpty().ifEmpty { null }
        val group = Group(name = name, memberUsernames = memberUsernames)
        groupDao.insertGroupWithMembers(
            group = group.toEntity(account),
            members =
                group.memberUsernames.map { username ->
                    GroupMemberEntity(groupId = group.id, username = username)
                },
        )
        return group
    }

    /**
     * Scopes the observed groups to the signed-in account - see `YoRepositoryImpl.observeHistory`
     * for why no NULL-owner claim is needed here either.
     */
    override fun observeGroups(): Flow<List<Group>> =
        flow {
            val account = sessionStore.current()?.username.orEmpty()
            if (account.isNotEmpty()) {
                emitAll(
                    groupDao.observeGroupsWithMembers(account)
                        .map { groups -> groups.map(GroupWithMembers::toDomain) },
                )
            } else {
                emitAll(flowOf(emptyList()))
            }
        }

    override suspend fun getGroup(groupId: String): Group? {
        val account = sessionStore.current()?.username.orEmpty()
        if (account.isEmpty()) return null
        return groupDao.getGroupWithMembers(groupId, account)?.toDomain()
    }

    /** Clears only the currently signed-in account's groups - see `ClearLocalAccountDataUseCase`. */
    override suspend fun clear() {
        val account = sessionStore.current()?.username.orEmpty()
        if (account.isNotEmpty()) {
            groupDao.deleteForOwner(account)
        }
    }
}

private fun Group.toEntity(ownerAccount: String?) =
    GroupEntity(
        id = id,
        name = name,
        ownerAccount = ownerAccount,
    )

private fun GroupWithMembers.toDomain() =
    Group(
        id = group.id,
        name = group.name,
        memberUsernames = members.map { it.username },
    )

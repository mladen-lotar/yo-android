package hr.theshop.yo.data.repository

import hr.theshop.yo.data.local.GroupDao
import hr.theshop.yo.data.local.GroupEntity
import hr.theshop.yo.data.local.GroupMemberEntity
import hr.theshop.yo.data.local.GroupWithMembers
import hr.theshop.yo.domain.model.Group
import hr.theshop.yo.domain.repository.GroupRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class GroupRepositoryImpl @Inject constructor(
    private val groupDao: GroupDao,
) : GroupRepository {
    override suspend fun createGroup(
        name: String,
        memberUsernames: List<String>,
    ): Group {
        val group = Group(name = name, memberUsernames = memberUsernames)
        groupDao.insertGroupWithMembers(
            group = group.toEntity(),
            members =
                group.memberUsernames.map { username ->
                    GroupMemberEntity(groupId = group.id, username = username)
                },
        )
        return group
    }

    override fun observeGroups(): Flow<List<Group>> =
        groupDao.observeGroupsWithMembers()
            .map { groups -> groups.map(GroupWithMembers::toDomain) }

    override suspend fun getGroup(groupId: String): Group? =
        groupDao.getGroupWithMembers(groupId)?.toDomain()

    override suspend fun clear() = groupDao.deleteAll()
}

private fun Group.toEntity() =
    GroupEntity(
        id = id,
        name = name,
    )

private fun GroupWithMembers.toDomain() =
    Group(
        id = group.id,
        name = group.name,
        memberUsernames = members.map { it.username },
    )

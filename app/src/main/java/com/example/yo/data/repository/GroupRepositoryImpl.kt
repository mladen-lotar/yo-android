package com.example.yo.data.repository

import com.example.yo.data.local.GroupDao
import com.example.yo.data.local.GroupEntity
import com.example.yo.data.local.GroupMemberEntity
import com.example.yo.data.local.GroupWithMembers
import com.example.yo.domain.model.Group
import com.example.yo.domain.repository.GroupRepository
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

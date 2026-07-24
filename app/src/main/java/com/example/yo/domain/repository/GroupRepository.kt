package com.example.yo.domain.repository

import com.example.yo.domain.model.Group
import kotlinx.coroutines.flow.Flow

interface GroupRepository {
    suspend fun createGroup(name: String, memberUsernames: List<String>): Group

    fun observeGroups(): Flow<List<Group>>

    suspend fun getGroup(groupId: String): Group?
}

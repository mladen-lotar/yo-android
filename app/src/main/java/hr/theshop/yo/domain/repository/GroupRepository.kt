package hr.theshop.yo.domain.repository

import hr.theshop.yo.domain.model.Group
import kotlinx.coroutines.flow.Flow

interface GroupRepository {
    suspend fun createGroup(name: String, memberUsernames: List<String>): Group

    fun observeGroups(): Flow<List<Group>>

    suspend fun getGroup(groupId: String): Group?

    /** Forgets every stored group. Used when an account is deleted. */
    suspend fun clear()
}

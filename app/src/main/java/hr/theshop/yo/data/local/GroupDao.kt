package hr.theshop.yo.data.local

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface GroupDao {
    @Insert
    suspend fun insertGroup(entity: GroupEntity)

    @Insert
    suspend fun insertMembers(members: List<GroupMemberEntity>)

    @Transaction
    suspend fun insertGroupWithMembers(
        group: GroupEntity,
        members: List<GroupMemberEntity>,
    ) {
        insertGroup(group)
        insertMembers(members)
    }

    @Transaction
    @Query("SELECT * FROM groups ORDER BY name ASC")
    fun observeGroupsWithMembers(): Flow<List<GroupWithMembers>>

    @Transaction
    @Query("SELECT * FROM groups WHERE id = :groupId")
    suspend fun getGroupWithMembers(groupId: String): GroupWithMembers?
}

data class GroupWithMembers(
    @Embedded val group: GroupEntity,
    @Relation(parentColumn = "id", entityColumn = "groupId")
    val members: List<GroupMemberEntity>,
)

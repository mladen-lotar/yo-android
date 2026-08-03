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

    /**
     * Scoped to [account]. [YoDatabase.migration3To4] never leaves a group with a NULL owner - it
     * either stamps every pre-upgrade group to the account that wrote it or deletes it - so this
     * filter has no NULL case to worry about at read time.
     */
    @Transaction
    @Query("SELECT * FROM groups WHERE ownerAccount = :account ORDER BY name ASC")
    fun observeGroupsWithMembers(account: String): Flow<List<GroupWithMembers>>

    @Transaction
    @Query("SELECT * FROM groups WHERE id = :groupId AND ownerAccount = :account")
    suspend fun getGroupWithMembers(groupId: String, account: String): GroupWithMembers?

    // group_members is declared ON DELETE CASCADE, and Room enables foreign key enforcement,
    // so removing the groups takes their membership rows with them.
    /** Forgets every group owned by [account]. Used when that account logs out or is deleted. */
    @Query("DELETE FROM groups WHERE ownerAccount = :account")
    suspend fun deleteForOwner(account: String)
}

data class GroupWithMembers(
    @Embedded val group: GroupEntity,
    @Relation(parentColumn = "id", entityColumn = "groupId")
    val members: List<GroupMemberEntity>,
)

package hr.theshop.yo.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "groups")
data class GroupEntity(
    @PrimaryKey val id: String,
    val name: String,
    /**
     * The account this group belongs to. See [hr.theshop.yo.data.local.YoEntity.ownerAccount] for
     * why the migration itself can never leave this NULL on a pre-upgrade row - the reasoning
     * applies identically here. (`GroupRepositoryImpl.createGroup` can still pass `null` for a
     * brand-new group created with no session; that is unrelated to the migration.)
     */
    val ownerAccount: String? = null,
)

package hr.theshop.yo.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface YoDao {
    @Insert
    suspend fun insert(entity: YoEntity)

    /**
     * Scoped to [account]. [YoDatabase.migration3To4] never leaves a row with a NULL owner - it
     * either stamps every pre-upgrade row to the account that wrote it or deletes it - so this
     * filter has no NULL case to worry about at read time.
     */
    @Query("SELECT * FROM yo_messages WHERE ownerAccount = :account ORDER BY timestamp DESC")
    fun observeAll(account: String): Flow<List<YoEntity>>

    /** Records the verdict on a row that was written before the send was attempted. */
    @Query("UPDATE yo_messages SET delivered = :delivered WHERE id = :id")
    suspend fun markDelivered(id: String, delivered: Boolean)

    /** Forgets every stored Yo owned by [account]. Used when that account logs out or is deleted. */
    @Query("DELETE FROM yo_messages WHERE ownerAccount = :account")
    suspend fun deleteForOwner(account: String)
}

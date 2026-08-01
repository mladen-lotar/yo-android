package hr.theshop.yo.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface YoDao {
    @Insert
    suspend fun insert(entity: YoEntity)

    @Query("SELECT * FROM yo_messages ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<YoEntity>>

    /** Records the verdict on a row that was written before the send was attempted. */
    @Query("UPDATE yo_messages SET delivered = :delivered WHERE id = :id")
    suspend fun markDelivered(id: String, delivered: Boolean)

    @Query("DELETE FROM yo_messages")
    suspend fun deleteAll()
}

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

    @Query("DELETE FROM yo_messages")
    suspend fun deleteAll()
}

package hr.theshop.yo.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "yo_messages")
data class YoEntity(
    @PrimaryKey val id: String,
    val sender: String,
    val recipient: String,
    val timestamp: Long,
    val link: String?,
    val hashtag: String?,
    val latitude: Double?,
    val longitude: Double?,
    val photoUri: String?,
)

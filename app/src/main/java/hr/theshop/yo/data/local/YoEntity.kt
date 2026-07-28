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
    // Vestigial: photo attachment was removed, but the column stays because dropping it bumps the
    // Room schema, and this database has no migrations and no destructive fallback - so a bump
    // would crash on first open for every install that already exists. It is always null now, and
    // goes when there is a real reason to write a Migration.
    val photoUri: String? = null,
)

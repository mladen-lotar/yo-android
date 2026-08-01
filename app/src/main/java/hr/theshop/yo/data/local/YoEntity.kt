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
    /**
     * Whether this Yo was confirmed delivered. NULL means "written before this column existed",
     * which is genuinely unknown rather than false - see the migration in [YoDatabase].
     *
     * G25 fixed the TRANSIENT surface: the band says COULDN'T YO and then the moment passes. The
     * Room row it wrote is PERMANENT, carried no delivery state, and rendered exactly like a Yo
     * that arrived - so the surface that lasts was the one still lying, and it outlived the one
     * telling the truth.
     */
    val delivered: Boolean? = null,
)

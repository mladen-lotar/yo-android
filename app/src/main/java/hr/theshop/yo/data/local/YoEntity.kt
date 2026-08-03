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
    /**
     * The account this row is scoped to, so signing out and back in keeps a device's own history
     * instead of destroying it while never showing it to anyone else who signs in.
     *
     * NULL is only ever transient, inside `YoDatabase.migration3To4` itself: `ADD COLUMN` has no
     * way to create the column pre-populated, so every pre-upgrade row passes through NULL for
     * the instant before that same migration either stamps it to the account that wrote it or
     * deletes it. No row this app ever reads carries a NULL owner - every insert this app makes
     * sets it directly (see `YoRepositoryImpl.toEntity`), and the migration leaves none behind.
     * The type stays nullable only because the column itself is nullable at the schema level.
     */
    val ownerAccount: String? = null,
)

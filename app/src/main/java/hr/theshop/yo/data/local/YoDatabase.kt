package hr.theshop.yo.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [YoEntity::class, GroupEntity::class, GroupMemberEntity::class],
    version = 4,
)
abstract class YoDatabase : RoomDatabase() {
    abstract fun yoDao(): YoDao

    abstract fun groupDao(): GroupDao

    companion object {
        /**
         * Adds [YoEntity.delivered] so history can stop rendering a failed send as a delivered one.
         *
         * This is the database's FIRST migration. Until now it had none and no
         * `fallbackToDestructiveMigration`, which is why a dead `photoUri` column was kept rather
         * than dropped (G24) - any version bump would have thrown on first open for every install
         * that already existed. That constraint is now paid off rather than worked around.
         *
         * `ADD COLUMN` specifically, and that is not an accident. minSdk 24 means the platform
         * SQLite can be 3.9, and `ALTER TABLE ... DROP COLUMN` did not arrive until 3.35 (2021) -
         * so dropping a column here would need a full table rebuild, and a wrong
         * `INSERT ... SELECT` SUCCEEDS while silently copying values into the wrong column names.
         * `ADD COLUMN` has no such failure mode: it is available on every supported device and it
         * cannot corrupt an existing row.
         *
         * The new column is nullable and every existing row keeps NULL, which is the honest value.
         * Those Yos were written before anything recorded delivery, so their state is unknown -
         * defaulting them to "delivered" would re-tell exactly the lie this removes, and defaulting
         * them to "failed" would invent one in the other direction.
         *
         * `photoUri` is deliberately still here. Removing it is the table rebuild described above,
         * and the PRD's own note applies: the rebuild SQL is identical whenever it runs, so nothing
         * is gained by folding a riskier operation into the migration that finally makes the schema
         * movable.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE yo_messages ADD COLUMN delivered INTEGER")
            }
        }

        /**
         * Adds `ownerAccount` to [YoEntity] and [GroupEntity] so history and groups can be scoped
         * to the signed-in account instead of being destroyed on logout.
         *
         * Logout used to wipe both tables outright, because with no owner column the alternative
         * was showing the next account on this handset the previous one's recipients, links and
         * GPS pins. That fixed the leak at the cost of the user's own groups every sign-out. A
         * nullable owner column removes that cost: rows are filtered by account, not destroyed.
         *
         * `ADD COLUMN` again, for the same reason as [MIGRATION_2_3]: minSdk 24 can mean SQLite
         * 3.9, `DROP COLUMN`/table rebuilds are not an option, and this database still has no
         * `fallbackToDestructiveMigration`.
         *
         * A bare `Migration` cannot read the session, so it cannot know on its own which account
         * an existing row belongs to. [ownerAtUpgrade] is how the caller (`AppModule`, which reads
         * `SessionStore.current()?.username` when it builds this database) supplies that answer:
         * whoever was signed in when the database last closed is the account that WROTE these
         * rows, because nothing else could have written them.
         *
         * The two outcomes:
         * - [ownerAtUpgrade] non-null: every pre-upgrade row is stamped to it. This is the account
         *   that wrote the rows, not a guess.
         * - [ownerAtUpgrade] null (nobody was signed in when the app last closed - the common case
         *   is a session that lapsed while the app sat closed for the full 90-day token TTL):
         *   there is no evidence of who these rows belong to, so they are deleted rather than left
         *   adoptable. Guessing here - by stamping them to whichever account happens to sign in
         *   next - is exactly the bug this migration exists to fix: that account did not write
         *   them, so it has no more claim to them than any other, and a phone with app data intact
         *   but no active session is precisely the state that follows a lapsed session, which is
         *   also precisely the state that follows an account switch. Deleting is the only option
         *   that is neither a leak (adopting) nor a lie (inventing an owner); leaving them on disk
         *   unclaimed is exactly the residue this whole change removes.
         *
         * No index is added on `ownerAccount`: this is a single user's on-device history and group
         * list, not a shared table, so a table scan filtered by account costs nothing that matters
         * here - and an index Room doesn't know to validate against is exactly the kind of thing
         * that turns a hand-built migration-test fixture into one that silently isn't a real
         * upgrade (see the migration test's own comment on indices).
         */
        fun migration3To4(ownerAtUpgrade: String?): Migration =
            object : Migration(3, 4) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE yo_messages ADD COLUMN ownerAccount TEXT")
                    db.execSQL("ALTER TABLE groups ADD COLUMN ownerAccount TEXT")
                    if (ownerAtUpgrade != null) {
                        db.execSQL(
                            "UPDATE yo_messages SET ownerAccount = ? WHERE ownerAccount IS NULL",
                            arrayOf<Any?>(ownerAtUpgrade),
                        )
                        db.execSQL(
                            "UPDATE groups SET ownerAccount = ? WHERE ownerAccount IS NULL",
                            arrayOf<Any?>(ownerAtUpgrade),
                        )
                    } else {
                        db.execSQL("DELETE FROM yo_messages WHERE ownerAccount IS NULL")
                        db.execSQL("DELETE FROM groups WHERE ownerAccount IS NULL")
                    }
                }
            }
    }
}

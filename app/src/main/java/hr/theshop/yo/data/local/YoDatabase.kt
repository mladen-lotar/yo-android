package hr.theshop.yo.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [YoEntity::class, GroupEntity::class, GroupMemberEntity::class],
    version = 3,
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
    }
}

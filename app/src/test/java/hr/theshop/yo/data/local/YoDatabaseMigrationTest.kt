package hr.theshop.yo.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * That an existing database survives the upgrade.
 *
 * This database shipped at version 2 with **no migrations and no `fallbackToDestructiveMigration`**,
 * which is precisely why a dead `photoUri` column was kept rather than dropped (G24): any version
 * bump would have thrown on first open for every install that already existed. G29 is the change
 * that finally has to move it, so the thing to prove is not that the new column exists - the
 * schema export already says that - but that a **real version-2 file opens and keeps its rows**.
 *
 * Built by hand from the exported `2.json` rather than with `MigrationTestHelper`, which would mean
 * adding `androidx.room:room-testing` for one test. The `room_master_table` identity hash is the
 * load-bearing part: without the exact v2 hash Room reports a schema mismatch instead of running
 * the migration, and the test would pass for the wrong reason.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class YoDatabaseMigrationTest {

    private lateinit var context: Context
    private lateinit var file: File

    /** Verbatim from `app/schemas/hr.theshop.yo.data.local.YoDatabase/2.json`. */
    private val v2Identity = "1536e7be5fe233a3141085fe9c550969"
    private val v2Tables = listOf(
        "CREATE TABLE IF NOT EXISTS `yo_messages` (`id` TEXT NOT NULL, `sender` TEXT NOT NULL, " +
            "`recipient` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, `link` TEXT, " +
            "`hashtag` TEXT, `latitude` REAL, `longitude` REAL, `photoUri` TEXT, PRIMARY KEY(`id`))",
        "CREATE TABLE IF NOT EXISTS `groups` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, " +
            "PRIMARY KEY(`id`))",
        "CREATE TABLE IF NOT EXISTS `group_members` (`groupId` TEXT NOT NULL, " +
            "`username` TEXT NOT NULL, PRIMARY KEY(`groupId`, `username`), " +
            "FOREIGN KEY(`groupId`) REFERENCES `groups`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        // Room validates INDICES as well as columns, so omitting this produced
        // "Migration didn't properly handle: group_members" - a failure that looks like a broken
        // migration and is actually a v2 fixture that was never a real v2.
        "CREATE INDEX IF NOT EXISTS `index_group_members_groupId` ON `group_members` (`groupId`)",
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        file = context.getDatabasePath("migration-test.db")
        file.parentFile?.mkdirs()
        file.delete()
    }

    @After
    fun tearDown() {
        file.delete()
    }

    /** A genuine version-2 database, with a Yo already in it. */
    private fun writeVersion2Database() {
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        v2Tables.forEach(db::execSQL)
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS room_master_table " +
                "(id INTEGER PRIMARY KEY, identity_hash TEXT)",
        )
        db.execSQL(
            "INSERT OR REPLACE INTO room_master_table (id, identity_hash) VALUES (42, ?)",
            arrayOf(v2Identity),
        )
        db.execSQL(
            "INSERT INTO yo_messages (id, sender, recipient, timestamp, link, hashtag, " +
                "latitude, longitude, photoUri) VALUES ('old-1','ME','ADA',1000,'https://x.test'," +
                "'worldcup',NULL,NULL,NULL)",
        )
        db.version = 2
        db.close()
    }

    private fun openWithRoom(): YoDatabase =
        Room.databaseBuilder(context, YoDatabase::class.java, file.absolutePath)
            .addMigrations(YoDatabase.MIGRATION_2_3)
            .allowMainThreadQueries()
            .build()

    @Test
    fun `a version 2 database opens at version 3 without losing anything`() = runBlocking {
        writeVersion2Database()

        val database = openWithRoom()
        val history = database.yoDao().observeAll().first()
        database.close()

        assertEquals("the existing Yo must survive the upgrade", 1, history.size)
        val row = history.single()
        assertEquals("old-1", row.id)
        assertEquals("ADA", row.recipient)
        assertEquals("https://x.test", row.link)
        assertEquals("worldcup", row.hashtag)
    }

    @Test
    fun `a row written before the column existed reports UNKNOWN, not delivered`() = runBlocking {
        writeVersion2Database()

        val database = openWithRoom()
        val row = database.yoDao().observeAll().first().single()
        database.close()

        assertNull(
            "an old row's delivery state is genuinely unknown - defaulting it to delivered " +
                "would re-tell the exact lie G29 removes, and to failed would invent a new one",
            row.delivered,
        )
    }

    @Test
    fun `the upgraded database still accepts writes and records a verdict`() = runBlocking {
        writeVersion2Database()

        val database = openWithRoom()
        val dao = database.yoDao()
        dao.insert(
            YoEntity(
                id = "new-1",
                sender = "ME",
                recipient = "LEO",
                timestamp = 2000,
                link = null,
                hashtag = null,
                latitude = null,
                longitude = null,
            ),
        )
        dao.markDelivered("new-1", false)
        val rows = dao.observeAll().first().associateBy { it.id }
        database.close()

        assertEquals(2, rows.size)
        assertEquals(false, rows.getValue("new-1").delivered)
        assertNull("the migrated row is untouched by a later write", rows.getValue("old-1").delivered)
    }

    @Test
    fun `opening a version 2 file without the migration would have thrown`() {
        // The counterfactual, so the migration is not merely present but load-bearing. Without it
        // Room raises IllegalStateException ("A migration from 2 to 3 was required but not found"),
        // which on a shipped app is a crash loop on first launch after update - recoverable only
        // by uninstalling, which is what "no migrations and no destructive fallback" cost.
        writeVersion2Database()

        val database =
            Room.databaseBuilder(context, YoDatabase::class.java, file.absolutePath)
                .allowMainThreadQueries()
                .build()

        val thrown =
            try {
                database.yoDao().observeAll()
                database.openHelper.writableDatabase
                null
            } catch (e: Throwable) {
                e
            } finally {
                runCatching { database.close() }
            }

        assertTrue(
            "expected a missing-migration failure, got: $thrown",
            thrown is IllegalStateException,
        )
    }
}

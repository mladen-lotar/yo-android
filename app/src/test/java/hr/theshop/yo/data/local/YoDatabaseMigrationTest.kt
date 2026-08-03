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

    private fun openWithRoom(ownerAtUpgrade: String? = null): YoDatabase =
        Room.databaseBuilder(context, YoDatabase::class.java, file.absolutePath)
            .addMigrations(
                YoDatabase.MIGRATION_2_3,
                YoDatabase.migration3To4(ownerAtUpgrade),
            )
            .allowMainThreadQueries()
            .build()

    /** Verbatim from `app/schemas/hr.theshop.yo.data.local.YoDatabase/3.json`. */
    private val v3Identity = "3364a68127207d8e8b3751cf7d7b8f55"
    private val v3Tables = listOf(
        "CREATE TABLE IF NOT EXISTS `yo_messages` (`id` TEXT NOT NULL, `sender` TEXT NOT NULL, " +
            "`recipient` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, `link` TEXT, " +
            "`hashtag` TEXT, `latitude` REAL, `longitude` REAL, `photoUri` TEXT, " +
            "`delivered` INTEGER, PRIMARY KEY(`id`))",
        "CREATE TABLE IF NOT EXISTS `groups` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, " +
            "PRIMARY KEY(`id`))",
        "CREATE TABLE IF NOT EXISTS `group_members` (`groupId` TEXT NOT NULL, " +
            "`username` TEXT NOT NULL, PRIMARY KEY(`groupId`, `username`), " +
            "FOREIGN KEY(`groupId`) REFERENCES `groups`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE INDEX IF NOT EXISTS `index_group_members_groupId` ON `group_members` (`groupId`)",
    )

    /** A genuine version-3 database (post G29, pre owner-scoping), with a Yo already in it. */
    private fun writeVersion3Database() {
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        v3Tables.forEach(db::execSQL)
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS room_master_table " +
                "(id INTEGER PRIMARY KEY, identity_hash TEXT)",
        )
        db.execSQL(
            "INSERT OR REPLACE INTO room_master_table (id, identity_hash) VALUES (42, ?)",
            arrayOf(v3Identity),
        )
        db.execSQL(
            "INSERT INTO yo_messages (id, sender, recipient, timestamp, link, hashtag, " +
                "latitude, longitude, photoUri, delivered) VALUES ('old-1','ME','ADA',1000," +
                "'https://x.test','worldcup',NULL,NULL,NULL,NULL)",
        )
        db.version = 3
        db.close()
    }

    private fun openWithMigration34(ownerAtUpgrade: String? = null): YoDatabase =
        Room.databaseBuilder(context, YoDatabase::class.java, file.absolutePath)
            .addMigrations(YoDatabase.migration3To4(ownerAtUpgrade))
            .allowMainThreadQueries()
            .build()

    @Test
    fun `a version 2 database opens at version 4 without losing anything`() = runBlocking {
        writeVersion2Database()

        // "ME" was signed in when the version-2 database last closed, so the 3-to-4 leg of this
        // chain stamps old-1 to "ME" directly - see the owner-scoping tests below for the
        // isolation this buys.
        val database = openWithRoom(ownerAtUpgrade = "ME")
        val dao = database.yoDao()
        val history = dao.observeAll("ME").first()
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

        val database = openWithRoom(ownerAtUpgrade = "ME")
        val dao = database.yoDao()
        val row = dao.observeAll("ME").first().single()
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

        val database = openWithRoom(ownerAtUpgrade = "ME")
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
                ownerAccount = "ME",
            ),
        )
        dao.markDelivered("new-1", false)
        val rows = dao.observeAll("ME").first().associateBy { it.id }
        database.close()

        assertEquals(2, rows.size)
        assertEquals(false, rows.getValue("new-1").delivered)
        assertNull("the migrated row is untouched by a later write", rows.getValue("old-1").delivered)
    }

    /**
     * RED-FIRST: pins the correct invariant - "the account that WROTE the rows owns them" - not
     * the old, wrong one this replaces ("the first account to read after the upgrade owns them").
     * `writeVersion3Database` stands in for "alice" being the account that wrote `old-1` before
     * her session lapsed while the app was closed; the migration runs with her still recorded as
     * signed in (AppModule reads SessionStore at construction time, before anyone new signs in),
     * so `old-1` must be stamped to her - and once stamped, a different account signing in
     * afterwards must never see it.
     */
    @Test
    fun `migrating with a signed-in account stamps existing rows to that account, and a different account signing in later cannot see them`() =
        runBlocking {
            writeVersion3Database()

            val database = openWithMigration34(ownerAtUpgrade = "alice")
            val dao = database.yoDao()
            val aliceSees = dao.observeAll("alice").first().map { it.id }
            val bobSees = dao.observeAll("bob").first().map { it.id }
            database.close()

            assertEquals(
                "the pre-upgrade row must be stamped to the account that was signed in when it " +
                    "was written, not to whoever happens to read next",
                listOf("old-1"),
                aliceSees,
            )
            assertTrue(
                "a second, different account must never see a row it did not write: saw $bobSees",
                bobSees.isEmpty(),
            )
        }

    @Test
    fun `opening a version 2 file without any migration would have thrown`() {
        // The counterfactual, so the migration chain is not merely present but load-bearing.
        // Without it Room raises IllegalStateException ("A migration from 2 to 4 was required but
        // not found"), which on a shipped app is a crash loop on first launch after update -
        // recoverable only by uninstalling, which is what "no migrations and no destructive
        // fallback" cost.
        writeVersion2Database()

        val database =
            Room.databaseBuilder(context, YoDatabase::class.java, file.absolutePath)
                .allowMainThreadQueries()
                .build()

        val thrown =
            try {
                database.yoDao().observeAll("anyone")
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

    /**
     * The other half of the invariant: with nobody signed in at upgrade time (the session lapsed
     * while the app sat closed), there is no evidence of who `old-1` belongs to, so it must be
     * removed rather than left as a NULL row some later account could adopt - adopting it is the
     * exact bug this migration exists to fix, and inventing an owner would just be a different lie.
     */
    @Test
    fun `migrating with no session removes the unattributable rows rather than leaving them adoptable`() =
        runBlocking {
            writeVersion3Database()

            val database = openWithMigration34(ownerAtUpgrade = null)
            val cursor =
                database.openHelper.readableDatabase.query(
                    "SELECT id FROM yo_messages WHERE id = 'old-1'",
                )
            val found = cursor.moveToFirst()
            cursor.close()
            database.close()

            assertTrue("an unattributable pre-upgrade row must be deleted, not left NULL", !found)
        }

    /**
     * Rows written after the upgrade are owned by whoever wrote them, independent of who (if
     * anyone) happened to be signed in when the migration itself ran.
     */
    @Test
    fun `a row written after the upgrade is owned by the account that wrote it, not whoever reads next`() =
        runBlocking {
            writeVersion3Database()

            // "alice" is signed in at migration time, but never writes anything herself here -
            // "carol" is the one who writes a new row afterwards.
            val database = openWithMigration34(ownerAtUpgrade = "alice")
            val dao = database.yoDao()
            dao.insert(
                YoEntity(
                    id = "carol-1",
                    sender = "carol",
                    recipient = "z",
                    timestamp = 9_000,
                    link = null,
                    hashtag = null,
                    latitude = null,
                    longitude = null,
                    ownerAccount = "carol",
                ),
            )

            val carolSees = dao.observeAll("carol").first().map { it.id }
            val aliceSees = dao.observeAll("alice").first().map { it.id }
            database.close()

            assertEquals(
                "carol must see only the row she wrote herself",
                listOf("carol-1"),
                carolSees,
            )
            assertEquals(
                "alice must see only old-1, which the migration stamped to her - never carol's " +
                    "row, which she neither wrote nor read first",
                listOf("old-1"),
                aliceSees,
            )
        }

    @Test
    fun `two accounts on one device never see each other's rows`() = runBlocking {
        writeVersion3Database()

        val database = openWithMigration34(ownerAtUpgrade = null)
        val dao = database.yoDao()
        dao.insert(
            YoEntity(
                id = "alice-1",
                sender = "alice",
                recipient = "x",
                timestamp = 5_000,
                link = null,
                hashtag = null,
                latitude = null,
                longitude = null,
                ownerAccount = "alice",
            ),
        )
        dao.insert(
            YoEntity(
                id = "bob-1",
                sender = "bob",
                recipient = "y",
                timestamp = 6_000,
                link = null,
                hashtag = null,
                latitude = null,
                longitude = null,
                ownerAccount = "bob",
            ),
        )

        val aliceHistory = dao.observeAll("alice").first().map { it.id }
        val bobHistory = dao.observeAll("bob").first().map { it.id }
        database.close()

        assertEquals(listOf("alice-1"), aliceHistory)
        assertEquals(listOf("bob-1"), bobHistory)
    }

    @Test
    fun `clearing one account's data leaves the other's intact`() = runBlocking {
        writeVersion3Database()

        val database = openWithMigration34(ownerAtUpgrade = null)
        val dao = database.yoDao()
        dao.insert(
            YoEntity(
                id = "alice-1",
                sender = "alice",
                recipient = "x",
                timestamp = 5_000,
                link = null,
                hashtag = null,
                latitude = null,
                longitude = null,
                ownerAccount = "alice",
            ),
        )
        dao.insert(
            YoEntity(
                id = "bob-1",
                sender = "bob",
                recipient = "y",
                timestamp = 6_000,
                link = null,
                hashtag = null,
                latitude = null,
                longitude = null,
                ownerAccount = "bob",
            ),
        )

        dao.deleteForOwner("alice")
        val aliceHistory = dao.observeAll("alice").first()
        val bobHistory = dao.observeAll("bob").first().map { it.id }
        database.close()

        assertTrue("the departing account's rows must be gone", aliceHistory.isEmpty())
        assertEquals("the other account's rows must be untouched", listOf("bob-1"), bobHistory)
    }

    @Test
    fun `opening a version 3 file without migration3To4 would have thrown`() {
        // The counterfactual for the migration this wave adds: proves it is load-bearing, not
        // merely present, the same way the v2-to-v4 counterfactual above does for MIGRATION_2_3.
        writeVersion3Database()

        val database =
            Room.databaseBuilder(context, YoDatabase::class.java, file.absolutePath)
                .allowMainThreadQueries()
                .build()

        val thrown =
            try {
                database.yoDao().observeAll("anyone")
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

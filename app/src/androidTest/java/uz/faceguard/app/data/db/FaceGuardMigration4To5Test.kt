package uz.faceguard.app.data.db

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real instrumented test for [MIGRATION_4_5], which account-scopes the activity
 * log.
 *
 * Builds a genuine v4 database with Room's generated DDL, writes rows (including
 * an unscoped legacy event), then opens the v5 database through Room so the
 * migration runs *and* Room validates the resulting schema against the v5
 * entities.
 */
@RunWith(AndroidJUnit4::class)
class FaceGuardMigration4To5Test {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private var room: FaceGuardDatabase? = null

    @Before
    fun setUp() {
        context.deleteDatabase(DB_NAME)
    }

    @After
    fun tearDown() {
        room?.close()
        context.deleteDatabase(DB_NAME)
    }

    /** DDL Room generated for schema v4 (6 tables + 4 indices). */
    private fun createV4Database(accountCount: Int = 1, legacyEvent: Boolean = true) {
        val db = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(DB_NAME), null)
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `user_accounts` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`fullName` TEXT NOT NULL, `phoneNumber` TEXT NOT NULL, `pinHash` TEXT NOT NULL, " +
                "`pinSalt` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)",
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_user_accounts_phoneNumber` " +
                "ON `user_accounts` (`phoneNumber`)",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `parent_profiles` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`accountId` INTEGER NOT NULL, `displayName` TEXT NOT NULL, `isFaceEnrolled` INTEGER NOT NULL, " +
                "`faceTemplateRef` TEXT, `enrollmentStatus` TEXT NOT NULL, `enrollmentVersion` INTEGER NOT NULL, " +
                "`lastEnrollmentAt` INTEGER, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL)",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `child_profiles` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`accountId` INTEGER NOT NULL, `childName` TEXT NOT NULL, `isFaceEnrolled` INTEGER NOT NULL, " +
                "`faceTemplateRef` TEXT, `restrictionLevel` TEXT NOT NULL, `enrollmentStatus` TEXT NOT NULL, " +
                "`enrollmentVersion` INTEGER NOT NULL, `lastEnrollmentAt` INTEGER, " +
                "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_child_profiles_accountId` ON `child_profiles` (`accountId`)",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `protected_apps` (`packageName` TEXT NOT NULL, " +
                "`appDisplayName` TEXT NOT NULL, `isProtected` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`packageName`))",
        )
        // v4 shape: no accountId yet.
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `activity_events` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`type` TEXT NOT NULL, `detail` TEXT, `at` INTEGER NOT NULL)",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_activity_events_at` ON `activity_events` (`at`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `child_app_policies` (`accountId` INTEGER NOT NULL, " +
                "`childId` INTEGER NOT NULL, `packageName` TEXT NOT NULL, `mode` TEXT NOT NULL, " +
                "`action` TEXT NOT NULL, `dailyLimitMinutes` INTEGER, `activationDelayMs` INTEGER NOT NULL, " +
                "`recoveryDelayMs` INTEGER NOT NULL, `enabled` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL, PRIMARY KEY(`accountId`, `childId`, `packageName`))",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_child_app_policies_accountId_childId` " +
                "ON `child_app_policies` (`accountId`, `childId`)",
        )

        for (accountId in 1..accountCount) {
            db.execSQL(
                "INSERT INTO user_accounts (id, fullName, phoneNumber, pinHash, pinSalt, createdAt) " +
                    "VALUES ($accountId, 'Parent $accountId', '9989012345$accountId', 'hash-$accountId', " +
                    "'salt-$accountId', 1000)",
            )
        }
        db.execSQL(
            "INSERT INTO child_profiles (id, accountId, childName, isFaceEnrolled, faceTemplateRef, " +
                "restrictionLevel, enrollmentStatus, enrollmentVersion, lastEnrollmentAt, createdAt, updatedAt) " +
                "VALUES (5, 1, 'Vali', 1, 'template-child', 'MEDIUM', 'ENROLLED', 1, 1200, 1000, 1200)",
        )
        // Legacy, account-unscoped events that must not be shown to any account.
        if (legacyEvent) {
            db.execSQL(
                "INSERT INTO activity_events (id, type, detail, at) VALUES (1, 'CHILD_BLOCKED', 'Vali', 1400)",
            )
        }

        db.version = 4 // PRAGMA user_version -> Room runs the 4 -> 5 migration
        db.close()
    }

    private fun openV5(): FaceGuardDatabase =
        Room.databaseBuilder(context, FaceGuardDatabase::class.java, DB_NAME)
            .addMigrations(MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9)
            .allowMainThreadQueries()
            .build()
            .also {
                room = it
                it.openHelper.writableDatabase // triggers migration + schema validation
            }

    @Test
    fun migration4To5_keepsExistingAccountsAndChildren() = runBlocking {
        createV4Database()
        val db = openV5()

        val account = db.userAccountDao().getById(1L)
        assertNotNull("account must survive the migration", account)
        assertEquals("Parent 1", account!!.fullName)

        val children = db.childProfileDao().observeAll(1L).first()
        assertEquals(1, children.size)
        assertEquals("Vali", children.first().childName)
    }

    @Test
    fun migration4To5_backfillsLegacyEventsToTheSoleAccount() = runBlocking {
        createV4Database(accountCount = 1)
        val db = openV5()

        val events = db.activityEventDao().observeRecent(1L).first()
        assertEquals("the sole account owns the legacy event", 1, events.size)
        assertEquals("CHILD_BLOCKED", events.first().type)
        assertEquals("Vali", events.first().detail)
        assertEquals(1L, events.first().accountId)
        assertEquals(1L, events.first().id)
    }

    @Test
    fun migration4To5_dropsLegacyEventsWhenTheOwnerIsAmbiguous() = runBlocking {
        createV4Database(accountCount = 2)
        val db = openV5()

        assertTrue(
            "unowned events must not be shown to account 1",
            db.activityEventDao().observeRecent(1L).first().isEmpty(),
        )
        assertTrue(
            "unowned events must not be shown to account 2",
            db.activityEventDao().observeRecent(2L).first().isEmpty(),
        )
    }

    @Test
    fun migration4To5_activityLogIsUsableAndAccountScoped() = runBlocking {
        createV4Database(legacyEvent = false)
        val db = openV5()
        val dao = db.activityEventDao()

        dao.insert(ActivityEventEntity(accountId = 1L, type = "CHILD_BLOCKED", detail = "Vali", at = 2000L))
        dao.insert(ActivityEventEntity(accountId = 2L, type = "NO_FACE", at = 2100L))

        assertEquals(1, dao.observeRecent(1L).first().size)
        assertEquals("CHILD_BLOCKED", dao.observeRecent(1L).first().first().type)
        assertEquals(1, dao.observeRecent(2L).first().size)
    }

    @Test
    fun migration4To5_createsTheExpectedSchema() {
        createV4Database()
        val db = openV5()
        val raw = db.openHelper.readableDatabase

        raw.query("PRAGMA table_info(`activity_events`)").use { cursor ->
            val columns = mutableSetOf<String>()
            while (cursor.moveToNext()) {
                columns += cursor.getString(cursor.getColumnIndexOrThrow("name"))
            }
            assertTrue("missing accountId, found $columns", columns.contains("accountId"))
            assertTrue(
                "unexpected columns: $columns",
                columns.containsAll(listOf("id", "type", "detail", "at")),
            )
        }

        raw.query("PRAGMA index_list(`activity_events`)").use { cursor ->
            val indices = mutableSetOf<String>()
            while (cursor.moveToNext()) {
                indices += cursor.getString(cursor.getColumnIndexOrThrow("name"))
            }
            assertTrue("missing at index, found $indices", indices.contains("index_activity_events_at"))
            assertTrue(
                "missing account index, found $indices",
                indices.contains("index_activity_events_accountId_at"),
            )
        }
    }

    private companion object {
        const val DB_NAME = "migration-4-5-test.db"
    }
}

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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real instrumented test for the migrations starting at schema v3.
 *
 * `exportSchema = false`, so instead of Room schema assets this test builds a
 * genuine v3 database with the DDL Room generated for v3, writes real rows into
 * every legacy table, then opens the latest database through Room. Room then
 * runs every migration (3 -> 4 -> 5) *and* validates the resulting schema
 * against the current entities, so a wrong migration fails here rather than on
 * a user's device.
 */
@RunWith(AndroidJUnit4::class)
class FaceGuardMigrationTest {

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

    // ---------------------------------------------------------------- fixture

    /** DDL Room generated for schema v3 (5 tables + 3 indices). */
    private fun createV3Database() {
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
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `activity_events` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`type` TEXT NOT NULL, `detail` TEXT, `at` INTEGER NOT NULL)",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_activity_events_at` ON `activity_events` (`at`)")

        db.execSQL(
            "INSERT INTO user_accounts (id, fullName, phoneNumber, pinHash, pinSalt, createdAt) " +
                "VALUES (1, 'Ali Valiyev', '998901234567', 'hash-a', 'salt-a', 1000)",
        )
        db.execSQL(
            "INSERT INTO parent_profiles (id, accountId, displayName, isFaceEnrolled, faceTemplateRef, " +
                "enrollmentStatus, enrollmentVersion, lastEnrollmentAt, createdAt, updatedAt) " +
                "VALUES (1, 1, 'Ali', 1, 'template-parent', 'ENROLLED', 2, 1100, 1000, 1100)",
        )
        db.execSQL(
            "INSERT INTO child_profiles (id, accountId, childName, isFaceEnrolled, faceTemplateRef, " +
                "restrictionLevel, enrollmentStatus, enrollmentVersion, lastEnrollmentAt, createdAt, updatedAt) " +
                "VALUES (5, 1, 'Vali', 1, 'template-child', 'MEDIUM', 'ENROLLED', 1, 1200, 1000, 1200)",
        )
        db.execSQL(
            "INSERT INTO protected_apps (packageName, appDisplayName, isProtected, updatedAt) " +
                "VALUES ('com.example.youtube', 'YouTube', 1, 1300)",
        )
        db.execSQL(
            "INSERT INTO activity_events (id, type, detail, at) VALUES (1, 'CHILD_BLOCKED', 'Vali', 1400)",
        )

        db.version = 3 // PRAGMA user_version -> Room will run the 3 -> 4 migration
        db.close()
    }

    private fun openLatest(): FaceGuardDatabase =
        Room.databaseBuilder(context, FaceGuardDatabase::class.java, DB_NAME)
            .addMigrations(MIGRATION_3_4, MIGRATION_4_5)
            .allowMainThreadQueries()
            .build()
            .also {
                room = it
                it.openHelper.writableDatabase // triggers migration + schema validation
            }

    // ------------------------------------------------------------------ tests

    @Test
    fun migration3To4_keepsAccountsAndProfiles() = runBlocking {
        createV3Database()
        val db = openLatest()

        val account = db.userAccountDao().getById(1L)
        assertNotNull("user account must survive the migration", account)
        assertEquals("Ali Valiyev", account!!.fullName)
        assertEquals("998901234567", account.phoneNumber)

        val parent = db.parentProfileDao().get(1L)
        assertNotNull("parent profile must survive", parent)
        assertEquals("Ali", parent!!.displayName)
        assertEquals("template-parent", parent.faceTemplateRef)
        assertTrue(parent.isFaceEnrolled)

        val children = db.childProfileDao().observeAll(1L).first()
        assertEquals(1, children.size)
        assertEquals("Vali", children.first().childName)
    }

    @Test
    fun migration3To4_keepsProtectedAppsAndActivityLog() = runBlocking {
        createV3Database()
        val db = openLatest()

        assertEquals(1, db.protectedAppDao().countProtected())
        val apps = db.protectedAppDao().observeAll().first()
        assertEquals(1, apps.size)
        assertEquals("com.example.youtube", apps.first().packageName)
        assertTrue(apps.first().isProtected)

        // One account owns the legacy events, so MIGRATION_4_5 carries them into
        // the account-scoped log instead of dropping them.
        val events = db.activityEventDao().observeRecent(1L).first()
        assertEquals(1, events.size)
        assertEquals("CHILD_BLOCKED", events.first().type)
        assertEquals("Vali", events.first().detail)
        assertEquals(1L, events.first().accountId)
    }

    @Test
    fun migration3To4_createsAUsableChildAppPoliciesTable() = runBlocking {
        createV3Database()
        val db = openLatest()
        val dao = db.childAppPolicyDao()

        assertNull(dao.getPolicy(1L, 5L, "com.example.youtube"))
        dao.upsert(
            ChildAppPolicyEntity(
                accountId = 1L,
                childId = 5L,
                packageName = "com.example.youtube",
                mode = "LIMIT",
                action = "SOFT_BLOCK",
                dailyLimitMinutes = 30,
                activationDelayMs = 5_000L,
                recoveryDelayMs = 30_000L,
            ),
        )

        val stored = dao.getPolicy(1L, 5L, "com.example.youtube")
        assertNotNull("insert into the migrated table must work", stored)
        assertEquals("LIMIT", stored!!.mode)
        assertEquals(30, stored.dailyLimitMinutes)
    }

    @Test
    fun migration3To4_createsTheExpectedSchema() {
        createV3Database()
        val db = openLatest()
        val raw = db.openHelper.readableDatabase

        raw.query("PRAGMA table_info(`child_app_policies`)").use { cursor ->
            val columns = mutableSetOf<String>()
            while (cursor.moveToNext()) {
                columns += cursor.getString(cursor.getColumnIndexOrThrow("name"))
            }
            val expected = listOf(
                "accountId", "childId", "packageName", "mode", "action",
                "dailyLimitMinutes", "activationDelayMs", "recoveryDelayMs",
                "enabled", "createdAt", "updatedAt",
            )
            assertTrue("unexpected columns: $columns", columns.containsAll(expected))
        }

        raw.query("PRAGMA index_list(`child_app_policies`)").use { cursor ->
            val indices = mutableSetOf<String>()
            while (cursor.moveToNext()) {
                indices += cursor.getString(cursor.getColumnIndexOrThrow("name"))
            }
            assertTrue(
                "missing index, found $indices",
                indices.contains("index_child_app_policies_accountId_childId"),
            )
        }
    }

    @Test
    fun migration3To4_supportsCompositePrimaryKeyAfterUpgrade() = runBlocking {
        createV3Database()
        val db = openLatest()
        val dao = db.childAppPolicyDao()

        dao.upsert(policy(childId = 5L, packageName = "com.example.youtube", mode = "BLOCK"))
        dao.upsert(policy(childId = 6L, packageName = "com.example.youtube", mode = "ALLOW"))
        dao.upsert(policy(childId = 5L, packageName = "com.example.tiktok", mode = "ALLOW"))
        dao.upsert(policy(childId = 5L, packageName = "com.example.youtube", mode = "LIMIT"))

        assertEquals(2, dao.observePolicies(1L, 5L).first().size)
        assertEquals(1, dao.observePolicies(1L, 6L).first().size)
        assertEquals("LIMIT", dao.getPolicy(1L, 5L, "com.example.youtube")!!.mode)
        assertEquals("ALLOW", dao.getPolicy(1L, 6L, "com.example.youtube")!!.mode)
    }

    private fun policy(childId: Long, packageName: String, mode: String) = ChildAppPolicyEntity(
        accountId = 1L,
        childId = childId,
        packageName = packageName,
        mode = mode,
        action = "HARD_BLOCK",
    )

    private companion object {
        const val DB_NAME = "migration-3-4-test.db"
    }
}

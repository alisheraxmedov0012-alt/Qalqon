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
 * Phase 4 Step 1B-1: the real v6 -> v7 migration.
 *
 * Builds a genuine v6 database with the DDL Room generated for v6, writes a row
 * into every v6 table, then opens the latest database through Room. Room runs the
 * 6 -> 7 migration *and* validates the resulting schema against the current
 * entities, so a mismatch between [MIGRATION_6_7] and the entities fails here
 * instead of on a user device. The migration is additive: no existing row may be
 * lost and no table may be recreated destructively.
 */
@RunWith(AndroidJUnit4::class)
class FaceGuardMigration6To7Test {

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

    private fun createV6Database() {
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
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_child_profiles_accountId` ON `child_profiles` (`accountId`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `protected_apps` (`packageName` TEXT NOT NULL, " +
                "`appDisplayName` TEXT NOT NULL, `isProtected` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`packageName`))",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `activity_events` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`accountId` INTEGER NOT NULL, `type` TEXT NOT NULL, `detail` TEXT, `at` INTEGER NOT NULL)",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_activity_events_at` ON `activity_events` (`at`)")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_activity_events_accountId_at` ON `activity_events` (`accountId`, `at`)",
        )
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
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `parent_requests` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`accountId` INTEGER NOT NULL, `childId` INTEGER NOT NULL, `targetPackageName` TEXT NOT NULL, " +
                "`requestType` TEXT NOT NULL, `requestedDurationMinutes` INTEGER NOT NULL, " +
                "`approvedDurationMinutes` INTEGER, `status` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL, `expiresAt` INTEGER, `resolvedAt` INTEGER, " +
                "`resolutionReason` TEXT, `source` TEXT NOT NULL, `deduplicationKey` TEXT NOT NULL)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_parent_requests_accountId_createdAt` " +
                "ON `parent_requests` (`accountId`, `createdAt`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_parent_requests_accountId_status_createdAt` " +
                "ON `parent_requests` (`accountId`, `status`, `createdAt`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_parent_requests_accountId_childId_status` " +
                "ON `parent_requests` (`accountId`, `childId`, `status`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_parent_requests_accountId_deduplicationKey_status` " +
                "ON `parent_requests` (`accountId`, `deduplicationKey`, `status`)",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `notification_records` (`deduplicationKey` TEXT NOT NULL, " +
                "`accountId` INTEGER NOT NULL, `type` TEXT NOT NULL, `relatedRequestId` INTEGER, " +
                "`createdAt` INTEGER NOT NULL, `delivered` INTEGER NOT NULL, `deliveryAt` INTEGER, " +
                "PRIMARY KEY(`deduplicationKey`))",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_notification_records_accountId_createdAt` " +
                "ON `notification_records` (`accountId`, `createdAt`)",
        )

        db.execSQL(
            "INSERT INTO user_accounts (id, fullName, phoneNumber, pinHash, pinSalt, createdAt) " +
                "VALUES (1, 'Parent', '998901234567', 'hash', 'salt', 1000)",
        )
        db.execSQL(
            "INSERT INTO parent_profiles (id, accountId, displayName, isFaceEnrolled, faceTemplateRef, " +
                "enrollmentStatus, enrollmentVersion, lastEnrollmentAt, createdAt, updatedAt) " +
                "VALUES (1, 1, 'Parent', 1, 'enc-parent', 'ENROLLED', 1, 1200, 1000, 1200)",
        )
        db.execSQL(
            "INSERT INTO child_profiles (id, accountId, childName, isFaceEnrolled, faceTemplateRef, " +
                "restrictionLevel, enrollmentStatus, enrollmentVersion, lastEnrollmentAt, createdAt, updatedAt) " +
                "VALUES (10, 1, 'Vali', 1, 'enc-child', 'MEDIUM', 'ENROLLED', 1, 1200, 1000, 1200)",
        )
        db.execSQL(
            "INSERT INTO protected_apps (packageName, appDisplayName, isProtected, updatedAt) " +
                "VALUES ('com.google.android.youtube', 'YouTube', 1, 1000)",
        )
        db.execSQL(
            "INSERT INTO child_app_policies (accountId, childId, packageName, mode, action, dailyLimitMinutes, " +
                "activationDelayMs, recoveryDelayMs, enabled, createdAt, updatedAt) " +
                "VALUES (1, 10, 'com.google.android.youtube', 'LIMIT', 'SOFT_BLOCK', 30, 0, 0, 1, 1000, 1000)",
        )
        db.execSQL(
            "INSERT INTO activity_events (id, accountId, type, detail, at) " +
                "VALUES (1, 1, 'CHILD_BLOCKED', 'Vali', 1400)",
        )
        db.execSQL(
            "INSERT INTO parent_requests (id, accountId, childId, targetPackageName, requestType, " +
                "requestedDurationMinutes, approvedDurationMinutes, status, createdAt, updatedAt, expiresAt, " +
                "resolvedAt, resolutionReason, source, deduplicationKey) " +
                "VALUES (1, 1, 10, 'com.google.android.youtube', 'EXTRA_TIME', 15, NULL, 'PENDING', 1000, 1000, " +
                "NULL, NULL, NULL, 'CHILD_OVERLAY', 'EXTRA_TIME:1:10:com.google.android.youtube')",
        )
        db.execSQL(
            "INSERT INTO notification_records (deduplicationKey, accountId, type, relatedRequestId, createdAt, " +
                "delivered, deliveryAt) VALUES ('request:1', 1, 'PARENT_REQUEST_CREATED', 1, 1000, 1, 1000)",
        )

        db.version = 6
        db.close()
    }


    private fun openLatest(): FaceGuardDatabase =
        Room.databaseBuilder(context, FaceGuardDatabase::class.java, DB_NAME)
            .addMigrations(MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9)
            .allowMainThreadQueries()
            .build()
            .also {
                room = it
                it.openHelper.writableDatabase // triggers the migration + Room schema validation
            }

    @Test
    fun migration6To7_isAdditiveAndKeepsEveryExistingRow() = runBlocking {
        createV6Database()

        val db = openLatest()

        assertEquals("the database is opened at the latest version", 9, db.openHelper.writableDatabase.version)
        assertNotNull(db.userAccountDao().getById(1L))
        assertNotNull(db.parentProfileDao().get(1L))
        val children = db.childProfileDao().observeAll(1L).first()
        assertEquals(1, children.size)
        assertEquals("Vali", children.single().childName)
        assertEquals("enc-child", children.single().faceTemplateRef)
        assertEquals(1, db.protectedAppDao().countProtected())
        val policy = db.childAppPolicyDao().getPolicy(1L, 10L, "com.google.android.youtube")
        assertNotNull(policy)
        assertEquals("LIMIT", policy!!.mode)
        assertEquals(30, policy.dailyLimitMinutes)
        assertEquals(1, db.activityEventDao().observeRecent(1L).first().size)
        assertNotNull(db.parentRequestDao().byId(1L, 1L))
        assertNotNull(db.notificationRecordDao().byKey("request:1"))
    }

    @Test
    fun theNewScreenTimeTablesAreUsableAfterMigration() = runBlocking {
        createV6Database()
        val db = openLatest()

        db.dailyAppUsageDao().insertIfAbsent(
            DailyAppUsageEntity(
                accountId = 1L,
                childId = 10L,
                dateKey = "2026-09-24",
                packageName = "com.google.android.youtube",
                usedMs = 1_000L,
                category = "VIDEO",
                updatedAt = 2_000L,
            ),
        )
        val stored = db.dailyAppUsageDao().packageUsage(1L, 10L, "2026-09-24", "com.google.android.youtube")
        assertNotNull(stored)
        assertEquals(1_000L, stored!!.usedMs)
        assertEquals(1_000L, db.dailyAppUsageDao().totalUsedMs(1L, 10L, "2026-09-24"))
        // The atomic increment works on the migrated schema too.
        db.dailyAppUsageDao().incrementUsage(1L, 10L, "2026-09-24", "com.google.android.youtube", 500L, 3_000L)
        assertEquals(1_500L, db.dailyAppUsageDao().totalUsedMs(1L, 10L, "2026-09-24"))

        // TOTAL limits use category "" (SQLite treats NULL primary keys as distinct).
        db.childScreenTimeLimitDao().upsert(
            ChildScreenTimeLimitEntity(
                accountId = 1L,
                childId = 10L,
                scope = "TOTAL",
                category = "",
                limitMinutes = 120,
                updatedAt = 2_000L,
            ),
        )
        val total = db.childScreenTimeLimitDao().limit(1L, 10L, "TOTAL", "")
        assertNotNull(total)
        assertEquals(120, total!!.limitMinutes)
        assertEquals(1, db.childScreenTimeLimitDao().limits(1L, 10L).size)
        assertTrue(db.childScreenTimeLimitDao().limit(1L, 10L, "CATEGORY", "") == null)
    }

    @Test
    fun theMigratedSchemaHasTheExpectedKeysAndIndices() {
        createV6Database()
        val db = openLatest()

        assertTrue("daily_app_usage must exist", tableExists(db, "daily_app_usage"))
        assertTrue("child_screen_time_limits must exist", tableExists(db, "child_screen_time_limits"))

        assertEquals(
            "daily_app_usage is keyed by account + child + day + package",
            setOf("accountId", "childId", "dateKey", "packageName"),
            primaryKeyColumns(db, "daily_app_usage"),
        )
        assertEquals(
            "child_screen_time_limits is keyed by account + child + scope + category",
            setOf("accountId", "childId", "scope", "category"),
            primaryKeyColumns(db, "child_screen_time_limits"),
        )

        val usageIndices = indexNames(db, "daily_app_usage")
        assertTrue(
            "missing day index, found $usageIndices",
            usageIndices.contains("index_daily_app_usage_accountId_childId_dateKey"),
        )
        assertTrue(
            "missing category index, found $usageIndices",
            usageIndices.contains("index_daily_app_usage_accountId_childId_dateKey_category"),
        )

        val limitIndices = indexNames(db, "child_screen_time_limits")
        assertTrue(
            "missing child index, found $limitIndices",
            limitIndices.contains("index_child_screen_time_limits_accountId_childId"),
        )
    }

    private fun tableExists(db: FaceGuardDatabase, table: String): Boolean =
        db.openHelper.readableDatabase.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?",
            arrayOf(table),
        ).use { it.moveToFirst() }

    private fun primaryKeyColumns(db: FaceGuardDatabase, table: String): Set<String> =
        db.openHelper.readableDatabase.query("PRAGMA table_info(`$table`)").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            val pkIndex = cursor.getColumnIndexOrThrow("pk")
            val columns = mutableSetOf<String>()
            while (cursor.moveToNext()) {
                if (cursor.getInt(pkIndex) > 0) columns += cursor.getString(nameIndex)
            }
            columns
        }

    private fun indexNames(db: FaceGuardDatabase, table: String): Set<String> =
        db.openHelper.readableDatabase.query("PRAGMA index_list(`$table`)").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            val names = mutableSetOf<String>()
            while (cursor.moveToNext()) names += cursor.getString(nameIndex)
            names
        }

    private companion object {
        const val DB_NAME = "migration-6-7-test.db"
    }
}

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
 * Phase 4 Step 1B-6: the real v7 -> v8 migration.
 *
 * Builds a genuine v7 database with the DDL Room generated through v7, writes a row into
 * representative tables (including both screen-time tables added in v7), then opens the
 * latest database through Room. Room runs 7 -> 8 *and* validates the resulting schema
 * against the current entities, so a mismatch between [MIGRATION_7_8] and
 * [UsageSnapshotCheckpointEntity] fails here instead of on a user device. The migration is
 * additive: no existing row may be lost and no table may be recreated destructively.
 */
@RunWith(AndroidJUnit4::class)
class FaceGuardMigration7To8Test {

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

    private fun exec(db: SQLiteDatabase, sql: String) = db.execSQL(sql)

    /** Exactly the schema Room v7 would have left on disk: v1..v6 tables plus v6 -> v7. */
    private fun createV7Database() {
        val db = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(DB_NAME), null)
        V7_DDL.forEach { exec(db, it) }

        db.execSQL(
            "INSERT INTO user_accounts (id, fullName, phoneNumber, pinHash, pinSalt, createdAt) " +
                "VALUES (1, 'Parent', '998901234567', 'hash', 'salt', 1000)",
        )
        db.execSQL(
            "INSERT INTO child_profiles (id, accountId, childName, isFaceEnrolled, faceTemplateRef, " +
                "restrictionLevel, enrollmentStatus, enrollmentVersion, lastEnrollmentAt, createdAt, updatedAt) " +
                "VALUES (10, 1, 'Vali', 1, 'enc-child', 'MEDIUM', 'ENROLLED', 1, 1200, 1000, 1200)",
        )
        db.execSQL(
            "INSERT INTO child_app_policies (accountId, childId, packageName, mode, action, dailyLimitMinutes, " +
                "activationDelayMs, recoveryDelayMs, enabled, createdAt, updatedAt) " +
                "VALUES (1, 10, 'com.google.android.youtube', 'LIMIT', 'SOFT_BLOCK', 30, 0, 0, 1, 1000, 1000)",
        )
        db.execSQL(
            "INSERT INTO daily_app_usage (accountId, childId, dateKey, packageName, usedMs, category, updatedAt) " +
                "VALUES (1, 10, '2026-09-24', 'com.google.android.youtube', 600000, 'VIDEO', 1000)",
        )
        db.execSQL(
            "INSERT INTO child_screen_time_limits (accountId, childId, scope, category, limitMinutes, updatedAt) " +
                "VALUES (1, 10, 'TOTAL', '', 120, 1000)",
        )

        db.version = 7
        db.close()
    }

    private fun openLatest(): FaceGuardDatabase =
        Room.databaseBuilder(context, FaceGuardDatabase::class.java, DB_NAME)
            .addMigrations(MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
            .allowMainThreadQueries()
            .build()
            .also {
                room = it
                it.openHelper.writableDatabase // triggers the migration + Room schema validation
            }

    @Test
    fun migration7To8_isAdditiveAndKeepsEveryExistingRow() = runBlocking {
        createV7Database()

        val db = openLatest()

        assertEquals("the database is now v8", 8, db.openHelper.writableDatabase.version)
        assertNotNull(db.userAccountDao().getById(1L))
        assertEquals("Vali", db.childProfileDao().observeAll(1L).first().single().childName)
        assertEquals("LIMIT", db.childAppPolicyDao().getPolicy(1L, 10L, "com.google.android.youtube")!!.mode)
        assertEquals(
            "v7 screen-time usage survives",
            600_000L,
            db.dailyAppUsageDao().packageUsage(1L, 10L, "2026-09-24", "com.google.android.youtube")!!.usedMs,
        )
        assertEquals(120, db.childScreenTimeLimitDao().limit(1L, 10L, "TOTAL", "")!!.limitMinutes)
    }

    @Test
    fun theNewCheckpointTableIsUsableAfterMigration() = runBlocking {
        createV7Database()
        val db = openLatest()

        val dao = db.usageSnapshotCheckpointDao()
        assertEquals("the table starts empty", emptyList<UsageSnapshotCheckpointEntity>(), dao.checkpointsForWindow(1L, 10L, "USAGE_STATS", 1_790_294_400_000L, 1_790_380_800_000L))

        dao.upsert(
            UsageSnapshotCheckpointEntity(
                accountId = 1L,
                childId = 10L,
                source = "USAGE_STATS",
                windowStartMs = 1_790_294_400_000L,
                windowEndMs = 1_790_380_800_000L,
                packageName = "com.google.android.youtube",
                cumulativeForegroundMs = 50_000L,
                observedAtMs = 1_790_295_000_000L,
            ),
        )

        val stored = dao.checkpoint(1L, 10L, "USAGE_STATS", 1_790_294_400_000L, 1_790_380_800_000L, "com.google.android.youtube")
        assertNotNull(stored)
        assertEquals(50_000L, stored!!.cumulativeForegroundMs)
    }

    @Test
    fun theMigratedCheckpointTableHasTheExpectedPrimaryKey() = runBlocking {
        createV7Database()
        val db = openLatest()
        val raw = db.openHelper.readableDatabase

        // The full identity is the primary key, so no observation can collide with another.
        val pk = raw.query("PRAGMA table_info(`usage_snapshot_checkpoints`)").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            val pkIndex = cursor.getColumnIndexOrThrow("pk")
            val columns = mutableMapOf<Int, String>()
            while (cursor.moveToNext()) {
                val order = cursor.getInt(pkIndex)
                if (order > 0) columns[order] = cursor.getString(nameIndex)
            }
            columns.toSortedMap().values.toList()
        }
        assertEquals(
            listOf("accountId", "childId", "source", "windowStartMs", "windowEndMs", "packageName"),
            pk,
        )

        // And it is enforced: the same key cannot be stored twice.
        raw.execSQL(
            "INSERT INTO usage_snapshot_checkpoints VALUES (1, 10, 'USAGE_STATS', 100, 200, 'com.a', 10, 1)",
        )
        val rejected = runCatching {
            raw.execSQL(
                "INSERT INTO usage_snapshot_checkpoints VALUES (1, 10, 'USAGE_STATS', 100, 200, 'com.a', 20, 2)",
            )
        }
        assertTrue("a duplicate identity must be rejected", rejected.isFailure)
    }

    @Test
    fun theMigrationDidNotTouchTheOlderTables() = runBlocking {
        createV7Database()
        val db = openLatest()
        val raw = db.openHelper.readableDatabase

        // Column sets of the older screen-time tables are unchanged by 7 -> 8.
        val usageColumns = raw.query("PRAGMA table_info(`daily_app_usage`)").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            buildList { while (cursor.moveToNext()) add(cursor.getString(nameIndex)) }
        }
        assertEquals(
            listOf("accountId", "childId", "dateKey", "packageName", "usedMs", "category", "updatedAt"),
            usageColumns,
        )
    }

    private companion object {
        const val DB_NAME = "migration-7-8-test.db"

        /** The v7 schema, used verbatim from the entities' generated DDL. */
        val V7_DDL: List<String> = listOf(
            "CREATE TABLE IF NOT EXISTS `user_accounts` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `fullName` TEXT NOT NULL, `phoneNumber` TEXT NOT NULL, `pinHash` TEXT NOT NULL, `pinSalt` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_user_accounts_phoneNumber` ON `user_accounts` (`phoneNumber`)",
            "CREATE TABLE IF NOT EXISTS `parent_profiles` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `accountId` INTEGER NOT NULL, `displayName` TEXT NOT NULL, `isFaceEnrolled` INTEGER NOT NULL, `faceTemplateRef` TEXT, `enrollmentStatus` TEXT NOT NULL, `enrollmentVersion` INTEGER NOT NULL, `lastEnrollmentAt` INTEGER, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS `child_profiles` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `accountId` INTEGER NOT NULL, `childName` TEXT NOT NULL, `isFaceEnrolled` INTEGER NOT NULL, `faceTemplateRef` TEXT, `restrictionLevel` TEXT NOT NULL, `enrollmentStatus` TEXT NOT NULL, `enrollmentVersion` INTEGER NOT NULL, `lastEnrollmentAt` INTEGER, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL)",
            "CREATE INDEX IF NOT EXISTS `index_child_profiles_accountId` ON `child_profiles` (`accountId`)",
            "CREATE TABLE IF NOT EXISTS `protected_apps` (`packageName` TEXT NOT NULL, `appDisplayName` TEXT NOT NULL, `isProtected` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`packageName`))",
            "CREATE TABLE IF NOT EXISTS `activity_events` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `accountId` INTEGER NOT NULL, `type` TEXT NOT NULL, `detail` TEXT, `at` INTEGER NOT NULL)",
            "CREATE INDEX IF NOT EXISTS `index_activity_events_at` ON `activity_events` (`at`)",
            "CREATE INDEX IF NOT EXISTS `index_activity_events_accountId_at` ON `activity_events` (`accountId`, `at`)",
            "CREATE TABLE IF NOT EXISTS `child_app_policies` (`accountId` INTEGER NOT NULL, `childId` INTEGER NOT NULL, `packageName` TEXT NOT NULL, `mode` TEXT NOT NULL, `action` TEXT NOT NULL, `dailyLimitMinutes` INTEGER, `activationDelayMs` INTEGER NOT NULL, `recoveryDelayMs` INTEGER NOT NULL, `enabled` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`accountId`, `childId`, `packageName`))",
            "CREATE INDEX IF NOT EXISTS `index_child_app_policies_accountId_childId` ON `child_app_policies` (`accountId`, `childId`)",
            "CREATE TABLE IF NOT EXISTS `parent_requests` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `accountId` INTEGER NOT NULL, `childId` INTEGER NOT NULL, `targetPackageName` TEXT NOT NULL, `requestType` TEXT NOT NULL, `requestedDurationMinutes` INTEGER NOT NULL, `approvedDurationMinutes` INTEGER, `status` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `expiresAt` INTEGER, `resolvedAt` INTEGER, `resolutionReason` TEXT, `source` TEXT NOT NULL, `deduplicationKey` TEXT NOT NULL)",
            "CREATE INDEX IF NOT EXISTS `index_parent_requests_accountId_createdAt` ON `parent_requests` (`accountId`, `createdAt`)",
            "CREATE INDEX IF NOT EXISTS `index_parent_requests_accountId_status_createdAt` ON `parent_requests` (`accountId`, `status`, `createdAt`)",
            "CREATE INDEX IF NOT EXISTS `index_parent_requests_accountId_childId_status` ON `parent_requests` (`accountId`, `childId`, `status`)",
            "CREATE INDEX IF NOT EXISTS `index_parent_requests_accountId_deduplicationKey_status` ON `parent_requests` (`accountId`, `deduplicationKey`, `status`)",
            "CREATE TABLE IF NOT EXISTS `notification_records` (`deduplicationKey` TEXT NOT NULL, `accountId` INTEGER NOT NULL, `type` TEXT NOT NULL, `relatedRequestId` INTEGER, `createdAt` INTEGER NOT NULL, `delivered` INTEGER NOT NULL, `deliveryAt` INTEGER, PRIMARY KEY(`deduplicationKey`))",
            "CREATE INDEX IF NOT EXISTS `index_notification_records_accountId_createdAt` ON `notification_records` (`accountId`, `createdAt`)",
            "CREATE TABLE IF NOT EXISTS `daily_app_usage` (`accountId` INTEGER NOT NULL, `childId` INTEGER NOT NULL, `dateKey` TEXT NOT NULL, `packageName` TEXT NOT NULL, `usedMs` INTEGER NOT NULL, `category` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`accountId`, `childId`, `dateKey`, `packageName`))",
            "CREATE INDEX IF NOT EXISTS `index_daily_app_usage_accountId_childId_dateKey` ON `daily_app_usage` (`accountId`, `childId`, `dateKey`)",
            "CREATE INDEX IF NOT EXISTS `index_daily_app_usage_accountId_childId_dateKey_category` ON `daily_app_usage` (`accountId`, `childId`, `dateKey`, `category`)",
            "CREATE TABLE IF NOT EXISTS `child_screen_time_limits` (`accountId` INTEGER NOT NULL, `childId` INTEGER NOT NULL, `scope` TEXT NOT NULL, `category` TEXT NOT NULL, `limitMinutes` INTEGER, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`accountId`, `childId`, `scope`, `category`))",
            "CREATE INDEX IF NOT EXISTS `index_child_screen_time_limits_accountId_childId` ON `child_screen_time_limits` (`accountId`, `childId`)",
        )
    }
}

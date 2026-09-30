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
 * Phase 6 Step 3: the real v9 -> v10 migration.
 *
 * Builds a genuine v9 database with the DDL Room generated through v9 (including the v9 schedule
 * tables), writes a row into representative tables from every earlier phase, then opens the latest
 * database through Room. Room runs 9 -> 10 *and* validates the resulting schema against the current
 * entities, so a mismatch between [MIGRATION_9_10] and [ChildEyeSafetyEntity] fails here instead of
 * on a user device. The migration is additive: no existing row may be lost and no existing table
 * may be altered.
 */
@RunWith(AndroidJUnit4::class)
class FaceGuardMigration9To10Test {

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

    /** Exactly the schema Room v9 would have left on disk: v1..v8 tables plus v8 -> v9. */
    private fun createV9Database() {
        val db = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(DB_NAME), null)
        V9_DDL.forEach { db.execSQL(it) }
        db.version = 9
        db.close()
    }

    /** Seeds one row into every table a pre-v10 device could plausibly hold. */
    private fun seedV9Data() {
        val db = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(DB_NAME), null)
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
            "INSERT INTO activity_events (id, accountId, type, detail, at) " +
                "VALUES (1, 1, 'CHILD_RECOGNIZED', 'details', 1500)",
        )
        db.execSQL(
            "INSERT INTO child_app_policies (accountId, childId, packageName, mode, action, dailyLimitMinutes, " +
                "activationDelayMs, recoveryDelayMs, enabled, createdAt, updatedAt) " +
                "VALUES (1, 10, 'com.google.android.youtube', 'LIMIT', 'SOFT_BLOCK', 30, 0, 0, 1, 1000, 1000)",
        )
        db.execSQL(
            "INSERT INTO parent_requests (id, accountId, childId, targetPackageName, requestType, " +
                "requestedDurationMinutes, approvedDurationMinutes, status, createdAt, updatedAt, expiresAt, " +
                "resolvedAt, resolutionReason, source, deduplicationKey) " +
                "VALUES (1, 1, 10, 'com.google.android.youtube', 'EXTRA_TIME', 15, NULL, 'PENDING', " +
                "1600, 1600, NULL, NULL, NULL, 'CHILD_OVERLAY', 'dedup-1')",
        )
        db.execSQL(
            "INSERT INTO notification_records (deduplicationKey, accountId, type, relatedRequestId, createdAt, " +
                "delivered, deliveryAt) VALUES ('dedup-1', 1, 'EXTRA_TIME_REQUEST', 1, 1600, 0, NULL)",
        )
        db.execSQL(
            "INSERT INTO daily_app_usage (accountId, childId, dateKey, packageName, usedMs, category, updatedAt) " +
                "VALUES (1, 10, '2026-09-24', 'com.google.android.youtube', 600000, 'VIDEO', 1000)",
        )
        db.execSQL(
            "INSERT INTO child_screen_time_limits (accountId, childId, scope, category, limitMinutes, updatedAt) " +
                "VALUES (1, 10, 'TOTAL', '', 120, 1000)",
        )
        db.execSQL(
            "INSERT INTO usage_snapshot_checkpoints (accountId, childId, source, windowStartMs, windowEndMs, " +
                "packageName, cumulativeForegroundMs, observedAtMs) " +
                "VALUES (1, 10, 'USAGE_STATS', 100, 200, 'com.google.android.youtube', 50000, 150)",
        )
        // v9's own tables, so a v9 -> v10 upgrade is exercised with schedule data present.
        db.execSQL(
            "INSERT INTO schedule_rules (id, accountId, childId, name, mode, enabled, startMinuteOfDay, " +
                "endMinuteOfDay, daysMask, priority, action, createdAt, updatedAt) " +
                "VALUES (5, 1, 10, 'Bedtime', 'SLEEP', 1, 1320, 420, 1, 5, 'HARD_BLOCK', 1000, 1000)",
        )
        db.execSQL(
            "INSERT INTO schedule_app_targets (accountId, childId, scheduleId, packageName) " +
                "VALUES (1, 10, 5, 'com.google.android.youtube')",
        )
        db.close()
    }

    private fun openLatest(): FaceGuardDatabase =
        Room.databaseBuilder(context, FaceGuardDatabase::class.java, DB_NAME)
            .addMigrations(
                MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7,
                MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10,
            )
            .allowMainThreadQueries()
            .build()
            .also {
                room = it
                it.openHelper.writableDatabase // triggers the migration + Room schema validation
            }

    // ---- 1. data preservation ----------------------------------------------

    @Test
    fun migration9To10_isAdditiveAndKeepsEveryExistingRow() = runBlocking {
        createV9Database()
        seedV9Data()

        val db = openLatest()

        assertEquals("the database is now v10", 10, db.openHelper.writableDatabase.version)

        assertNotNull(db.userAccountDao().getById(1L))
        assertEquals("Vali", db.childProfileDao().observeAll(1L).first().single().childName)
        assertEquals("LIMIT", db.childAppPolicyDao().getPolicy(1L, 10L, "com.google.android.youtube")!!.mode)
        assertEquals(
            "v9 screen-time usage survives",
            600_000L,
            db.dailyAppUsageDao().packageUsage(1L, 10L, "2026-09-24", "com.google.android.youtube")!!.usedMs,
        )
        assertEquals(120, db.childScreenTimeLimitDao().limit(1L, 10L, "TOTAL", "")!!.limitMinutes)
        assertEquals(
            "v9 checkpoints survive",
            50_000L,
            db.usageSnapshotCheckpointDao()
                .checkpoint(1L, 10L, "USAGE_STATS", 100L, 200L, "com.google.android.youtube")!!
                .cumulativeForegroundMs,
        )
        assertEquals("v9 schedules survive", "Bedtime", db.scheduleDao().schedule(1L, 10L, 5L)!!.name)
        assertEquals(
            "v9 schedule targets survive",
            listOf("com.google.android.youtube"),
            db.scheduleDao().targetPackages(1L, 10L, 5L),
        )

        val raw = db.openHelper.readableDatabase
        assertEquals(1, count(raw, "protected_apps"))
        assertEquals(1, count(raw, "activity_events"))
        assertEquals(1, count(raw, "parent_requests"))
        assertEquals(1, count(raw, "notification_records"))
    }

    private fun count(db: androidx.sqlite.db.SupportSQLiteDatabase, table: String): Int =
        db.query("SELECT COUNT(*) FROM `$table`").use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }

    // ---- 2. the new table ----------------------------------------------------

    @Test
    fun theNewEyeSafetyTableExistsAndIsEmpty() = runBlocking {
        createV9Database()
        seedV9Data()
        val db = openLatest()

        assertEquals(null, db.childEyeSafetyDao().config(1L, 10L))
    }

    @Test
    fun theMigrationSeedsNoConfigurationForAnyChild() = runBlocking {
        createV9Database()
        seedV9Data()
        val db = openLatest()

        // Child 10 exists in child_profiles, yet no eye-safety row may be invented for it.
        assertEquals(0, count(db.openHelper.readableDatabase, "child_eye_safety"))
        assertEquals(null, db.childEyeSafetyDao().config(1L, 10L))
    }

    @Test
    fun theNewEyeSafetyTableIsUsableAfterMigration() = runBlocking {
        createV9Database()
        seedV9Data()
        val db = openLatest()

        val dao = db.childEyeSafetyDao()
        dao.upsert(
            ChildEyeSafetyEntity(
                accountId = 1L,
                childId = 10L,
                enabled = true,
                warningEnterThresholdPercent = 30,
                warningExitThresholdPercent = 27,
                dangerEnterThresholdPercent = 40,
                dangerExitThresholdPercent = 35,
                confirmFrames = 3,
                warningAction = "WARNING",
                dangerAction = "SOFT_BLOCK",
                updatedAt = 2_000L,
            ),
        )

        val stored = dao.config(1L, 10L)!!
        assertEquals(30, stored.warningEnterThresholdPercent)
        assertEquals(40, stored.dangerEnterThresholdPercent)
        assertEquals("WARNING", stored.warningAction)
        assertEquals("SOFT_BLOCK", stored.dangerAction)
    }

    // ---- 3. schema ----------------------------------------------------------

    @Test
    fun theMigratedEyeSafetyTableHasTheExpectedColumns() = runBlocking {
        createV9Database()
        val db = openLatest()
        val raw = db.openHelper.readableDatabase

        assertEquals(
            listOf(
                "accountId", "childId", "enabled",
                "warningEnterThresholdPercent", "warningExitThresholdPercent",
                "dangerEnterThresholdPercent", "dangerExitThresholdPercent",
                "confirmFrames", "warningAction", "dangerAction", "updatedAt",
            ),
            columnsOf(raw, "child_eye_safety"),
        )
    }

    @Test
    fun theMigratedEyeSafetyTableHasTheExpectedPrimaryKeyOrder() = runBlocking {
        createV9Database()
        val db = openLatest()
        val raw = db.openHelper.readableDatabase

        // Room validates exactly this, so a wrong order would already have failed openLatest().
        assertEquals(listOf("accountId", "childId"), primaryKeyOf(raw, "child_eye_safety"))
    }

    @Test
    fun theEyeSafetyPrimaryKeyIsEnforced() = runBlocking {
        createV9Database()
        val db = openLatest()
        val raw = db.openHelper.writableDatabase

        raw.execSQL("INSERT INTO child_eye_safety VALUES (1, 10, 1, 30, 27, 40, 35, 3, 'WARNING', 'SOFT_BLOCK', 1)")
        val duplicate = runCatching {
            raw.execSQL("INSERT INTO child_eye_safety VALUES (1, 10, 1, 30, 27, 40, 35, 3, 'WARNING', 'SOFT_BLOCK', 2)")
        }

        assertTrue("the composite primary key must be enforced", duplicate.isFailure)
    }

    // ---- 4. older tables are untouched -------------------------------------

    @Test
    fun theMigrationDidNotTouchTheOlderTables() = runBlocking {
        createV9Database()
        val db = openLatest()
        val raw = db.openHelper.readableDatabase

        assertEquals(
            listOf("accountId", "childId", "dateKey", "packageName", "usedMs", "category", "updatedAt"),
            columnsOf(raw, "daily_app_usage"),
        )
        assertEquals(
            listOf("accountId", "childId", "scope", "category", "limitMinutes", "updatedAt"),
            columnsOf(raw, "child_screen_time_limits"),
        )
        assertEquals(
            listOf(
                "id", "accountId", "childId", "name", "mode", "enabled",
                "startMinuteOfDay", "endMinuteOfDay", "daysMask", "priority",
                "action", "createdAt", "updatedAt",
            ),
            columnsOf(raw, "schedule_rules"),
        )
        assertEquals(
            listOf("accountId", "childId", "scheduleId", "packageName"),
            columnsOf(raw, "schedule_app_targets"),
        )
    }

    // ---- 5. empty database --------------------------------------------------

    @Test
    fun migrationWorksOnAnEmptyV9Database() = runBlocking {
        createV9Database()

        val db = openLatest()

        assertEquals(10, db.openHelper.writableDatabase.version)
        assertEquals(null, db.childEyeSafetyDao().config(1L, 10L))
    }

    // ---- 6. multiple accounts / children -----------------------------------

    @Test
    fun configurationCoexistsForSeveralAccountsAndChildrenWithoutCollision() = runBlocking {
        createV9Database()
        seedV9Data()
        val db = openLatest()
        val dao = db.childEyeSafetyDao()

        fun entity(accountId: Long, childId: Long, percent: Int) = ChildEyeSafetyEntity(
            accountId = accountId,
            childId = childId,
            enabled = true,
            warningEnterThresholdPercent = percent,
            warningExitThresholdPercent = percent - 3,
            dangerEnterThresholdPercent = percent + 10,
            dangerExitThresholdPercent = percent + 5,
            confirmFrames = 3,
            warningAction = "WARNING",
            dangerAction = "SOFT_BLOCK",
            updatedAt = 1_000L,
        )

        dao.upsert(entity(1L, 10L, 30))
        dao.upsert(entity(1L, 11L, 35))
        dao.upsert(entity(2L, 10L, 40))

        assertEquals(30, dao.config(1L, 10L)!!.warningEnterThresholdPercent)
        assertEquals(35, dao.config(1L, 11L)!!.warningEnterThresholdPercent)
        assertEquals(40, dao.config(2L, 10L)!!.warningEnterThresholdPercent)

        // A (account, child) pair with no row is not visible under any other pair.
        assertEquals(null, dao.config(2L, 11L))
        assertEquals(null, dao.config(1L, 12L))
    }

    private fun columnsOf(db: androidx.sqlite.db.SupportSQLiteDatabase, table: String): List<String> =
        db.query("PRAGMA table_info(`$table`)").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            buildList { while (cursor.moveToNext()) add(cursor.getString(nameIndex)) }
        }

    private fun primaryKeyOf(db: androidx.sqlite.db.SupportSQLiteDatabase, table: String): List<String> =
        db.query("PRAGMA table_info(`$table`)").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            val pkIndex = cursor.getColumnIndexOrThrow("pk")
            val ordered = mutableMapOf<Int, String>()
            while (cursor.moveToNext()) {
                val order = cursor.getInt(pkIndex)
                if (order > 0) ordered[order] = cursor.getString(nameIndex)
            }
            ordered.toSortedMap().values.toList()
        }

    private companion object {
        const val DB_NAME = "migration-9-10-test.db"

        /** The v9 schema: v1..v8 tables plus the v8 -> v9 schedule tables. */
        val V9_DDL: List<String> = listOf(
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
            "CREATE TABLE IF NOT EXISTS `usage_snapshot_checkpoints` (`accountId` INTEGER NOT NULL, `childId` INTEGER NOT NULL, `source` TEXT NOT NULL, `windowStartMs` INTEGER NOT NULL, `windowEndMs` INTEGER NOT NULL, `packageName` TEXT NOT NULL, `cumulativeForegroundMs` INTEGER NOT NULL, `observedAtMs` INTEGER NOT NULL, PRIMARY KEY(`accountId`, `childId`, `source`, `windowStartMs`, `windowEndMs`, `packageName`))",
            "CREATE TABLE IF NOT EXISTS `schedule_rules` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `accountId` INTEGER NOT NULL, `childId` INTEGER NOT NULL, `name` TEXT NOT NULL, `mode` TEXT NOT NULL, `enabled` INTEGER NOT NULL, `startMinuteOfDay` INTEGER NOT NULL, `endMinuteOfDay` INTEGER NOT NULL, `daysMask` INTEGER NOT NULL, `priority` INTEGER NOT NULL, `action` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL)",
            "CREATE INDEX IF NOT EXISTS `index_schedule_rules_accountId_childId` ON `schedule_rules` (`accountId`, `childId`)",
            "CREATE TABLE IF NOT EXISTS `schedule_app_targets` (`accountId` INTEGER NOT NULL, `childId` INTEGER NOT NULL, `scheduleId` INTEGER NOT NULL, `packageName` TEXT NOT NULL, PRIMARY KEY(`accountId`, `childId`, `scheduleId`, `packageName`))",
        )
    }
}

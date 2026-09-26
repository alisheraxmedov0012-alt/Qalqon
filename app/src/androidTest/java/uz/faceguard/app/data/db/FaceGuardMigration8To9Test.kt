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
 * Phase 5 Step 2: the real v8 -> v9 migration.
 *
 * Builds a genuine v8 database with the DDL Room generated through v8, writes a row into
 * representative tables from every earlier phase, then opens the latest database through Room.
 * Room runs 8 -> 9 *and* validates the resulting schema against the current entities, so a
 * mismatch between [MIGRATION_8_9] and [ScheduleRuleEntity]/[ScheduleAppTargetEntity] fails
 * here instead of on a user device. The migration is additive: no existing row may be lost and
 * no existing table may be altered.
 */
@RunWith(AndroidJUnit4::class)
class FaceGuardMigration8To9Test {

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

    /** Exactly the schema Room v8 would have left on disk: v1..v7 tables plus v7 -> v8. */
    private fun createV8Database() {
        val db = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(DB_NAME), null)
        V8_DDL.forEach { exec(db, it) }
        db.version = 8
        db.close()
    }

    /** Seeds one row into every table a pre-v9 device could plausibly hold. */
    private fun seedV8Data() {
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

    // ---- 1. data preservation ----------------------------------------------

    @Test
    fun migration8To9_isAdditiveAndKeepsEveryExistingRow() = runBlocking {
        createV8Database()
        seedV8Data()

        val db = openLatest()

        assertEquals("the database is now v9", 9, db.openHelper.writableDatabase.version)

        assertNotNull(db.userAccountDao().getById(1L))
        assertEquals("Vali", db.childProfileDao().observeAll(1L).first().single().childName)
        assertEquals("LIMIT", db.childAppPolicyDao().getPolicy(1L, 10L, "com.google.android.youtube")!!.mode)
        assertEquals(
            "v8 screen-time usage survives",
            600_000L,
            db.dailyAppUsageDao().packageUsage(1L, 10L, "2026-09-24", "com.google.android.youtube")!!.usedMs,
        )
        assertEquals(120, db.childScreenTimeLimitDao().limit(1L, 10L, "TOTAL", "")!!.limitMinutes)
        assertEquals(
            "v8 checkpoints survive",
            50_000L,
            db.usageSnapshotCheckpointDao()
                .checkpoint(1L, 10L, "USAGE_STATS", 100L, 200L, "com.google.android.youtube")!!
                .cumulativeForegroundMs,
        )

        // Rows only reachable through raw SQL are checked the same way.
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

    // ---- 2. the new tables --------------------------------------------------

    @Test
    fun theNewScheduleTablesExistAndAreEmpty() = runBlocking {
        createV8Database()
        seedV8Data()
        val db = openLatest()

        assertEquals(emptyList<ScheduleRuleEntity>(), db.scheduleDao().schedules(1L, 10L))
        assertEquals(emptyList<String>(), db.scheduleDao().targetPackages(1L, 10L, 1L))
    }

    @Test
    fun theNewScheduleTablesAreUsableAfterMigration() = runBlocking {
        createV8Database()
        seedV8Data()
        val db = openLatest()

        val dao = db.scheduleDao()
        val id = dao.insert(
            ScheduleRuleEntity(
                accountId = 1L,
                childId = 10L,
                name = "Bedtime",
                mode = "SLEEP",
                enabled = true,
                startMinuteOfDay = 1320,
                endMinuteOfDay = 420,
                daysMask = 0b0000001,
                priority = 5,
                action = "HARD_BLOCK",
                createdAt = 2_000L,
                updatedAt = 2_000L,
            ),
        )
        assertTrue("the row gets a positive generated id", id > 0L)

        dao.insertTargets(
            listOf(
                ScheduleAppTargetEntity(1L, 10L, id, "com.google.android.youtube"),
                ScheduleAppTargetEntity(1L, 10L, id, "com.example.game"),
            ),
        )

        val stored = dao.schedule(1L, 10L, id)!!
        assertEquals("Bedtime", stored.name)
        assertEquals(1320, stored.startMinuteOfDay)
        assertEquals(420, stored.endMinuteOfDay)
        assertEquals(
            listOf("com.example.game", "com.google.android.youtube"),
            dao.targetPackages(1L, 10L, id),
        )
    }

    // ---- 3. schema ----------------------------------------------------------

    @Test
    fun theMigratedScheduleTablesHaveTheExpectedColumnsAndPrimaryKeys() = runBlocking {
        createV8Database()
        val db = openLatest()
        val raw = db.openHelper.readableDatabase

        assertEquals(
            listOf(
                "id", "accountId", "childId", "name", "mode", "enabled",
                "startMinuteOfDay", "endMinuteOfDay", "daysMask", "priority",
                "action", "createdAt", "updatedAt",
            ),
            columnsOf(raw, "schedule_rules"),
        )
        assertEquals(
            listOf("id"),
            primaryKeyOf(raw, "schedule_rules"),
        )
        assertEquals(
            listOf("accountId", "childId", "scheduleId", "packageName"),
            columnsOf(raw, "schedule_app_targets"),
        )
        assertEquals(
            listOf("accountId", "childId", "scheduleId", "packageName"),
            primaryKeyOf(raw, "schedule_app_targets"),
        )
        assertTrue(
            "the (accountId, childId) lookup index is created",
            indexNamesOf(raw, "schedule_rules").contains("index_schedule_rules_accountId_childId"),
        )
    }

    @Test
    fun theScheduleTargetPrimaryKeyIsEnforced() = runBlocking {
        createV8Database()
        val db = openLatest()
        val raw = db.openHelper.readableDatabase

        raw.execSQL("INSERT INTO schedule_app_targets VALUES (1, 10, 1, 'com.a')")
        val duplicate = runCatching {
            raw.execSQL("INSERT INTO schedule_app_targets VALUES (1, 10, 1, 'com.a')")
        }
        assertTrue("the same target cannot be stored twice", duplicate.isFailure)
    }

    // ---- 4. older tables are untouched -------------------------------------

    @Test
    fun theMigrationDidNotTouchTheOlderTables() = runBlocking {
        createV8Database()
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
                "accountId", "childId", "source", "windowStartMs", "windowEndMs",
                "packageName", "cumulativeForegroundMs", "observedAtMs",
            ),
            columnsOf(raw, "usage_snapshot_checkpoints"),
        )
    }

    // ---- 5. empty database --------------------------------------------------

    @Test
    fun migrationWorksOnAnEmptyV8Database() = runBlocking {
        createV8Database()

        val db = openLatest()

        assertEquals(9, db.openHelper.writableDatabase.version)
        assertEquals(emptyList<ScheduleRuleEntity>(), db.scheduleDao().schedules(1L, 10L))
        assertEquals(emptyList<String>(), db.scheduleDao().targetPackages(1L, 10L, 1L))
    }

    // ---- 6. multiple accounts / children -----------------------------------

    @Test
    fun schedulesForSeveralAccountsAndChildrenCoexistWithoutCollision() = runBlocking {
        createV8Database()
        seedV8Data()
        val db = openLatest()
        val dao = db.scheduleDao()

        val aA = dao.insert(rule(accountId = 1L, childId = 10L, name = "A/A"))
        val aB = dao.insert(rule(accountId = 1L, childId = 11L, name = "A/B"))
        val bA = dao.insert(rule(accountId = 2L, childId = 10L, name = "B/A"))

        // Same schedule id can be addressed under three scopes and each read returns its own.
        assertEquals("A/A", dao.schedule(1L, 10L, aA)!!.name)
        assertEquals("A/B", dao.schedule(1L, 11L, aB)!!.name)
        assertEquals("B/A", dao.schedule(2L, 10L, bA)!!.name)

        // A schedule id from one scope is not visible under another.
        assertEquals(null, dao.schedule(1L, 11L, aA))
        assertEquals(null, dao.schedule(2L, 10L, aA))
        assertEquals(listOf("A/A"), dao.schedules(1L, 10L).map { it.name })
        assertEquals(listOf("A/B"), dao.schedules(1L, 11L).map { it.name })
        assertEquals(listOf("B/A"), dao.schedules(2L, 10L).map { it.name })
    }

    private fun rule(accountId: Long, childId: Long, name: String) = ScheduleRuleEntity(
        accountId = accountId,
        childId = childId,
        name = name,
        mode = "NORMAL",
        enabled = true,
        startMinuteOfDay = 600,
        endMinuteOfDay = 700,
        daysMask = 0b1111111,
        priority = 0,
        action = "ALLOW",
        createdAt = 1_000L,
        updatedAt = 1_000L,
    )

    private fun columnsOf(db: androidx.sqlite.db.SupportSQLiteDatabase, table: String): List<String> =
        db.query("PRAGMA table_info(`$table`)").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            buildList { while (cursor.moveToNext()) add(cursor.getString(nameIndex)) }
        }

    private fun primaryKeyOf(db: androidx.sqlite.db.SupportSQLiteDatabase, table: String): List<String> =
        db.query("PRAGMA table_info(`$table`)").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            val pkIndex = cursor.getColumnIndexOrThrow("pk")
            val columns = mutableMapOf<Int, String>()
            while (cursor.moveToNext()) {
                val order = cursor.getInt(pkIndex)
                if (order > 0) columns[order] = cursor.getString(nameIndex)
            }
            columns.toSortedMap().values.toList()
        }

    private fun indexNamesOf(db: androidx.sqlite.db.SupportSQLiteDatabase, table: String): List<String> =
        db.query("PRAGMA index_list(`$table`)").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            buildList { while (cursor.moveToNext()) add(cursor.getString(nameIndex)) }
        }

    private companion object {
        const val DB_NAME = "migration-8-9-test.db"

        /** The v8 schema, used verbatim from the entities' generated DDL. */
        val V8_DDL: List<String> = listOf(
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
        )
    }
}

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
 * Phase 11: the real v5 -> v6 migration.
 *
 * Builds a genuine v5 database with the DDL Room generated for v5, writes rows
 * into every table, then opens the latest database through Room. Room runs the
 * 5 -> 6 migration *and* validates the resulting schema against the current
 * entities, so a wrong migration fails here instead of on a device. The
 * migration is additive: no existing row may be lost.
 */
@RunWith(AndroidJUnit4::class)
class FaceGuardMigration5To6Test {

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

    private fun createV5Database() {
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
        // v5 shape: account-scoped activity log.
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `activity_events` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`accountId` INTEGER NOT NULL, `type` TEXT NOT NULL, `detail` TEXT, `at` INTEGER NOT NULL)",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_activity_events_at` ON `activity_events` (`at`)")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_activity_events_accountId_at` " +
                "ON `activity_events` (`accountId`, `at`)",
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
            "INSERT INTO user_accounts (id, fullName, phoneNumber, pinHash, pinSalt, createdAt) " +
                "VALUES (1, 'Parent', '998901234567', 'hash', 'salt', 1000)",
        )
        db.execSQL(
            "INSERT INTO parent_profiles (id, accountId, displayName, isFaceEnrolled, faceTemplateRef, " +
                "enrollmentStatus, enrollmentVersion, lastEnrollmentAt, createdAt, updatedAt) " +
                "VALUES (1, 1, 'Parent', 1, 'template-parent', 'ENROLLED', 1, 1200, 1000, 1200)",
        )
        db.execSQL(
            "INSERT INTO child_profiles (id, accountId, childName, isFaceEnrolled, faceTemplateRef, " +
                "restrictionLevel, enrollmentStatus, enrollmentVersion, lastEnrollmentAt, createdAt, updatedAt) " +
                "VALUES (5, 1, 'Vali', 1, 'template-child', 'MEDIUM', 'ENROLLED', 1, 1200, 1000, 1200)",
        )
        db.execSQL(
            "INSERT INTO protected_apps (packageName, appDisplayName, isProtected, updatedAt) " +
                "VALUES ('com.example.youtube', 'YouTube', 1, 1000)",
        )
        db.execSQL(
            "INSERT INTO child_app_policies (accountId, childId, packageName, mode, action, " +
                "dailyLimitMinutes, activationDelayMs, recoveryDelayMs, enabled, createdAt, updatedAt) " +
                "VALUES (1, 5, 'com.example.youtube', 'BLOCK', 'HARD_BLOCK', NULL, 0, 0, 1, 1000, 1000)",
        )
        db.execSQL(
            "INSERT INTO activity_events (id, accountId, type, detail, at) " +
                "VALUES (1, 1, 'CHILD_BLOCKED', 'Vali', 1400)",
        )

        db.version = 5
        db.close()
    }

    private fun openLatest(): FaceGuardDatabase =
        Room.databaseBuilder(context, FaceGuardDatabase::class.java, DB_NAME)
            .addMigrations(MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
            .allowMainThreadQueries()
            .build()

    @Test
    fun migration5To6_isAdditiveAndKeepsExistingData() = runBlocking {
        createV5Database()
        val db = openLatest()

        // Existing data survived the migration.
        assertNotNull(db.userAccountDao().getById(1L))
        assertNotNull(db.parentProfileDao().get(1L))
        // Room validates the schema on open; a real read proves the table is usable.
        assertEquals(1, db.childProfileDao().observeAll(1L).first().size)
        assertEquals(1, db.protectedAppDao().countProtected())
        assertNotNull(db.childAppPolicyDao().getPolicy(1L, 5L, "com.example.youtube"))
        assertEquals(1, db.activityEventDao().observeRecent(1L).first().size)
    }

    @Test
    fun theNewTablesAreUsableAfterMigration() = runBlocking {
        createV5Database()
        val db = openLatest()

        val id = db.parentRequestDao().insert(
            ParentRequestEntity(
                accountId = 1L,
                childId = 5L,
                targetPackageName = "com.example.youtube",
                requestType = "EXTRA_TIME",
                requestedDurationMinutes = 15,
                status = "PENDING",
                createdAt = 2_000L,
                updatedAt = 2_000L,
                source = "CHILD_OVERLAY",
                deduplicationKey = "EXTRA_TIME:1:5:com.example.youtube",
            ),
        )
        assertTrue(id > 0L)
        assertEquals(1, db.parentRequestDao().observePending(1L, 50).first().size)
        assertEquals(1, db.parentRequestDao().observePendingCount(1L).first())

        val inserted = db.notificationRecordDao().insert(
            NotificationRecordEntity(
                deduplicationKey = "request:$id",
                accountId = 1L,
                type = "PARENT_REQUEST_CREATED",
                createdAt = 2_000L,
            ),
        )
        assertTrue(inserted > 0L)
    }

    private companion object {
        const val DB_NAME = "migration_5_6_test.db"
    }
}

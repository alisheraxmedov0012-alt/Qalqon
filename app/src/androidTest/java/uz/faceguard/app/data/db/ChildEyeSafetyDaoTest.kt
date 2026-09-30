package uz.faceguard.app.data.db

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 6 Step 3: real Room (in-memory) tests for [ChildEyeSafetyDao].
 *
 * CRUD, composite-key lookup, absence semantics and account + child isolation. Runs on a device /
 * emulator through `connectedDebugAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
class ChildEyeSafetyDaoTest {

    private lateinit var db: FaceGuardDatabase
    private lateinit var dao: ChildEyeSafetyDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            FaceGuardDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.childEyeSafetyDao()
    }

    @After
    fun tearDown() = db.close()

    private fun entity(
        accountId: Long = 1L,
        childId: Long = 10L,
        enabled: Boolean = true,
        warningEnterPercent: Int = 30,
        warningExitPercent: Int = 27,
        dangerEnterPercent: Int = 40,
        dangerExitPercent: Int = 35,
        confirmFrames: Int = 3,
        warningAction: String = "WARNING",
        dangerAction: String = "SOFT_BLOCK",
        updatedAt: Long = 1_000L,
    ) = ChildEyeSafetyEntity(
        accountId = accountId,
        childId = childId,
        enabled = enabled,
        warningEnterThresholdPercent = warningEnterPercent,
        warningExitThresholdPercent = warningExitPercent,
        dangerEnterThresholdPercent = dangerEnterPercent,
        dangerExitThresholdPercent = dangerExitPercent,
        confirmFrames = confirmFrames,
        warningAction = warningAction,
        dangerAction = dangerAction,
        updatedAt = updatedAt,
    )

    // ---- 1/2. insert + get ---------------------------------------------------

    @Test
    fun insertThenReadBackEveryField() = runBlocking {
        dao.upsert(entity())

        val stored = dao.config(1L, 10L)!!
        assertEquals(1L, stored.accountId)
        assertEquals(10L, stored.childId)
        assertTrue(stored.enabled)
        assertEquals(30, stored.warningEnterThresholdPercent)
        assertEquals(27, stored.warningExitThresholdPercent)
        assertEquals(40, stored.dangerEnterThresholdPercent)
        assertEquals(35, stored.dangerExitThresholdPercent)
        assertEquals(3, stored.confirmFrames)
        assertEquals("WARNING", stored.warningAction)
        assertEquals("SOFT_BLOCK", stored.dangerAction)
        assertEquals(1_000L, stored.updatedAt)
    }

    @Test
    fun observeReturnsTheStoredConfiguration() = runBlocking {
        dao.upsert(entity())

        assertEquals(30, dao.observeConfig(1L, 10L).first()!!.warningEnterThresholdPercent)
    }

    // ---- 3. update / upsert --------------------------------------------------

    @Test
    fun upsertReplacesTheRowForTheSameKey() = runBlocking {
        dao.upsert(entity())
        dao.upsert(entity(enabled = false, warningEnterPercent = 45, dangerEnterPercent = 55, updatedAt = 2_000L))

        val stored = dao.config(1L, 10L)!!
        assertEquals(false, stored.enabled)
        assertEquals(45, stored.warningEnterThresholdPercent)
        assertEquals(55, stored.dangerEnterThresholdPercent)
        assertEquals(2_000L, stored.updatedAt)
    }

    @Test
    fun upsertingOneChildLeavesAnotherUntouched() = runBlocking {
        dao.upsert(entity(childId = 10L, warningEnterPercent = 30))
        dao.upsert(entity(childId = 11L, warningEnterPercent = 35))

        dao.upsert(entity(childId = 11L, warningEnterPercent = 50, updatedAt = 9_000L))

        assertEquals(30, dao.config(1L, 10L)!!.warningEnterThresholdPercent)
        assertEquals(1_000L, dao.config(1L, 10L)!!.updatedAt)
        assertEquals(50, dao.config(1L, 11L)!!.warningEnterThresholdPercent)
    }

    // ---- 4/5. delete + absence ----------------------------------------------

    @Test
    fun deleteRemovesOnlyThatChildsConfiguration() = runBlocking {
        dao.upsert(entity(childId = 10L))
        dao.upsert(entity(childId = 11L))

        val deleted = dao.delete(1L, 10L)

        assertEquals(1, deleted)
        assertNull(dao.config(1L, 10L))
        assertEquals(11L, dao.config(1L, 11L)!!.childId)
    }

    @Test
    fun deletingAnUnconfiguredChildChangesNothing() = runBlocking {
        dao.upsert(entity(childId = 10L))

        val deleted = dao.delete(1L, 99L)

        assertEquals(0, deleted)
        assertEquals("child 10 is untouched", 10L, dao.config(1L, 10L)!!.childId)
    }

    @Test
    fun aMissingRowReadsAsAbsence() = runBlocking {
        assertNull("a child with no row has no configuration", dao.config(1L, 10L))
        assertNull(dao.observeConfig(1L, 10L).first())
    }

    @Test
    fun deletingRemovesTheConfigurationFromTheObservedStream() = runBlocking {
        dao.upsert(entity())
        assertTrue(dao.observeConfig(1L, 10L).first() != null)

        dao.delete(1L, 10L)

        assertNull(dao.observeConfig(1L, 10L).first())
    }

    // ---- 6/7/8. composite-key lookup + isolation ----------------------------

    @Test
    fun lookupIsByTheFullCompositeKey() = runBlocking {
        dao.upsert(entity(accountId = 1L, childId = 10L, warningEnterPercent = 30))
        dao.upsert(entity(accountId = 1L, childId = 11L, warningEnterPercent = 31))
        dao.upsert(entity(accountId = 2L, childId = 10L, warningEnterPercent = 32))
        dao.upsert(entity(accountId = 2L, childId = 11L, warningEnterPercent = 33))

        assertEquals(30, dao.config(1L, 10L)!!.warningEnterThresholdPercent)
        assertEquals(31, dao.config(1L, 11L)!!.warningEnterThresholdPercent)
        assertEquals(32, dao.config(2L, 10L)!!.warningEnterThresholdPercent)
        assertEquals(33, dao.config(2L, 11L)!!.warningEnterThresholdPercent)
    }

    @Test
    fun aChildIsNeverVisibleUnderAnotherChild() = runBlocking {
        dao.upsert(entity(accountId = 1L, childId = 10L))

        assertNull("child 11 must not see child 10's configuration", dao.config(1L, 11L))
    }

    @Test
    fun anAccountIsNeverVisibleUnderAnotherAccount() = runBlocking {
        dao.upsert(entity(accountId = 1L, childId = 10L))

        assertNull("account 2 must not see account 1's configuration", dao.config(2L, 10L))
    }

    @Test
    fun theCompositeKeyIsEnforcedAtTheSqlLevel() = runBlocking {
        val raw = db.openHelper.writableDatabase
        raw.execSQL(
            "INSERT INTO child_eye_safety VALUES (1, 10, 1, 30, 27, 40, 35, 3, 'WARNING', 'SOFT_BLOCK', 1)",
        )

        // A second row with the same (accountId, childId) must be rejected.
        val duplicate = runCatching {
            raw.execSQL(
                "INSERT INTO child_eye_safety VALUES (1, 10, 1, 30, 27, 40, 35, 3, 'WARNING', 'SOFT_BLOCK', 2)",
            )
        }

        assertTrue("the composite primary key must be enforced", duplicate.isFailure)
    }

    @Test
    fun theMigratedTableHasTheExpectedColumnsAndPrimaryKey() {
        val raw = db.openHelper.writableDatabase

        val columns = raw.query("PRAGMA table_info(`child_eye_safety`)").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            buildList { while (cursor.moveToNext()) add(cursor.getString(nameIndex)) }
        }
        assertEquals(
            listOf(
                "accountId", "childId", "enabled",
                "warningEnterThresholdPercent", "warningExitThresholdPercent",
                "dangerEnterThresholdPercent", "dangerExitThresholdPercent",
                "confirmFrames", "warningAction", "dangerAction", "updatedAt",
            ),
            columns,
        )

        val primaryKey = raw.query("PRAGMA table_info(`child_eye_safety`)").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            val pkIndex = cursor.getColumnIndexOrThrow("pk")
            val ordered = mutableMapOf<Int, String>()
            while (cursor.moveToNext()) {
                val order = cursor.getInt(pkIndex)
                if (order > 0) ordered[order] = cursor.getString(nameIndex)
            }
            ordered.toSortedMap().values.toList()
        }
        assertEquals(listOf("accountId", "childId"), primaryKey)
    }

    @Test
    fun everyProtectionActionNameIsStorable() = runBlocking {
        uz.faceguard.app.domain.policy.ProtectionAction.entries.forEachIndexed { index, action ->
            dao.upsert(entity(childId = index.toLong() + 1, warningAction = action.name, dangerAction = action.name))
        }

        uz.faceguard.app.domain.policy.ProtectionAction.entries.forEachIndexed { index, action ->
            val stored = dao.config(1L, index.toLong() + 1)!!
            assertEquals(action.name, stored.warningAction)
            assertEquals(action.name, stored.dangerAction)
        }
    }
}

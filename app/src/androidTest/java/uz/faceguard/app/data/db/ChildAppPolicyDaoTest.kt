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
 * Real Room (in-memory) tests for [ChildAppPolicyDao]. Runs on a device /
 * emulator through `connectedDebugAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
class ChildAppPolicyDaoTest {

    private lateinit var db: FaceGuardDatabase
    private lateinit var dao: ChildAppPolicyDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            FaceGuardDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.childAppPolicyDao()
    }

    @After
    fun tearDown() = db.close()

    private fun policy(
        childId: Long = 5L,
        packageName: String = "com.example.youtube",
        mode: String = "BLOCK",
        action: String = "HARD_BLOCK",
        limit: Int? = null,
        activation: Long = 0L,
        recovery: Long = 0L,
        accountId: Long = 1L,
    ) = ChildAppPolicyEntity(
        accountId = accountId,
        childId = childId,
        packageName = packageName,
        mode = mode,
        action = action,
        dailyLimitMinutes = limit,
        activationDelayMs = activation,
        recoveryDelayMs = recovery,
    )

    // 1 — insert
    @Test
    fun insert_thenReadBackEveryField() = runBlocking {
        dao.upsert(policy(mode = "LIMIT", action = "SOFT_BLOCK", limit = 30, activation = 5_000L, recovery = 30_000L))

        val stored = dao.getPolicy(1L, 5L, "com.example.youtube")
        assertEquals("com.example.youtube", stored!!.packageName)
        assertEquals(1L, stored.accountId)
        assertEquals(5L, stored.childId)
        assertEquals("LIMIT", stored.mode)
        assertEquals("SOFT_BLOCK", stored.action)
        assertEquals(30, stored.dailyLimitMinutes)
        assertEquals(5_000L, stored.activationDelayMs)
        assertEquals(30_000L, stored.recoveryDelayMs)
        assertTrue(stored.enabled)
    }

    // 2 — update
    @Test
    fun update_replacesMode() = runBlocking {
        dao.upsert(policy(mode = "BLOCK"))
        dao.upsert(policy(mode = "LIMIT", limit = 45))

        val stored = dao.getPolicy(1L, 5L, "com.example.youtube")!!
        assertEquals("LIMIT", stored.mode)
        assertEquals(45, stored.dailyLimitMinutes)
        assertEquals(1, dao.observePolicies(1L, 5L).first().size)
    }

    // 3 — lookup across children and packages
    @Test
    fun lookup_returnsTheMatchingRow() = runBlocking {
        dao.upsert(policy(childId = 5L, packageName = "com.example.youtube", mode = "BLOCK"))
        dao.upsert(policy(childId = 5L, packageName = "com.example.tiktok", mode = "ALLOW"))
        dao.upsert(policy(childId = 6L, packageName = "com.example.youtube", mode = "LIMIT"))

        assertEquals("BLOCK", dao.getPolicy(1L, 5L, "com.example.youtube")!!.mode)
        assertEquals("ALLOW", dao.getPolicy(1L, 5L, "com.example.tiktok")!!.mode)
        assertEquals("LIMIT", dao.getPolicy(1L, 6L, "com.example.youtube")!!.mode)
    }

    // 4 — child isolation
    @Test
    fun childIsolation_sameAppDifferentModePerChild() = runBlocking {
        dao.upsert(policy(childId = 5L, packageName = "com.example.youtube", mode = "BLOCK"))
        dao.upsert(policy(childId = 6L, packageName = "com.example.youtube", mode = "ALLOW"))

        assertEquals("BLOCK", dao.getPolicy(1L, 5L, "com.example.youtube")!!.mode)
        assertEquals("ALLOW", dao.getPolicy(1L, 6L, "com.example.youtube")!!.mode)
    }

    // 5 — package isolation
    @Test
    fun packageIsolation_sameChildDifferentApps() = runBlocking {
        dao.upsert(policy(packageName = "com.example.youtube", mode = "BLOCK"))
        dao.upsert(policy(packageName = "com.example.tiktok", mode = "ALLOW"))

        assertEquals("BLOCK", dao.getPolicy(1L, 5L, "com.example.youtube")!!.mode)
        assertEquals("ALLOW", dao.getPolicy(1L, 5L, "com.example.tiktok")!!.mode)
    }

    // 6 — observe
    @Test
    fun observePolicies_emitsEveryRowForTheChild() = runBlocking {
        dao.upsert(policy(packageName = "com.example.youtube", mode = "BLOCK"))
        dao.upsert(policy(packageName = "com.example.tiktok", mode = "ALLOW"))
        dao.upsert(policy(packageName = "com.example.game", mode = "LIMIT", limit = 20))

        val rows = dao.observePolicies(1L, 5L).first()
        assertEquals(3, rows.size)
        assertEquals(listOf("com.example.game", "com.example.tiktok", "com.example.youtube"), rows.map { it.packageName })
    }

    // 7 — delete one
    @Test
    fun delete_removesOnlyTheTargetedRow() = runBlocking {
        dao.upsert(policy(packageName = "com.example.youtube"))
        dao.upsert(policy(packageName = "com.example.tiktok"))

        dao.delete(1L, 5L, "com.example.youtube")

        assertNull(dao.getPolicy(1L, 5L, "com.example.youtube"))
        assertEquals(1, dao.observePolicies(1L, 5L).first().size)
    }

    // 8 — deleteForChild
    @Test
    fun deleteForChild_leavesOtherChildrenUntouched() = runBlocking {
        dao.upsert(policy(childId = 5L, packageName = "com.example.youtube"))
        dao.upsert(policy(childId = 5L, packageName = "com.example.tiktok"))
        dao.upsert(policy(childId = 6L, packageName = "com.example.youtube"))

        dao.deleteForChild(1L, 5L)

        assertTrue(dao.observePolicies(1L, 5L).first().isEmpty())
        assertEquals(1, dao.observePolicies(1L, 6L).first().size)
    }

    // 9 — deleteForApp
    @Test
    fun deleteForApp_leavesOtherPackagesUntouched() = runBlocking {
        dao.upsert(policy(childId = 5L, packageName = "com.example.youtube"))
        dao.upsert(policy(childId = 6L, packageName = "com.example.youtube"))
        dao.upsert(policy(childId = 5L, packageName = "com.example.tiktok"))

        dao.deleteForApp(1L, "com.example.youtube")

        assertNull(dao.getPolicy(1L, 5L, "com.example.youtube"))
        assertNull(dao.getPolicy(1L, 6L, "com.example.youtube"))
        assertEquals(1, dao.observePolicies(1L, 5L).first().size)
    }

    // 10 — deleteAll
    @Test
    fun deleteAll_clearsEveryAccountAndChild() = runBlocking {
        dao.upsert(policy(accountId = 1L, childId = 5L))
        dao.upsert(policy(accountId = 1L, childId = 6L))
        dao.upsert(policy(accountId = 2L, childId = 7L))

        dao.deleteAll()

        assertTrue(dao.observePolicies(1L, 5L).first().isEmpty())
        assertTrue(dao.observePolicies(1L, 6L).first().isEmpty())
        assertTrue(dao.observePolicies(2L, 7L).first().isEmpty())
    }

    // composite primary key
    @Test
    fun compositePrimaryKey_allowsSameAppForDifferentChildrenAndUpdatesInPlace() = runBlocking {
        dao.upsert(policy(childId = 5L, packageName = "com.example.youtube", mode = "BLOCK"))
        dao.upsert(policy(childId = 6L, packageName = "com.example.youtube", mode = "ALLOW"))
        assertEquals(1, dao.observePolicies(1L, 5L).first().size)
        assertEquals(1, dao.observePolicies(1L, 6L).first().size)

        // Same PK again -> update, not a duplicate row.
        dao.upsert(policy(childId = 5L, packageName = "com.example.youtube", mode = "LIMIT", limit = 15))
        assertEquals(1, dao.observePolicies(1L, 5L).first().size)
        assertEquals("LIMIT", dao.getPolicy(1L, 5L, "com.example.youtube")!!.mode)

        // Different package for the same child coexists.
        dao.upsert(policy(childId = 5L, packageName = "com.example.tiktok", mode = "ALLOW"))
        assertEquals(2, dao.observePolicies(1L, 5L).first().size)
    }
}

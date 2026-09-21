package uz.faceguard.app.data.repository

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
import uz.faceguard.app.data.db.FaceGuardDatabase
import uz.faceguard.app.domain.policy.AppPolicy
import uz.faceguard.app.domain.policy.AppPolicyMode
import uz.faceguard.app.domain.policy.ProtectionAction

/**
 * Real Room-backed tests for [ChildAppPolicyRepositoryImpl]: exercises the
 * production repository path (DAO + entity/domain mapping), not a mock.
 */
@RunWith(AndroidJUnit4::class)
class ChildAppPolicyRepositoryTest {

    private lateinit var db: FaceGuardDatabase
    private lateinit var repository: ChildAppPolicyRepositoryImpl

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            FaceGuardDatabase::class.java,
        ).allowMainThreadQueries().build()
        repository = ChildAppPolicyRepositoryImpl(db.childAppPolicyDao())
    }

    @After
    fun tearDown() = db.close()

    private fun policy(
        packageName: String = "com.example.youtube",
        mode: AppPolicyMode = AppPolicyMode.BLOCK,
        action: ProtectionAction = ProtectionAction.HARD_BLOCK,
        limit: Int? = null,
        activation: Long = 0L,
        recovery: Long = 0L,
    ) = AppPolicy(
        packageName = packageName,
        mode = mode,
        action = action,
        dailyLimitMinutes = limit,
        activationDelayMs = activation,
        recoveryDelayMs = recovery,
    )

    @Test
    fun upsert_thenPolicyFor_returnsMappedDomainPolicy() = runBlocking {
        repository.upsert(1L, 5L, policy(mode = AppPolicyMode.LIMIT, action = ProtectionAction.SOFT_BLOCK, limit = 30))

        val loaded = repository.policyFor(1L, 5L, "com.example.youtube")
        assertNotNull(loaded)
        assertEquals(AppPolicyMode.LIMIT, loaded!!.mode)
        assertEquals(ProtectionAction.SOFT_BLOCK, loaded.action)
        assertEquals(30, loaded.dailyLimitMinutes)
        assertEquals(5L, loaded.childId)
    }

    @Test
    fun upsert_updatesAnExistingRowInsteadOfDuplicating() = runBlocking {
        repository.upsert(1L, 5L, policy(mode = AppPolicyMode.BLOCK))
        repository.upsert(1L, 5L, policy(mode = AppPolicyMode.ALLOW))

        assertEquals(1, repository.observePolicies(1L, 5L).first().size)
        assertEquals(AppPolicyMode.ALLOW, repository.policyFor(1L, 5L, "com.example.youtube")!!.mode)
    }

    @Test
    fun upsert_preservesCreatedAtAcrossUpdates() = runBlocking {
        repository.upsert(1L, 5L, policy(mode = AppPolicyMode.BLOCK))
        val createdAt = db.childAppPolicyDao().getPolicy(1L, 5L, "com.example.youtube")!!.createdAt

        repository.upsert(1L, 5L, policy(mode = AppPolicyMode.LIMIT, limit = 10))
        val afterUpdate = db.childAppPolicyDao().getPolicy(1L, 5L, "com.example.youtube")!!

        assertEquals(createdAt, afterUpdate.createdAt)
        assertEquals("LIMIT", afterUpdate.mode)
    }

    @Test
    fun observePolicies_emitsOnlyTheRequestedChild() = runBlocking {
        repository.upsert(1L, 5L, policy(packageName = "com.example.youtube"))
        repository.upsert(1L, 5L, policy(packageName = "com.example.tiktok"))
        repository.upsert(1L, 6L, policy(packageName = "com.example.youtube"))

        assertEquals(2, repository.observePolicies(1L, 5L).first().size)
        assertEquals(1, repository.observePolicies(1L, 6L).first().size)
    }

    @Test
    fun childPoliciesDoNotLeakAcrossChildren() = runBlocking {
        repository.upsert(1L, 5L, policy(mode = AppPolicyMode.BLOCK))
        repository.upsert(1L, 6L, policy(mode = AppPolicyMode.ALLOW))

        assertEquals(AppPolicyMode.BLOCK, repository.policyFor(1L, 5L, "com.example.youtube")!!.mode)
        assertEquals(AppPolicyMode.ALLOW, repository.policyFor(1L, 6L, "com.example.youtube")!!.mode)
    }

    @Test
    fun policiesAreScopedPerAccount() = runBlocking {
        repository.upsert(1L, 5L, policy(mode = AppPolicyMode.BLOCK))

        assertNull(repository.policyFor(2L, 5L, "com.example.youtube"))
        assertTrue(repository.observePolicies(2L, 5L).first().isEmpty())
    }

    @Test
    fun delete_removesOnlyThatPolicy() = runBlocking {
        repository.upsert(1L, 5L, policy(packageName = "com.example.youtube"))
        repository.upsert(1L, 5L, policy(packageName = "com.example.tiktok"))

        repository.delete(1L, 5L, "com.example.youtube")

        assertNull(repository.policyFor(1L, 5L, "com.example.youtube"))
        assertNotNull(repository.policyFor(1L, 5L, "com.example.tiktok"))
    }

    @Test
    fun deleteAllForChild_leavesOtherChildren() = runBlocking {
        repository.upsert(1L, 5L, policy())
        repository.upsert(1L, 6L, policy())

        repository.deleteAllForChild(1L, 5L)

        assertTrue(repository.observePolicies(1L, 5L).first().isEmpty())
        assertEquals(1, repository.observePolicies(1L, 6L).first().size)
    }
}

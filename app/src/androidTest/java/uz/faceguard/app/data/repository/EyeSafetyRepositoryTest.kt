package uz.faceguard.app.data.repository

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import uz.faceguard.app.data.db.FaceGuardDatabase
import uz.faceguard.app.domain.eyesafety.ChildEyeSafetyConfig
import uz.faceguard.app.domain.eyesafety.EyeSafetyConfig
import uz.faceguard.app.domain.eyesafety.EyeSafetyRepository
import uz.faceguard.app.domain.policy.ProtectionAction

/**
 * Phase 6 Step 3: the shipped [EyeSafetyRepository] over the real (in-memory) v10 database.
 *
 * Configuration is written and read through the repository, never the DAO, so these assert the path
 * a later configuration screen will use — including the domain round trip, absence semantics and
 * account + child isolation.
 */
@RunWith(AndroidJUnit4::class)
class EyeSafetyRepositoryTest {

    private lateinit var db: FaceGuardDatabase
    private lateinit var repository: EyeSafetyRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            FaceGuardDatabase::class.java,
        ).allowMainThreadQueries().build()
        repository = EyeSafetyRepositoryImpl(db.childEyeSafetyDao())
    }

    @After
    fun tearDown() = db.close()

    private fun domain(
        accountId: Long = 1L,
        childId: Long = 10L,
        enabled: Boolean = true,
        warningEnter: Float = 0.30f,
        warningExit: Float = 0.27f,
        dangerEnter: Float = 0.40f,
        dangerExit: Float = 0.35f,
        confirmFrames: Int = 3,
        warningAction: ProtectionAction = ProtectionAction.WARNING,
        dangerAction: ProtectionAction = ProtectionAction.SOFT_BLOCK,
        updatedAt: Long = 1_000L,
    ) = ChildEyeSafetyConfig(
        accountId = accountId,
        childId = childId,
        config = EyeSafetyConfig(
            enabled = enabled,
            warningEnterThreshold = warningEnter,
            warningExitThreshold = warningExit,
            dangerEnterThreshold = dangerEnter,
            dangerExitThreshold = dangerExit,
            confirmFrames = confirmFrames,
        ),
        warningAction = warningAction,
        dangerAction = dangerAction,
        updatedAt = updatedAt,
    )

    // ---- save / load round trip ---------------------------------------------

    @Test
    fun saveThenLoadReturnsTheWholeConfiguration() = runBlocking {
        val original = domain()

        repository.save(original)

        val loaded = repository.config(1L, 10L)!!
        assertEquals(1L, loaded.accountId)
        assertEquals(10L, loaded.childId)
        assertEquals(true, loaded.config.enabled)
        assertEquals(0.30f, loaded.config.warningEnterThreshold)
        assertEquals(0.27f, loaded.config.warningExitThreshold)
        assertEquals(0.40f, loaded.config.dangerEnterThreshold)
        assertEquals(0.35f, loaded.config.dangerExitThreshold)
        assertEquals(3, loaded.config.confirmFrames)
        assertEquals(ProtectionAction.WARNING, loaded.warningAction)
        assertEquals(ProtectionAction.SOFT_BLOCK, loaded.dangerAction)
        assertEquals(1_000L, loaded.updatedAt)
    }

    @Test
    fun everyProtectionActionSurvivesTheRepositoryRoundTrip() = runBlocking {
        ProtectionAction.entries.forEachIndexed { index, action ->
            val childId = index.toLong() + 1
            repository.save(domain(childId = childId, warningAction = action, dangerAction = action))

            val loaded = repository.config(1L, childId)!!
            assertEquals(action, loaded.warningAction)
            assertEquals(action, loaded.dangerAction)
        }
    }

    @Test
    fun ownerAndTimestampSurviveTheRoundTrip() = runBlocking {
        repository.save(domain(accountId = 4L, childId = 77L, updatedAt = 987_654L))

        val loaded = repository.config(4L, 77L)!!
        assertEquals(4L, loaded.accountId)
        assertEquals(77L, loaded.childId)
        assertEquals(987_654L, loaded.updatedAt)
    }

    @Test
    fun aDisabledConfigurationIsNotConfusedWithAbsence() = runBlocking {
        repository.save(domain(enabled = false))

        val loaded = repository.config(1L, 10L)
        assertEquals("a stored row is present even when disabled", false, loaded!!.config.enabled)
    }

    // ---- update --------------------------------------------------------------

    @Test
    fun savingAgainUpdatesInPlace() = runBlocking {
        repository.save(domain(warningEnter = 0.30f, dangerEnter = 0.40f))
        repository.save(domain(warningEnter = 0.20f, dangerEnter = 0.60f, warningAction = ProtectionAction.MUTE, updatedAt = 5_000L))

        val loaded = repository.config(1L, 10L)!!
        assertEquals(0.20f, loaded.config.warningEnterThreshold)
        assertEquals(0.60f, loaded.config.dangerEnterThreshold)
        assertEquals(ProtectionAction.MUTE, loaded.warningAction)
        assertEquals(5_000L, loaded.updatedAt)
    }

    // ---- delete + absence ----------------------------------------------------

    @Test
    fun deleteLeavesNoConfiguration() = runBlocking {
        repository.save(domain())

        repository.delete(1L, 10L)

        assertNull(repository.config(1L, 10L))
    }

    @Test
    fun deleteIsScopedToTheChild() = runBlocking {
        repository.save(domain(childId = 10L))
        repository.save(domain(childId = 11L))

        repository.delete(1L, 10L)

        assertNull(repository.config(1L, 10L))
        assertEquals(11L, repository.config(1L, 11L)!!.childId)
    }

    @Test
    fun anUnconfiguredChildReadsAsAbsence() = runBlocking {
        assertNull("no row means unconfigured", repository.config(1L, 10L))
        assertNull(repository.observeConfig(1L, 10L).first())
    }

    @Test
    fun nothingIsSeededForUnconfiguredChildren() = runBlocking {
        repository.save(domain(childId = 10L))

        // Saving one child must not create a row for its sibling.
        assertNull(repository.config(1L, 11L))
        assertNull(repository.config(2L, 10L))
    }

    // ---- isolation -----------------------------------------------------------

    @Test
    fun twoChildrenUnderOneAccountAreIsolated() = runBlocking {
        repository.save(domain(childId = 10L, warningEnter = 0.30f, dangerEnter = 0.40f))
        repository.save(domain(childId = 11L, warningEnter = 0.20f, dangerEnter = 0.50f))

        assertEquals(0.30f, repository.config(1L, 10L)!!.config.warningEnterThreshold)
        assertEquals(0.20f, repository.config(1L, 11L)!!.config.warningEnterThreshold)
    }

    @Test
    fun theSameChildIdUnderDifferentAccountsIsIsolated() = runBlocking {
        repository.save(domain(accountId = 1L, childId = 10L, warningAction = ProtectionAction.MUTE))
        repository.save(domain(accountId = 2L, childId = 10L, warningAction = ProtectionAction.HARD_BLOCK))

        assertEquals(ProtectionAction.MUTE, repository.config(1L, 10L)!!.warningAction)
        assertEquals(ProtectionAction.HARD_BLOCK, repository.config(2L, 10L)!!.warningAction)
    }

    @Test
    fun updatingOneChildNeverChangesAnother() = runBlocking {
        repository.save(domain(childId = 10L, warningEnter = 0.30f, updatedAt = 1L))
        repository.save(domain(childId = 11L, warningEnter = 0.30f, updatedAt = 1L))

        repository.save(domain(childId = 11L, warningEnter = 0.45f, updatedAt = 2L))

        val untouched = repository.config(1L, 10L)!!
        assertEquals(0.30f, untouched.config.warningEnterThreshold)
        assertEquals(1L, untouched.updatedAt)
        assertEquals(0.45f, repository.config(1L, 11L)!!.config.warningEnterThreshold)
    }

    @Test
    fun deletingOneChildNeverChangesAnother() = runBlocking {
        repository.save(domain(childId = 10L))
        repository.save(domain(childId = 11L))

        repository.delete(1L, 10L)

        assertEquals(1, countRows())
        assertEquals(11L, repository.config(1L, 11L)!!.childId)
    }

    @Test
    fun deletingAnotherAccountLeavesThisAccountAlone() = runBlocking {
        repository.save(domain(accountId = 1L, childId = 10L))

        repository.delete(2L, 10L)

        assertEquals(10L, repository.config(1L, 10L)!!.childId)
        assertEquals(1, countRows())
    }

    private fun countRows(): Int = db.openHelper.readableDatabase
        .query("SELECT COUNT(*) FROM child_eye_safety")
        .use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }

    // ---- domain validation is preserved -------------------------------------

    @Test
    fun aDomainInvalidConfigurationCannotEvenBeConstructed() {
        // The domain is the authority; the repository never sees a value the domain rejects.
        assertThrows(IllegalArgumentException::class.java) {
            ChildEyeSafetyConfig(
                accountId = 1L,
                childId = 10L,
                config = EyeSafetyConfig(
                    enabled = true,
                    warningEnterThreshold = 0.50f,
                    warningExitThreshold = 0.45f,
                    dangerEnterThreshold = 0.40f,
                    dangerExitThreshold = 0.35f,
                ),
                warningAction = ProtectionAction.WARNING,
                dangerAction = ProtectionAction.SOFT_BLOCK,
                updatedAt = 1L,
            )
        }
    }

    @Test
    fun aNonPositiveScopeIsRejectedByTheDomain() {
        assertThrows(IllegalArgumentException::class.java) { domain(accountId = 0L) }
        assertThrows(IllegalArgumentException::class.java) { domain(childId = 0L) }
    }

    // ---- observation ---------------------------------------------------------

    @Test
    fun theObservedStreamReflectsSavesAndDeletes() = runBlocking {
        assertNull(repository.observeConfig(1L, 10L).first())

        repository.save(domain(warningEnter = 0.30f))
        assertEquals(0.30f, repository.observeConfig(1L, 10L).first()!!.config.warningEnterThreshold)

        repository.save(domain(warningEnter = 0.35f))
        assertEquals(0.35f, repository.observeConfig(1L, 10L).first()!!.config.warningEnterThreshold)

        repository.delete(1L, 10L)
        assertNull(repository.observeConfig(1L, 10L).first())
    }

    @Test
    fun repeatedLoadsOfTheSameConfigurationAreIdentical() = runBlocking {
        repository.save(domain())

        val first = repository.config(1L, 10L)
        repeat(3) { assertEquals(first, repository.config(1L, 10L)) }
    }

    @Test
    fun aFullDomainRoundTripIsStable() = runBlocking {
        val original = domain(
            accountId = 3L,
            childId = 21L,
            enabled = false,
            warningEnter = 0.25f,
            warningExit = 0.20f,
            dangerEnter = 0.55f,
            dangerExit = 0.50f,
            confirmFrames = 6,
            warningAction = ProtectionAction.MUTE,
            dangerAction = ProtectionAction.HARD_BLOCK,
            updatedAt = 42L,
        )

        repository.save(original)
        val loaded = repository.config(3L, 21L)!!

        assertEquals(original, loaded)
    }
}

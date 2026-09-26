package uz.faceguard.app.data.repository

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.time.DayOfWeek
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import uz.faceguard.app.data.db.FaceGuardDatabase
import uz.faceguard.app.domain.policy.ProtectionAction
import uz.faceguard.app.domain.schedule.ScheduleDays
import uz.faceguard.app.domain.schedule.ScheduleDraft
import uz.faceguard.app.domain.schedule.ScheduleMode
import uz.faceguard.app.domain.schedule.ScheduleRepository
import uz.faceguard.app.domain.schedule.ScheduleRule
import uz.faceguard.app.domain.schedule.ScheduleWindow

/**
 * Phase 5 Step 2: the shipped [ScheduleRepository] over the real (in-memory) v9 database.
 *
 * Schedules are written and read through the repository, never the DAO, so these assert the
 * path later steps will use — including the domain round trip, account + child isolation, the
 * affected-app relation and the transactional guarantees.
 */
@RunWith(AndroidJUnit4::class)
class ScheduleRepositoryTest {

    private lateinit var db: FaceGuardDatabase
    private lateinit var repository: ScheduleRepository

    private val youtube = "com.google.android.youtube"
    private val tiktok = "com.zhiliaoapp.musically"
    private val game = "com.example.mygame"

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            FaceGuardDatabase::class.java,
        ).allowMainThreadQueries().build()
        repository = ScheduleRepositoryImpl(db, db.scheduleDao())
    }

    @After
    fun tearDown() = db.close()

    private fun draft(
        name: String = "Bedtime",
        mode: ScheduleMode = ScheduleMode.SLEEP,
        startMinuteOfDay: Int = 1320,
        endMinuteOfDay: Int = 420,
        days: ScheduleDays = ScheduleDays.of(DayOfWeek.MONDAY),
        action: ProtectionAction = ProtectionAction.HARD_BLOCK,
        priority: Int = 0,
        enabled: Boolean = true,
    ) = ScheduleDraft(
        name = name,
        mode = mode,
        window = ScheduleWindow.ofMinutes(startMinuteOfDay, endMinuteOfDay),
        days = days,
        action = action,
        priority = priority,
        enabled = enabled,
    )

    // ---- create / read ------------------------------------------------------

    @Test
    fun create_returnsAStoredRuleWithAGeneratedPositiveId() = runBlocking {
        val stored = repository.create(1L, 10L, draft(), setOf(youtube, tiktok))

        assertTrue("the id must be positive", stored.id > 0L)
        assertEquals("Bedtime", stored.name)
        assertEquals(ScheduleMode.SLEEP, stored.mode)
        assertEquals(ProtectionAction.HARD_BLOCK, stored.action)
        assertTrue(stored.enabled)
        assertEquals(1320, stored.window.startMinuteOfDay)
        assertEquals(420, stored.window.endMinuteOfDay)
        assertTrue(stored.window.crossesMidnight)
        assertEquals(ScheduleDays.of(DayOfWeek.MONDAY), stored.days)

        // And it is genuinely persisted, not just echoed back.
        assertEquals(stored, repository.schedule(1L, 10L, stored.id))
        assertEquals(
            listOf(tiktok, youtube),
            repository.targetPackages(1L, 10L, stored.id),
        )
    }

    @Test
    fun create_assignsDistinctIds() = runBlocking {
        val first = repository.create(1L, 10L, draft(name = "First"))
        val second = repository.create(1L, 10L, draft(name = "Second"))

        assertFalse("ids must not collide", first.id == second.id)
        assertEquals(listOf("First", "Second"), repository.schedules(1L, 10L).map { it.name })
    }

    @Test
    fun everyFieldSurvivesTheRoundTrip() = runBlocking {
        val stored = repository.create(
            accountId = 3L,
            childId = 12L,
            draft = draft(
                name = "School night",
                mode = ScheduleMode.SCHOOL,
                startMinuteOfDay = 1_320,
                endMinuteOfDay = 420,
                days = ScheduleDays.WEEKDAYS,
                action = ProtectionAction.MUTE,
                priority = -4,
                enabled = false,
            ),
            targetPackages = setOf(game),
        )

        val read = repository.schedule(3L, 12L, stored.id)!!
        assertEquals(stored, read)
        assertEquals("School night", read.name)
        assertEquals(ScheduleMode.SCHOOL, read.mode)
        assertEquals(ProtectionAction.MUTE, read.action)
        assertEquals(-4, read.priority)
        assertFalse(read.enabled)
        assertEquals(ScheduleDays.WEEKDAYS, read.days)
        assertEquals(1320, read.window.startMinuteOfDay)
        assertEquals(420, read.window.endMinuteOfDay)
        assertEquals(listOf(game), repository.targetPackages(3L, 12L, stored.id))
    }

    @Test
    fun observeSchedules_reflectsWrites() = runBlocking {
        repository.create(1L, 10L, draft(name = "A"))
        repository.create(1L, 10L, draft(name = "B"))

        assertEquals(listOf("A", "B"), repository.observeSchedules(1L, 10L).first().map { it.name })
    }

    // ---- update -------------------------------------------------------------

    @Test
    fun update_replacesFieldsAndTheCompleteTargetSet() = runBlocking {
        val stored = repository.create(1L, 10L, draft(name = "Bedtime"), setOf(youtube, tiktok))

        val edited = repository.update(
            accountId = 1L,
            childId = 10L,
            schedule = stored.copy(
                name = "Lights out",
                mode = ScheduleMode.CUSTOM,
                window = ScheduleWindow.ofMinutes(1_260, 400),
                days = ScheduleDays.ALL,
                action = ProtectionAction.SOFT_BLOCK,
                priority = 2,
                enabled = false,
            ),
            targetPackages = setOf(game),
        )

        assertEquals("Lights out", edited!!.name)
        assertEquals(2, edited.priority)
        assertFalse(edited.enabled)

        val read = repository.schedule(1L, 10L, stored.id)!!
        assertEquals("Lights out", read.name)
        assertEquals(ScheduleMode.CUSTOM, read.mode)
        assertEquals(ScheduleDays.ALL, read.days)
        assertEquals(ProtectionAction.SOFT_BLOCK, read.action)
        assertEquals(1260, read.window.startMinuteOfDay)
        assertEquals(400, read.window.endMinuteOfDay)
        assertEquals("the old targets are gone", listOf(game), repository.targetPackages(1L, 10L, stored.id))
    }

    @Test
    fun update_returnsNullForAnUnknownOrForeignSchedule() = runBlocking {
        val stored = repository.create(1L, 10L, draft())

        assertNull(
            "another child cannot update it",
            repository.update(1L, 11L, stored.copy(name = "Hijacked"), setOf(game)),
        )
        assertNull(
            "another account cannot update it",
            repository.update(2L, 10L, stored.copy(name = "Hijacked"), setOf(game)),
        )
        assertNull(
            "an unknown id is not found",
            repository.update(1L, 10L, stored.copy(id = 999L), setOf(game)),
        )

        assertEquals("nothing was changed", "Bedtime", repository.schedule(1L, 10L, stored.id)!!.name)
    }

    // ---- delete -------------------------------------------------------------

    @Test
    fun delete_removesTheScheduleAndItsTargets() = runBlocking {
        val stored = repository.create(1L, 10L, draft(), setOf(youtube))

        repository.delete(1L, 10L, stored.id)

        assertNull(repository.schedule(1L, 10L, stored.id))
        assertEquals("no orphaned target rows", emptyList<String>(), repository.targetPackages(1L, 10L, stored.id))
    }

    @Test
    fun delete_doesNotTouchAnotherScope() = runBlocking {
        val mine = repository.create(1L, 10L, draft(name = "Mine"))
        val sibling = repository.create(1L, 11L, draft(name = "Sibling"))

        repository.delete(1L, 10L, mine.id)

        assertEquals("Sibling", repository.schedule(1L, 11L, sibling.id)!!.name)
        assertNull(repository.schedule(1L, 10L, mine.id))
    }

    @Test
    fun deleteAllForChild_removesOnlyThatChildsSchedulesAndTargets() = runBlocking {
        repository.create(1L, 10L, draft(name = "A"), setOf(youtube))
        repository.create(1L, 10L, draft(name = "B"), setOf(tiktok))
        val sibling = repository.create(1L, 11L, draft(name = "C"), setOf(game))

        repository.deleteAllForChild(1L, 10L)

        assertEquals(emptyList<ScheduleRule>(), repository.schedules(1L, 10L))
        assertEquals(listOf("C"), repository.schedules(1L, 11L).map { it.name })
        assertEquals(listOf(game), repository.targetPackages(1L, 11L, sibling.id))
    }

    // ---- account / child isolation -----------------------------------------

    @Test
    fun accountIsolation_holdsForReadWriteAndDelete() = runBlocking {
        val stored = repository.create(1L, 10L, draft(name = "Mine"), setOf(youtube))

        assertNull("account 2 cannot read it", repository.schedule(2L, 10L, stored.id))
        assertEquals(emptyList<ScheduleRule>(), repository.schedules(2L, 10L))
        assertNull("account 2 cannot update it", repository.update(2L, 10L, stored.copy(name = "x"), emptySet()))
        repository.delete(2L, 10L, stored.id)

        assertEquals("Mine", repository.schedule(1L, 10L, stored.id)!!.name)
        assertEquals(listOf(youtube), repository.targetPackages(1L, 10L, stored.id))
    }

    @Test
    fun childIsolation_holdsForReadWriteAndDelete() = runBlocking {
        val stored = repository.create(1L, 10L, draft(name = "Mine"), setOf(youtube))

        assertNull("child 11 cannot read it", repository.schedule(1L, 11L, stored.id))
        assertEquals(emptyList<ScheduleRule>(), repository.schedules(1L, 11L))
        assertNull("child 11 cannot update it", repository.update(1L, 11L, stored.copy(name = "x"), emptySet()))
        repository.delete(1L, 11L, stored.id)

        assertEquals("Mine", repository.schedule(1L, 10L, stored.id)!!.name)
        assertEquals(listOf(youtube), repository.targetPackages(1L, 10L, stored.id))
    }

    @Test
    fun severalAccountsAndChildrenCoexist() = runBlocking {
        val aA = repository.create(1L, 10L, draft(name = "A/A"), setOf(youtube))
        val aB = repository.create(1L, 11L, draft(name = "A/B"), setOf(tiktok))
        val bA = repository.create(2L, 10L, draft(name = "B/A"), setOf(game))

        assertEquals("A/A", repository.schedule(1L, 10L, aA.id)!!.name)
        assertEquals("A/B", repository.schedule(1L, 11L, aB.id)!!.name)
        assertEquals("B/A", repository.schedule(2L, 10L, bA.id)!!.name)
        assertEquals(listOf("A/A"), repository.schedules(1L, 10L).map { it.name })
        assertEquals(listOf("A/B"), repository.schedules(1L, 11L).map { it.name })
        assertEquals(listOf("B/A"), repository.schedules(2L, 10L).map { it.name })
        assertEquals(listOf(youtube), repository.targetPackages(1L, 10L, aA.id))
        assertEquals(listOf(tiktok), repository.targetPackages(1L, 11L, aB.id))
        assertEquals(listOf(game), repository.targetPackages(2L, 10L, bA.id))
    }

    // ---- affected-app membership -------------------------------------------

    @Test
    fun replaceTargetPackages_replacesTheWholeSetIncludingEmpty() = runBlocking {
        val stored = repository.create(1L, 10L, draft(), setOf(youtube, tiktok))

        repository.replaceTargetPackages(1L, 10L, stored.id, setOf(game))
        assertEquals(listOf(game), repository.targetPackages(1L, 10L, stored.id))

        repository.replaceTargetPackages(1L, 10L, stored.id, emptySet())
        assertEquals("an empty target set is a valid configuration", emptyList<String>(), repository.targetPackages(1L, 10L, stored.id))
    }

    @Test
    fun replaceTargetPackages_doesNothingForANonOwnedSchedule() = runBlocking {
        val stored = repository.create(1L, 10L, draft(), setOf(youtube))

        repository.replaceTargetPackages(1L, 11L, stored.id, setOf(game))
        repository.replaceTargetPackages(2L, 10L, stored.id, setOf(game))
        repository.replaceTargetPackages(1L, 10L, 999L, setOf(game))

        assertEquals("the owner's targets are unchanged", listOf(youtube), repository.targetPackages(1L, 10L, stored.id))
        assertEquals("no rows were filed under another child", emptyList<String>(), repository.targetPackages(1L, 11L, stored.id))
        assertEquals("no rows were filed under another account", emptyList<String>(), repository.targetPackages(2L, 10L, stored.id))
        assertEquals("no orphan rows for an unknown schedule", emptyList<String>(), repository.targetPackages(1L, 10L, 999L))
    }

    // ---- validation ---------------------------------------------------------

    /** Asserts a suspend operation is rejected with [IllegalArgumentException] and wrote nothing. */
    private suspend fun assertRejected(block: suspend () -> Unit) {
        val result = runCatching { block() }
        assertTrue("expected the operation to be rejected, but it succeeded", result.isFailure)
        assertTrue(
            "expected IllegalArgumentException, was ${result.exceptionOrNull()}",
            result.exceptionOrNull() is IllegalArgumentException,
        )
    }

    @Test
    fun blankNameIsRejectedBeforeAnythingIsWritten() = runBlocking {
        assertRejected { repository.create(1L, 10L, draft(name = "   "), setOf(youtube)) }
        assertEquals(emptyList<ScheduleRule>(), repository.schedules(1L, 10L))
    }

    @Test
    fun aDomainOnlyActionIsRejected() = runBlocking {
        // DIM exists in the action enum but is not in IMPLEMENTED_ACTIONS, so it is not a valid
        // schedule action: the Step 1 constructor rejects it when the draft is realised.
        assertRejected { repository.create(1L, 10L, draft(action = ProtectionAction.DIM), emptySet()) }
        assertEquals(emptyList<ScheduleRule>(), repository.schedules(1L, 10L))
    }

    @Test
    fun blankTargetPackageIsRejectedAndWritesNothing() = runBlocking {
        assertRejected { repository.create(1L, 10L, draft(), setOf(youtube, "  ")) }
        assertEquals(emptyList<ScheduleRule>(), repository.schedules(1L, 10L))
    }

    @Test
    fun duplicateTargetPackagesCollapseToTheSameRelation() = runBlocking {
        // Set semantics make the duplicate impossible by construction; the stored rows and the
        // read-back must therefore contain each package exactly once.
        val stored = repository.create(1L, 10L, draft(), setOf(youtube, youtube, tiktok))

        val storedTargets = repository.targetPackages(1L, 10L, stored.id)
        assertEquals(listOf(tiktok, youtube), storedTargets)
        assertEquals(2, storedTargets.size)
    }

    @Test
    fun nonPositiveScopeIsRejected() = runBlocking {
        assertRejected { repository.create(0L, 10L, draft()) }
        assertRejected { repository.create(1L, 0L, draft()) }
        assertRejected { repository.delete(0L, 10L, 1L) }
        assertRejected { repository.replaceTargetPackages(1L, 0L, 1L, setOf(youtube)) }
    }

    // ---- atomicity ----------------------------------------------------------

    /**
     * A real failure is injected *inside* the production transaction with a SQLite trigger that
     * aborts the target insert. If create's two writes were not one transaction, the schedule
     * row would survive a failed target write; it must not.
     */
    @Test
    fun create_isAtomicWhenTheTargetWriteFails() = runBlocking {
        failInsertsOf(failingPackage)

        val failure = runCatching {
            repository.create(1L, 10L, draft(name = "Half written"), setOf(youtube, failingPackage))
        }

        assertTrue("the failing insert must surface", failure.isFailure)
        assertEquals(
            "no half-written schedule may remain",
            emptyList<ScheduleRule>(),
            repository.schedules(1L, 10L),
        )
    }

    /**
     * The same injection on update: neither the new metadata nor the new target set may be
     * visible. The previous schedule and its previous targets must be exactly as they were.
     */
    @Test
    fun update_isAtomicWhenTheTargetWriteFails() = runBlocking {
        val stored = repository.create(1L, 10L, draft(name = "Original"), setOf(youtube))
        failInsertsOf(failingPackage)

        val failure = runCatching {
            repository.update(
                accountId = 1L,
                childId = 10L,
                schedule = stored.copy(name = "Should not stick"),
                targetPackages = setOf(tiktok, failingPackage),
            )
        }

        assertTrue("the failing insert must surface", failure.isFailure)
        val read = repository.schedule(1L, 10L, stored.id)!!
        assertEquals("the old metadata is intact", "Original", read.name)
        assertEquals("the old target set is intact", listOf(youtube), repository.targetPackages(1L, 10L, stored.id))
    }

    /** Aborts any insert of [packageName] into the target table, inside whatever transaction. */
    private fun failInsertsOf(packageName: String) {
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_target_insert BEFORE INSERT ON schedule_app_targets " +
                "WHEN NEW.packageName = '$packageName' " +
                "BEGIN SELECT RAISE(ABORT, 'injected failure'); END",
        )
    }

    private val failingPackage = "com.injected.failure"
}

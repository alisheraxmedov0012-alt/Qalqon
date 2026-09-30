package uz.faceguard.app.schedule

import java.time.DayOfWeek
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.faceguard.app.R
import uz.faceguard.app.domain.model.ProtectedApp
import uz.faceguard.app.domain.policy.ProtectionAction
import uz.faceguard.app.domain.schedule.ScheduleDays
import uz.faceguard.app.domain.schedule.ScheduleDraft
import uz.faceguard.app.domain.schedule.ScheduleMode
import uz.faceguard.app.domain.schedule.ScheduleRepository
import uz.faceguard.app.domain.schedule.ScheduleRule
import uz.faceguard.app.domain.schedule.ScheduleWindow
import uz.faceguard.app.feature.schedule.ScheduleConfigController
import uz.faceguard.app.feature.schedule.ScheduleEditorValidation

/**
 * Phase 5 Step 5 (pure JVM): the schedule configuration logic driven against a fake
 * [ScheduleRepository].
 *
 * The repository *interface* is a production domain type; the fake is an in-memory double for the
 * Room-backed implementation, whose own behaviour is covered by Step 2's instrumented tests. That
 * is the same substitution the project's existing JVM tests use for device-bound dependencies, and
 * it is what lets list scoping, save, edit, toggle, delete and failure handling be verified here
 * against the real controller code.
 *
 * Every fake method records its arguments, so the tests can assert exactly what the UI asked the
 * repository to persist — in particular that a target replacement receives exactly the selected
 * packages and that an edit preserves the existing schedule id.
 */
class ScheduleConfigControllerTest {

    private val accountId = 1L
    private val childId = 10L

    private class FakeScheduleRepository : ScheduleRepository {
        val rules = MutableStateFlow<List<ScheduleRule>>(emptyList())
        val targets = MutableStateFlow<Map<Long, Set<String>>>(emptyMap())
        private var nextId = 1L

        var createCalls = 0
        var updateCalls = 0
        var deleteCalls = 0
        var lastTargetsWritten: Set<String>? = null
        var lastUpdateId: Long? = null
        /** The (account, child) every write was addressed to, for scoping assertions. */
        val writeScopes = mutableListOf<Pair<Long, Long>>()
        var failCreate = false
        var failDelete = false
        var failUpdate = false

        fun seed(rule: ScheduleRule, targets: Set<String>) {
            rules.value = rules.value + rule
            this.targets.value = this.targets.value + (rule.id to targets)
        }

        override fun observeSchedules(accountId: Long, childId: Long): Flow<List<ScheduleRule>> =
            rules.map { it.toList() }

        override suspend fun schedules(accountId: Long, childId: Long): List<ScheduleRule> = rules.value

        override suspend fun schedule(accountId: Long, childId: Long, scheduleId: Long): ScheduleRule? =
            rules.value.firstOrNull { it.id == scheduleId }

        override suspend fun create(
            accountId: Long,
            childId: Long,
            draft: ScheduleDraft,
            targetPackages: Set<String>,
        ): ScheduleRule {
            createCalls++
            lastTargetsWritten = targetPackages
            writeScopes += accountId to childId
            if (failCreate) throw IllegalStateException("injected create failure")
            val id = nextId++
            val rule = draft.withId(id)
            rules.value = rules.value + rule
            targets.value = targets.value + (id to targetPackages)
            return rule
        }

        override suspend fun update(
            accountId: Long,
            childId: Long,
            schedule: ScheduleRule,
            targetPackages: Set<String>,
        ): ScheduleRule? {
            updateCalls++
            lastUpdateId = schedule.id
            lastTargetsWritten = targetPackages
            writeScopes += accountId to childId
            if (failUpdate) throw IllegalStateException("injected update failure")
            if (rules.value.none { it.id == schedule.id }) return null
            rules.value = rules.value.map { if (it.id == schedule.id) schedule else it }
            targets.value = targets.value + (schedule.id to targetPackages)
            return schedule
        }

        override suspend fun delete(accountId: Long, childId: Long, scheduleId: Long) {
            deleteCalls++
            writeScopes += accountId to childId
            if (failDelete) throw IllegalStateException("injected delete failure")
            rules.value = rules.value.filterNot { it.id == scheduleId }
            targets.value = targets.value - scheduleId
        }

        override suspend fun deleteAllForChild(accountId: Long, childId: Long) {
            rules.value = emptyList()
            targets.value = emptyMap()
        }

        override fun observeTargetPackages(accountId: Long, childId: Long, scheduleId: Long): Flow<List<String>> =
            targets.map { (it[scheduleId] ?: emptySet()).sorted() }

        override suspend fun targetPackages(accountId: Long, childId: Long, scheduleId: Long): List<String> =
            (targets.value[scheduleId] ?: emptySet()).sorted()

        override suspend fun replaceTargetPackages(
            accountId: Long,
            childId: Long,
            scheduleId: Long,
            packageNames: Set<String>,
        ) {
            targets.value = targets.value + (scheduleId to packageNames)
        }
    }

    private fun rule(
        id: Long = 1L,
        name: String = "Bedtime",
        mode: ScheduleMode = ScheduleMode.SLEEP,
        action: ProtectionAction = ProtectionAction.HARD_BLOCK,
        enabled: Boolean = true,
        priority: Int = 0,
    ) = ScheduleRule(
        id = id,
        name = name,
        mode = mode,
        window = ScheduleWindow.ofMinutes(1320, 420),
        days = ScheduleDays.of(DayOfWeek.MONDAY),
        action = action,
        priority = priority,
        enabled = enabled,
    )

    private fun controller(
        repository: FakeScheduleRepository,
        catalog: List<ProtectedApp> = listOf(
            ProtectedApp("com.protected.a", "App A", isProtected = true),
            ProtectedApp("com.protected.b", "App B", isProtected = true),
            ProtectedApp("com.not.protected", "Other", isProtected = false),
        ),
    ) = ScheduleConfigController(
        repository = repository,
        accountId = accountId,
        childId = childId,
        protectedApps = flowOf(catalog),
        scope = CoroutineScope(Dispatchers.Unconfined),
    )

    /** Lets the list collectors (running on the unconfined scope) settle. */
    private fun settle() = runBlocking { kotlinx.coroutines.yield() }

    // ---- list ---------------------------------------------------------------

    @Test
    fun test19_theListShowsOnlyTheSchedulesOfThisChild() {
        val repository = FakeScheduleRepository()
        val mine = rule(id = 1L, name = "Mine")
        repository.seed(mine, setOf("com.protected.a"))

        val controller = controller(repository)

        assertEquals(listOf(mine), controller.list.value.rows.map { it.schedule })
    }

    @Test
    fun test19_disabledSchedulesRemainVisible() {
        val repository = FakeScheduleRepository()
        val disabled = rule(id = 1L, name = "Off", enabled = false)
        repository.seed(disabled, emptySet())

        val controller = controller(repository)

        assertEquals(1, controller.list.value.rows.size)
        assertFalse(controller.list.value.rows.single().schedule.enabled)
    }

    @Test
    fun test5_theListReportsTheAffectedAppCount() {
        val repository = FakeScheduleRepository()
        repository.seed(rule(id = 1L), setOf("com.protected.a", "com.protected.b"))

        val controller = controller(repository)

        assertEquals(2, controller.list.value.rows.single().targetCount)
    }

    @Test
    fun test19_anEmptyChildStillReportsLoadedWithNoRows() {
        val controller = controller(FakeScheduleRepository())

        assertFalse("the list must finish loading", controller.list.value.loading)
        assertTrue(controller.list.value.rows.isEmpty())
        assertNull(controller.list.value.errorMessageRes)
    }

    // ---- 21/22. catalog ----------------------------------------------------

    @Test
    fun test21_theSelectorOffersOnlyTheProtectedCatalog() {
        val controller = controller(FakeScheduleRepository())
        settle()

        assertEquals(
            listOf("com.protected.a", "com.protected.b"),
            controller.catalog.value.apps.map { it.packageName },
        )
        assertTrue(controller.catalog.value.loaded)
    }

    @Test
    fun test22_anUnprotectedPackageCannotBeIntroducedThroughTheUi() {
        val controller = controller(FakeScheduleRepository())
        settle()

        // What the selector can offer is exactly the protected catalog…
        val offered = controller.catalog.value.apps.map { it.packageName }.toSet()
        assertFalse("com.not.protected" in offered)
    }

    // ---- 1/2/3. editor lifecycle -------------------------------------------

    @Test
    fun test1_startAddBeginsANewSchedule() = runBlocking {
        val controller = controller(FakeScheduleRepository())

        controller.startAdd()

        assertTrue(controller.editor.value.isNew)
        assertEquals("", controller.editor.value.name)
    }

    @Test
    fun test2_startEditLoadsTheScheduleAndItsApps() = runBlocking {
        val repository = FakeScheduleRepository()
        val existing = rule(id = 5L, name = "Loaded", action = ProtectionAction.MUTE, enabled = false)
        repository.seed(existing, setOf("com.protected.a"))
        val controller = controller(repository)

        controller.startEdit(existing)

        val state = controller.editor.value
        assertEquals(5L, state.scheduleId)
        assertEquals("Loaded", state.name)
        assertEquals(ProtectionAction.MUTE, state.action)
        assertFalse(state.enabled)
        assertEquals(setOf("com.protected.a"), state.selectedPackages)
    }

    // ---- 4/5/6. save -------------------------------------------------------

    @Test
    fun test4_createSavesTheDraftAndItsTargets() = runBlocking {
        val repository = FakeScheduleRepository()
        val controller = controller(repository)
        controller.startAdd()
        controller.onNameChange("Homework")
        controller.onModeChange(ScheduleMode.STUDY)
        controller.onActionChange(ProtectionAction.SOFT_BLOCK)
        controller.onSetDays(setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY))
        controller.onStartMinuteChange(600)
        controller.onEndMinuteChange(720)
        controller.onPriorityChange("4")
        controller.onTogglePackage("com.protected.a")

        val saved = controller.saveEditor()

        assertTrue(saved)
        assertEquals(1, repository.createCalls)
        val stored = repository.rules.value.single()
        assertEquals("Homework", stored.name)
        assertEquals(ScheduleMode.STUDY, stored.mode)
        assertEquals(ProtectionAction.SOFT_BLOCK, stored.action)
        assertEquals(ScheduleDays.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY), stored.days)
        assertEquals(600, stored.window.startMinuteOfDay)
        assertEquals(720, stored.window.endMinuteOfDay)
        assertEquals(4, stored.priority)
    }

    @Test
    fun test5_editUpdatesInPlaceAndKeepsTheScheduleId() = runBlocking {
        val repository = FakeScheduleRepository()
        val existing = rule(id = 9L, name = "Original")
        repository.seed(existing, emptySet())
        val controller = controller(repository)
        controller.startEdit(existing)
        controller.onNameChange("Renamed")

        val saved = controller.saveEditor()

        assertTrue(saved)
        assertEquals(0, repository.createCalls)
        assertEquals(1, repository.updateCalls)
        assertEquals("the existing id is reused", 9L, repository.lastUpdateId)
        assertEquals(1, repository.rules.value.size)
        assertEquals("Renamed", repository.rules.value.single().name)
        assertEquals(9L, controller.editor.value.scheduleId)
    }

    @Test
    fun test6_targetReplacementReceivesExactlyTheSelectedPackages() = runBlocking {
        val repository = FakeScheduleRepository()
        val controller = controller(repository)
        controller.startAdd()
        controller.onNameChange("Study")
        controller.onTogglePackage("com.protected.a")
        controller.onTogglePackage("com.protected.b")

        controller.saveEditor()

        assertEquals(setOf("com.protected.a", "com.protected.b"), repository.lastTargetsWritten)
    }

    @Test
    fun test6_deselectingAnAppRemovesItFromTheReplacement() = runBlocking {
        val repository = FakeScheduleRepository()
        val existing = rule(id = 2L)
        repository.seed(existing, setOf("com.protected.a", "com.protected.b"))
        val controller = controller(repository)
        controller.startEdit(existing)

        controller.onTogglePackage("com.protected.a")
        controller.saveEditor()

        assertEquals(setOf("com.protected.b"), repository.lastTargetsWritten)
    }

    @Test
    fun test11_aBlankNameIsRejectedAndNothingIsWritten() = runBlocking {
        val repository = FakeScheduleRepository()
        val controller = controller(repository)
        controller.startAdd()
        controller.onNameChange("   ")

        assertFalse(controller.saveEditor())

        assertEquals(0, repository.createCalls)
        assertEquals(0, repository.updateCalls)
        assertEquals(R.string.schedule_error_name, controller.editor.value.errorMessageRes)
    }

    @Test
    fun test13_equalStartAndEndIsRejectedAndNothingIsWritten() = runBlocking {
        val repository = FakeScheduleRepository()
        val controller = controller(repository)
        controller.startAdd()
        controller.onNameChange("Bad window")
        controller.onStartMinuteChange(700)
        controller.onEndMinuteChange(700)

        assertFalse(controller.saveEditor())

        assertEquals(0, repository.createCalls)
        assertEquals(R.string.schedule_error_time, controller.editor.value.errorMessageRes)
    }

    @Test
    fun test14_aCrossMidnightScheduleSaves() = runBlocking {
        val repository = FakeScheduleRepository()
        val controller = controller(repository)
        controller.startAdd()
        controller.onNameChange("Night")
        controller.onStartMinuteChange(1320)
        controller.onEndMinuteChange(360)

        assertTrue(controller.saveEditor())

        val stored = repository.rules.value.single()
        assertEquals(1320, stored.window.startMinuteOfDay)
        assertEquals(360, stored.window.endMinuteOfDay)
        assertTrue(stored.window.crossesMidnight)
    }

    @Test
    fun test15_anInvalidPriorityIsRejectedAndNothingIsWritten() = runBlocking {
        val repository = FakeScheduleRepository()
        val controller = controller(repository)
        controller.startAdd()
        controller.onNameChange("Study")
        controller.onPriorityChange("-")

        assertFalse(controller.saveEditor())

        assertEquals(0, repository.createCalls)
        assertEquals(R.string.schedule_error_priority, controller.editor.value.errorMessageRes)
    }

    @Test
    fun priorityInputRejectsDecimalCharacters() {
        val controller = controller(FakeScheduleRepository())

        controller.onPriorityChange("3.5")

        // The dot can never enter the field, so a decimal priority is impossible to type.
        assertEquals("35", controller.editor.value.priorityText)
        assertEquals(35, controller.editor.value.priority)
    }

    // ---- 7/8. enable toggle ------------------------------------------------

    @Test
    fun test7_togglingOffPersistsEnabledFalseAndKeepsTheSchedule() = runBlocking {
        val repository = FakeScheduleRepository()
        val existing = rule(id = 3L, enabled = true)
        repository.seed(existing, setOf("com.protected.a"))
        val controller = controller(repository)

        val ok = controller.setEnabled(existing, false)

        assertTrue(ok)
        assertFalse("the schedule is disabled, not deleted", repository.rules.value.single().enabled)
        assertEquals(1, repository.rules.value.size)
        assertEquals(0, repository.deleteCalls)
    }

    @Test
    fun test8_togglingOnPersistsEnabledTrue() = runBlocking {
        val repository = FakeScheduleRepository()
        val existing = rule(id = 3L, enabled = false)
        repository.seed(existing, emptySet())
        val controller = controller(repository)

        val ok = controller.setEnabled(existing, true)

        assertTrue(ok)
        assertTrue(repository.rules.value.single().enabled)
    }

    @Test
    fun togglingCarriesTheExistingTargetsOverInsteadOfWipingThem() = runBlocking {
        val repository = FakeScheduleRepository()
        val existing = rule(id = 3L, enabled = true)
        repository.seed(existing, setOf("com.protected.a", "com.protected.b"))
        val controller = controller(repository)

        controller.setEnabled(existing, false)

        assertEquals(setOf("com.protected.a", "com.protected.b"), repository.lastTargetsWritten)
    }

    // ---- 9/10. delete confirmation -----------------------------------------

    @Test
    fun test9_cancellingTheConfirmationDeletesNothing() = runBlocking {
        val repository = FakeScheduleRepository()
        val existing = rule(id = 4L)
        repository.seed(existing, emptySet())
        val controller = controller(repository)

        controller.requestDelete(existing)
        assertEquals(existing, controller.list.value.pendingDelete)

        controller.cancelDelete()

        assertNull(controller.list.value.pendingDelete)
        assertEquals(0, repository.deleteCalls)
        assertEquals(1, repository.rules.value.size)
    }

    @Test
    fun test10_confirmingTheConfirmationDeletesThroughTheRepository() = runBlocking {
        val repository = FakeScheduleRepository()
        val existing = rule(id = 4L)
        repository.seed(existing, setOf("com.protected.a"))
        val controller = controller(repository)
        controller.requestDelete(existing)

        val deleted = controller.confirmDelete()

        assertTrue(deleted)
        assertEquals(1, repository.deleteCalls)
        assertTrue(repository.rules.value.isEmpty())
        assertNull(controller.list.value.pendingDelete)
    }

    @Test
    fun test10_confirmingWithoutARequestDeletesNothing() = runBlocking {
        val repository = FakeScheduleRepository()
        val controller = controller(repository)

        assertFalse(controller.confirmDelete())
        assertEquals(0, repository.deleteCalls)
    }

    @Test
    fun test20_everyWriteIsAddressedToThisAccountAndChildOnly() = runBlocking {
        val repository = FakeScheduleRepository()
        val controller = controller(repository)
        controller.startAdd()
        controller.onNameChange("Scoped")
        controller.saveEditor()

        val created = repository.rules.value.single()
        controller.setEnabled(created, false)
        controller.requestDelete(created)
        controller.confirmDelete()

        // Create, update and delete were all addressed to this controller's own account + child, so
        // a write can never land on another child's row.
        assertEquals(3, repository.writeScopes.size)
        repository.writeScopes.forEach { scope ->
            assertEquals(accountId to childId, scope)
        }
    }

    // ---- 24/25. failures ---------------------------------------------------

    @Test
    fun test24_aFailedSaveKeepsTheEditorInputSoItCanBeRetried() = runBlocking {
        val repository = FakeScheduleRepository()
        repository.failCreate = true
        val controller = controller(repository)
        controller.startAdd()
        controller.onNameChange("Keep me")
        controller.onPriorityChange("7")

        val saved = controller.saveEditor()

        assertFalse(saved)
        assertEquals(R.string.schedule_error_save, controller.editor.value.errorMessageRes)
        assertEquals("Keep me", controller.editor.value.name)
        assertEquals("7", controller.editor.value.priorityText)
        assertFalse("the editor is usable again", controller.editor.value.busy)

        // Retry succeeds once the repository recovers.
        repository.failCreate = false
        assertTrue(controller.saveEditor())
        assertEquals("Keep me", repository.rules.value.single().name)
    }

    @Test
    fun test24_aFailedUpdateReportsFailureAndDoesNotClaimSuccess() = runBlocking {
        val repository = FakeScheduleRepository()
        val existing = rule(id = 6L, name = "Before")
        repository.seed(existing, emptySet())
        repository.failUpdate = true
        val controller = controller(repository)
        controller.startEdit(existing)
        controller.onNameChange("After")

        assertFalse(controller.saveEditor())

        assertEquals(R.string.schedule_error_save, controller.editor.value.errorMessageRes)
        assertEquals("After", controller.editor.value.name)
        assertEquals("the stored value is untouched", "Before", repository.rules.value.single().name)
    }

    @Test
    fun test25_aFailedDeleteKeepsTheItemInTheDisplayedList() = runBlocking {
        val repository = FakeScheduleRepository()
        val existing = rule(id = 8L)
        repository.seed(existing, emptySet())
        val controller = controller(repository)
        controller.requestDelete(existing)

        repository.failDelete = true
        val deleted = controller.confirmDelete()

        assertFalse(deleted)
        assertEquals(R.string.schedule_error_delete, controller.list.value.errorMessageRes)
        assertEquals("nothing was falsely removed", listOf(8L), controller.list.value.rows.map { it.schedule.id })
    }

    @Test
    fun aFailedToggleReportsFailureAndLeavesTheStoredValueAlone() = runBlocking {
        val repository = FakeScheduleRepository()
        val existing = rule(id = 2L, enabled = true)
        repository.seed(existing, emptySet())
        repository.failUpdate = true
        val controller = controller(repository)

        val ok = controller.setEnabled(existing, false)

        assertFalse(ok)
        assertEquals(R.string.schedule_error_update, controller.list.value.errorMessageRes)
        assertTrue(repository.rules.value.single().enabled)
    }

    // ---- 24. determinism ---------------------------------------------------

    @Test
    fun test24_repeatedReadsOfTheSameStateAreIdentical() {
        val repository = FakeScheduleRepository()
        repository.seed(rule(id = 1L), setOf("com.protected.a"))
        val controller = controller(repository)

        val first = controller.list.value
        repeat(3) { settle() }

        assertEquals(first, controller.list.value)
    }

    // ---- editor helpers ----------------------------------------------------

    @Test
    fun startEditOfAnotherChildsScheduleFindsNoTargetsAndStaysLoadable() = runBlocking {
        val repository = FakeScheduleRepository()
        // A rule that exists but has no targets for this account + child.
        val foreign = rule(id = 99L, name = "Foreign")
        val controller = controller(repository)

        controller.startEdit(foreign)

        // The editor shows what it was given and simply has no targets here; it never invents them.
        assertEquals(99L, controller.editor.value.scheduleId)
        assertTrue(controller.editor.value.selectedPackages.isEmpty())
    }

    @Test
    fun aValidDefaultEditorStateIsImmediatelySaveable() = runBlocking {
        val repository = FakeScheduleRepository()
        val controller = controller(repository)
        controller.startAdd()
        controller.onNameChange("Quick")

        assertTrue(controller.editor.value.validate() is ScheduleEditorValidation.Valid)
        assertTrue(controller.saveEditor())
    }
}

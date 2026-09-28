package uz.faceguard.app.eyesafety

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.faceguard.app.R
import uz.faceguard.app.domain.eyesafety.ChildEyeSafetyConfig
import uz.faceguard.app.domain.eyesafety.EyeSafetyConfig
import uz.faceguard.app.domain.eyesafety.EyeSafetyRepository
import uz.faceguard.app.domain.policy.ProtectionAction
import uz.faceguard.app.feature.eyesafety.EyeSafetyConfigController

/**
 * Phase 6 Step 5 (pure JVM): the eye-safety configuration logic driven against a fake
 * [EyeSafetyRepository].
 *
 * The repository *interface* is a production domain type; the fake is an in-memory double for the
 * Room-backed implementation, whose own behaviour is covered by the Phase 6 Step 3 instrumented
 * tests. That is the same substitution the project's existing JVM tests use for device-bound
 * dependencies, and it is what lets load, unconfigured state, validation, save, both failure paths
 * and child isolation be verified here against the real controller code.
 *
 * Every fake method records its arguments, so these can assert exactly what the UI asked to persist
 * — in particular that a save is addressed to this controller's own account + child.
 */
class EyeSafetyConfigControllerTest {

    private val accountId = 1L
    private val childA = 10L
    private val childB = 11L

    private class FakeEyeSafetyRepository : EyeSafetyRepository {
        /** Rows keyed exactly like the real table: account + child. */
        val rows = mutableMapOf<Pair<Long, Long>, ChildEyeSafetyConfig>()

        var loadCalls = 0
        var saveCalls = 0
        var lastSaveScope: Pair<Long, Long>? = null
        val savedModels = mutableListOf<ChildEyeSafetyConfig>()
        var failLoad = false
        var failSave = false

        private fun key(accountId: Long, childId: Long) = accountId to childId

        fun seed(accountId: Long, childId: Long, model: ChildEyeSafetyConfig) {
            rows[key(accountId, childId)] = model
        }

        override suspend fun config(accountId: Long, childId: Long): ChildEyeSafetyConfig? {
            loadCalls++
            if (failLoad) throw IllegalStateException("injected load failure")
            return rows[key(accountId, childId)]
        }

        override fun observeConfig(accountId: Long, childId: Long): Flow<ChildEyeSafetyConfig?> =
            MutableStateFlow(rows[key(accountId, childId)]).map { it }

        override suspend fun save(config: ChildEyeSafetyConfig) {
            saveCalls++
            lastSaveScope = config.accountId to config.childId
            if (failSave) throw IllegalStateException("injected save failure")
            savedModels += config
            rows[key(config.accountId, config.childId)] = config
        }

        override suspend fun delete(accountId: Long, childId: Long) {
            rows.remove(key(accountId, childId))
        }
    }

    /**
     * A stored configuration. Exit thresholds default to a valid value *relative to* the enter
     * thresholds, so overriding one enter threshold in a test cannot accidentally build a
     * configuration the domain rejects.
     */
    private fun model(
        accountId: Long = this.accountId,
        childId: Long = childA,
        enabled: Boolean = true,
        warningEnter: Float = 0.30f,
        warningExit: Float? = null,
        dangerEnter: Float = 0.40f,
        dangerExit: Float? = null,
        confirmFrames: Int = 3,
        warningAction: ProtectionAction = ProtectionAction.WARNING,
        dangerAction: ProtectionAction = ProtectionAction.SOFT_BLOCK,
    ): ChildEyeSafetyConfig {
        val resolvedWarningExit = warningExit ?: (warningEnter - 0.03f).coerceAtLeast(0f)
        val resolvedDangerExit = dangerExit ?: (dangerEnter - 0.05f).coerceAtLeast(resolvedWarningExit)
        return ChildEyeSafetyConfig(
            accountId = accountId,
            childId = childId,
            config = EyeSafetyConfig(
                enabled = enabled,
                warningEnterThreshold = warningEnter,
                warningExitThreshold = resolvedWarningExit,
                dangerEnterThreshold = dangerEnter,
                dangerExitThreshold = resolvedDangerExit,
                confirmFrames = confirmFrames,
            ),
            warningAction = warningAction,
            dangerAction = dangerAction,
            updatedAt = 1_000L,
        )
    }

    private fun controller(
        repository: FakeEyeSafetyRepository,
        childId: Long = childA,
        accountId: Long = this.accountId,
        clock: () -> Long = { 5_000L },
    ) = EyeSafetyConfigController(
        repository = repository,
        accountId = accountId,
        childId = childId,
        clock = clock,
        scope = CoroutineScope(Dispatchers.Unconfined),
    )

    // ---- A. initial unconfigured state --------------------------------------

    @Test
    fun a_anUnconfiguredChildLoadsAsUnconfiguredWithoutSaving() {
        val repository = FakeEyeSafetyRepository()

        val controller = controller(repository)

        val state = controller.state.value
        assertFalse("the load has completed", state.loading)
        assertFalse("no row exists, so the child is unconfigured", state.configured)
        assertNull(state.loadErrorMessageRes)
        assertFalse("eye safety must not be pre-enabled", state.editor.enabled)
        assertEquals("opening the screen must not write anything", 0, repository.saveCalls)
        assertTrue("and it must not create a row", repository.rows.isEmpty())
    }

    @Test
    fun a_anUnconfiguredChildShowsTheDomainDefaults() {
        val controller = controller(FakeEyeSafetyRepository())

        val editor = controller.state.value.editor
        assertEquals(
            (EyeSafetyConfig.DEFAULT_WARNING_ENTER_THRESHOLD * 100f).toInt(),
            editor.warningEnterPercent,
        )
        assertEquals(EyeSafetyConfig.DEFAULT_CONFIRM_FRAMES, editor.confirmFrames)
    }

    @Test
    fun a_theRepositoryIsReadOnceAndNotOnEveryInteraction() {
        val repository = FakeEyeSafetyRepository()
        val controller = controller(repository)

        controller.onEnabledChange(true)
        controller.onWarningEnterChange("3")
        controller.onDangerActionChange(ProtectionAction.MUTE)

        assertEquals("the form never re-queries the repository while editing", 1, repository.loadCalls)
    }

    // ---- B. existing enabled configuration ----------------------------------

    @Test
    fun b_anExistingConfigurationLoadsEveryValue() {
        val repository = FakeEyeSafetyRepository()
        repository.seed(
            accountId,
            childA,
            model(warningEnter = 0.22f, dangerEnter = 0.55f, confirmFrames = 4, warningAction = ProtectionAction.MUTE),
        )

        val state = controller(repository).state.value

        assertTrue(state.configured)
        assertEquals("22", state.editor.warningEnterPercentText)
        assertEquals("55", state.editor.dangerEnterPercentText)
        assertEquals("4", state.editor.confirmFramesText)
        assertEquals(ProtectionAction.MUTE, state.editor.warningAction)
        assertTrue(state.editor.enabled)
    }

    // ---- C. existing disabled configuration ---------------------------------

    @Test
    fun c_aDisabledConfigurationLoadsAsDisabledWithItsValuesIntact() {
        val repository = FakeEyeSafetyRepository()
        repository.seed(accountId, childA, model(enabled = false, warningEnter = 0.35f, dangerEnter = 0.60f))

        val state = controller(repository).state.value

        assertTrue("a stored row means configured", state.configured)
        assertFalse("but it is switched off", state.editor.enabled)
        assertEquals("the values are preserved", "35", state.editor.warningEnterPercentText)
        assertEquals("60", state.editor.dangerEnterPercentText)
    }

    @Test
    fun c_disablingAndSavingKeepsTheRowAndItsValues() {
        val repository = FakeEyeSafetyRepository()
        repository.seed(accountId, childA, model(enabled = true))
        val controller = controller(repository)

        runBlocking {
            controller.onEnabledChange(false)
            assertTrue(controller.save())
        }

        assertEquals("the row is updated, not deleted", 1, repository.rows.size)
        val stored = repository.rows[accountId to childA]!!
        assertFalse(stored.config.enabled)
        assertEquals("the previous thresholds are preserved", 0.30f, stored.config.warningEnterThreshold)
        assertEquals(ProtectionAction.WARNING, stored.warningAction)
    }

    // ---- D/E/F. editing and validation -------------------------------------

    @Test
    fun d_editingThresholdsUpdatesTheStateWithoutSaving() {
        val repository = FakeEyeSafetyRepository()
        val controller = controller(repository)

        controller.onWarningEnterChange("35")
        controller.onDangerEnterChange("45")

        assertEquals(35, controller.state.value.editor.warningEnterPercent)
        assertEquals(45, controller.state.value.editor.dangerEnterPercent)
        assertEquals("editing alone must not persist", 0, repository.saveCalls)
    }

    @Test
    fun e_editingActionsUpdatesOnlyTheChosenLevel() {
        val controller = controller(FakeEyeSafetyRepository())

        controller.onWarningActionChange(ProtectionAction.MUTE)

        assertEquals(ProtectionAction.MUTE, controller.state.value.editor.warningAction)
        assertEquals(ProtectionAction.ALLOW, controller.state.value.editor.dangerAction)
    }

    @Test
    fun f_aValidConfirmationCountIsAcceptedAndSaved() {
        val repository = FakeEyeSafetyRepository()
        val controller = controller(repository)

        runBlocking {
            controller.onEnabledChange(true)
            controller.onConfirmFramesChange("5")
            assertTrue(controller.save())
        }

        assertEquals(5, repository.savedModels.single().config.confirmFrames)
    }

    @Test
    fun thresholdInputRejectsNonDigitsInsteadOfStoringThem() {
        val controller = controller(FakeEyeSafetyRepository())

        controller.onWarningEnterChange("3.5")
        controller.onDangerEnterChange("-20")
        controller.onConfirmFramesChange("1e3")

        // Only digits survive typing, so a decimal, sign or exponent can never reach the model.
        assertEquals("35", controller.state.value.editor.warningEnterPercentText)
        assertEquals("20", controller.state.value.editor.dangerEnterPercentText)
        assertEquals("13", controller.state.value.editor.confirmFramesText)
    }

    // ---- G. save ------------------------------------------------------------

    @Test
    fun g_savingWritesExactlyWhatWasConfiguredToThisScope() {
        val repository = FakeEyeSafetyRepository()
        val controller = controller(repository, clock = { 4_242L })

        runBlocking {
            controller.onEnabledChange(true)
            controller.onWarningEnterChange("25")
            controller.onWarningExitChange("20")
            controller.onDangerEnterChange("55")
            controller.onDangerExitChange("45")
            controller.onConfirmFramesChange("4")
            controller.onWarningActionChange(ProtectionAction.MUTE)
            controller.onDangerActionChange(ProtectionAction.HARD_BLOCK)

            assertTrue(controller.save())
        }

        assertEquals(1, repository.saveCalls)
        assertEquals("the save is addressed to this account and child", accountId to childA, repository.lastSaveScope)

        val stored = repository.savedModels.single()
        assertTrue(stored.config.enabled)
        assertEquals(0.25f, stored.config.warningEnterThreshold)
        assertEquals(0.20f, stored.config.warningExitThreshold)
        assertEquals(0.55f, stored.config.dangerEnterThreshold)
        assertEquals(0.45f, stored.config.dangerExitThreshold)
        assertEquals(4, stored.config.confirmFrames)
        assertEquals(ProtectionAction.MUTE, stored.warningAction)
        assertEquals(ProtectionAction.HARD_BLOCK, stored.dangerAction)
        assertEquals("the clock is the one supplied", 4_242L, stored.updatedAt)
    }

    @Test
    fun g_onlyOneRowIsWrittenForThisChild() {
        val repository = FakeEyeSafetyRepository()
        val controller = controller(repository)

        runBlocking {
            controller.onEnabledChange(true)
            controller.save()
        }

        assertEquals(1, repository.rows.size)
        assertEquals(childA, repository.rows.keys.single().second)
    }

    @Test
    fun g_afterASaveTheStateMirrorsWhatWasStored() {
        val repository = FakeEyeSafetyRepository()
        val controller = controller(repository, clock = { 7L })

        runBlocking {
            controller.onEnabledChange(true)
            // 29 stays above the default warning exit (27), so the change is a valid configuration.
            controller.onWarningEnterChange("29")
            assertTrue(controller.save())
        }

        val state = controller.state.value
        assertTrue(state.configured)
        assertFalse(state.editor.saving)
        assertNull(state.editor.errorMessageRes)
        assertEquals("29", state.editor.warningEnterPercentText)
    }

    @Test
    fun anEnterThresholdBelowTheCurrentExitIsReportedAndNotSilentlyMoved() {
        val repository = FakeEyeSafetyRepository()
        val controller = controller(repository)

        runBlocking {
            // The editor's default exit is 27, so 26 makes the pair inconsistent. The fix is the
            // parent's to make: nothing is clamped, swapped or saved behind their back.
            controller.onWarningEnterChange("26")
            assertFalse(controller.save())
        }

        assertEquals(0, repository.saveCalls)
        assertEquals(R.string.eye_safety_error_exit_order, controller.state.value.editor.errorMessageRes)
        assertEquals("26", controller.state.value.editor.warningEnterPercentText)
    }

    // ---- validation blocks the save ----------------------------------------

    @Test
    fun anInvalidConfigurationIsRejectedAndNothingIsWritten() {
        val repository = FakeEyeSafetyRepository()
        val controller = controller(repository)

        runBlocking {
            controller.onEnabledChange(true)
            // warning above danger: invalid, and must not be "fixed" into some other value.
            controller.onWarningEnterChange("45")
            controller.onDangerEnterChange("40")

            assertFalse(controller.save())
        }

        assertEquals(0, repository.saveCalls)
        assertTrue(repository.rows.isEmpty())
        assertEquals(
            R.string.eye_safety_error_threshold_order,
            controller.state.value.editor.errorMessageRes,
        )
    }

    @Test
    fun anEmptyFieldBlocksTheSaveWithAnInputError() {
        val repository = FakeEyeSafetyRepository()
        val controller = controller(repository)

        runBlocking {
            controller.onDangerEnterChange("")
            assertFalse(controller.save())
        }

        assertEquals(0, repository.saveCalls)
        assertEquals(R.string.eye_safety_error_invalid, controller.state.value.editor.errorMessageRes)
    }

    @Test
    fun zeroConfirmationFramesBlocksTheSave() {
        val repository = FakeEyeSafetyRepository()
        val controller = controller(repository)

        runBlocking {
            controller.onConfirmFramesChange("0")
            assertFalse(controller.save())
        }

        assertEquals(0, repository.saveCalls)
        assertEquals(R.string.eye_safety_error_confirm_frames, controller.state.value.editor.errorMessageRes)
    }

    @Test
    fun editingClearsAPreviousErrorMessage() {
        val repository = FakeEyeSafetyRepository()
        val controller = controller(repository)

        runBlocking {
            controller.onDangerEnterChange("")
            controller.save()
            assertEquals(R.string.eye_safety_error_invalid, controller.state.value.editor.errorMessageRes)

            controller.onDangerEnterChange("40")
            assertNull(controller.state.value.editor.errorMessageRes)
        }
    }

    // ---- H. save failure ----------------------------------------------------

    @Test
    fun h_aFailedSaveReportsAnErrorAndKeepsTheInput() {
        val repository = FakeEyeSafetyRepository()
        repository.failSave = true
        val controller = controller(repository)

        val saved = runBlocking {
            controller.onEnabledChange(true)
            controller.onWarningEnterChange("28")
            controller.save()
        }

        assertFalse("a failed save must never report success", saved)
        assertEquals(R.string.eye_safety_error_save, controller.state.value.editor.errorMessageRes)
        assertEquals("the parent's input is kept for a retry", "28", controller.state.value.editor.warningEnterPercentText)
        assertFalse(controller.state.value.editor.saving)
        assertTrue("no row was created", repository.rows.isEmpty())
    }

    @Test
    fun h_aFailedSaveOnAnUnconfiguredChildLeavesItUnconfigured() {
        val repository = FakeEyeSafetyRepository()
        repository.failSave = true
        val controller = controller(repository)

        runBlocking {
            controller.onEnabledChange(true)
            controller.save()
        }

        assertFalse(
            "a failed save must not claim the child is now configured",
            controller.state.value.configured,
        )
    }

    @Test
    fun h_aRetryAfterAFixedFailureSucceeds() {
        val repository = FakeEyeSafetyRepository()
        repository.failSave = true
        val controller = controller(repository)

        runBlocking {
            controller.onEnabledChange(true)
            assertFalse(controller.save())

            repository.failSave = false
            assertTrue(controller.save())
        }

        assertEquals(1, repository.rows.size)
        assertTrue(controller.state.value.configured)
    }

    // ---- I. load failure ----------------------------------------------------

    @Test
    fun i_aFailedLoadReportsAnErrorInsteadOfLookingUnconfigured() {
        val repository = FakeEyeSafetyRepository()
        repository.failLoad = true

        val state = controller(repository).state.value

        assertFalse(state.loading)
        assertEquals(R.string.eye_safety_error_load, state.loadErrorMessageRes)
        assertFalse("a read failure is not the same as unconfigured", state.configured)
    }

    @Test
    fun i_aFailedLoadDoesNotCrashAndWritesNothing() {
        val repository = FakeEyeSafetyRepository()
        repository.failLoad = true

        val controller = controller(repository)

        assertEquals(0, repository.saveCalls)
        assertTrue(repository.rows.isEmpty())
        assertFalse(controller.state.value.editor.saving)
    }

    // ---- J. child isolation -------------------------------------------------

    @Test
    fun j_aControllerOnlyEverReadsAndWritesItsOwnChild() {
        val repository = FakeEyeSafetyRepository()
        repository.seed(accountId, childA, model(childId = childA, warningEnter = 0.30f))
        repository.seed(accountId, childB, model(childId = childB, warningEnter = 0.60f, dangerEnter = 0.70f))

        val forA = controller(repository, childId = childA)

        assertEquals("30", forA.state.value.editor.warningEnterPercentText)

        runBlocking {
            forA.onWarningEnterChange("31")
            forA.onDangerEnterChange("41")
            assertTrue(forA.save())
        }

        assertEquals("child A was written", 0.31f, repository.rows[accountId to childA]!!.config.warningEnterThreshold)
        assertEquals(
            "child B is untouched",
            0.60f,
            repository.rows[accountId to childB]!!.config.warningEnterThreshold,
        )
        assertEquals(accountId to childA, repository.lastSaveScope)
    }

    @Test
    fun j_childBLoadsItsOwnConfiguration() {
        val repository = FakeEyeSafetyRepository()
        repository.seed(accountId, childA, model(childId = childA, warningEnter = 0.30f))
        repository.seed(accountId, childB, model(childId = childB, warningEnter = 0.60f, dangerEnter = 0.70f))

        val forB = controller(repository, childId = childB)

        assertTrue(forB.state.value.configured)
        assertEquals("60", forB.state.value.editor.warningEnterPercentText)
        assertEquals("70", forB.state.value.editor.dangerEnterPercentText)
    }

    @Test
    fun j_aStaleControllerCannotWriteToAnotherChild() {
        val repository = FakeEyeSafetyRepository()
        val stale = controller(repository, childId = childA)

        runBlocking {
            stale.onEnabledChange(true)
            stale.save()
        }

        assertEquals("the write went to the controller's own child", childA, repository.rows.keys.single().second)
        assertNull("child B was never written", repository.rows[accountId to childB])
    }

    @Test
    fun j_aChildWithNoRowIsUnconfiguredEvenWhenASiblingIsConfigured() {
        val repository = FakeEyeSafetyRepository()
        repository.seed(accountId, childA, model(childId = childA))

        val forB = controller(repository, childId = childB)

        assertFalse(forB.state.value.configured)
        assertFalse(forB.state.value.editor.enabled)
    }

    // ---- determinism --------------------------------------------------------

    @Test
    fun theSameLoadProducesTheSameState() {
        val repository = FakeEyeSafetyRepository()
        repository.seed(accountId, childA, model(warningEnter = 0.22f))

        val first = controller(repository).state.value
        val second = controller(repository).state.value

        assertEquals(first.editor, second.editor)
        assertEquals(first.configured, second.configured)
    }

    @Test
    fun repeatedSavesAreIdempotentForTheSameInput() {
        val repository = FakeEyeSafetyRepository()
        val controller = controller(repository)

        runBlocking {
            controller.onEnabledChange(true)
            controller.onWarningEnterChange("30")
            assertTrue(controller.save())
            assertTrue(controller.save())
        }

        assertEquals("both saves address the same single row", 1, repository.rows.size)
        assertEquals(2, repository.saveCalls)
    }
}

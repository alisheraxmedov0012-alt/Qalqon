package uz.faceguard.app.ux

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 8: the honest-state and Stage 1–7 regression guard.
 *
 * Stage 8 is about *not lying to the parent*: readiness must come from real capability
 * health, not the on/off setting, and camera recovery must be surfaced. This reads the
 * source so a future change that reintroduces a false-success path — or that weakens an
 * earlier stage — fails here instead of on a device.
 */
class Stage8RegressionGuardTest {

    private fun read(relative: String): String {
        val file = File(repoRoot(), relative)
        assertTrue("missing file: ${file.path}", file.isFile)
        return file.readText()
    }

    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        while (dir.parentFile != null) {
            if (File(dir, "app/src/main/res/values/strings.xml").isFile) return dir
            dir = dir.parentFile
        }
        error("could not locate the repository root")
    }

    private val root = "app/src/main/java/uz/faceguard/app/"

    // -------------------------------------------------------- no false success

    @Test
    fun theProtectionStatusCardUsesReadiness_notTheRawOnOffSetting() {
        val screen = read("${root}feature/protection/ProtectionScreen.kt")
        assertTrue(
            "the status card must derive its verdict from the readiness model",
            screen.contains("state.readiness") && screen.contains("protectionReadinessLabelRes"),
        )
    }

    @Test
    fun readinessIncludesTheAccessibilityGuardrail() {
        // The old `ready` excluded accessibility; the readiness model must include it.
        val readiness = read("${root}domain/protection/ProtectionReadiness.kt")
        assertTrue(readiness.contains("missingCapabilities.isNotEmpty() -> ProtectionReadiness.LIMITED"))
        assertTrue(
            "missing capabilities must come from the shared computation",
            read("${root}core/protection/ProtectionRuntime.kt").contains("missingCapabilities = degradedCapabilities"),
        )
    }

    @Test
    fun cameraRecoveryIsSurfacedOnBothHomeAndTheProtectionScreen() {
        assertTrue(read("${root}feature/protection/ProtectionScreen.kt").contains("state.cameraRecovering"))
        assertTrue(read("${root}feature/home/HomeScreen.kt").contains("protectionState.cameraRecovering"))
    }

    @Test
    fun theRecoveryRetryDelegatesToTheSingleSession_andIsGuardedByActive() {
        val runtime = read("${root}core/protection/ProtectionRuntime.kt")
        assertTrue(runtime.contains("fun retryCameraRecovery()"))
        assertTrue("the retry must short-circuit when inactive", runtime.contains("if (!active) return"))
        assertTrue("the retry must reuse the single session", runtime.contains("cameraSession.retryNow()"))
    }

    // ----------------------------------------------------- permission UX honesty

    @Test
    fun theRequirementsCardExplainsWhyEachCapabilityIsNeeded() {
        val screen = read("${root}feature/protection/ProtectionScreen.kt")
        assertTrue(screen.contains("protectionCapabilityWhyRes("))
        assertTrue(screen.contains("protection_requirements_required_group"))
    }

    // --------------------------------------------------------- Stage 1–7

    @Test
    fun stage1_recognitionStillRequiresAModelEmbedding() {
        assertTrue(read("${root}core/recognition/Recognizer.kt").contains("Thresholds(val parent"))
        assertTrue(read("${root}core/recognition/IdentityDecision.kt").contains("EmbeddingSource.MODEL"))
    }

    @Test
    fun stage2_multiFaceAggregationIsIntact() {
        assertTrue(read("${root}core/recognition/Recognizer.kt").contains("MultiFacePolicyEngine.decide"))
    }

    @Test
    fun stage3_livenessAndSpoofPolicyAreIntact() {
        assertTrue(read("${root}domain/policy/LivenessPolicy.kt").contains("SPOOF"))
        assertTrue(read("${root}core/protection/ProtectionEngine.kt").contains("livenessEvaluator"))
    }

    @Test
    fun stage4_enforcementReassertIsIntact() {
        assertTrue(read("${root}core/protection/ProtectionActionExecutor.kt").contains("fun reassert("))
        assertTrue(read("${root}core/protection/ProtectionEngine.kt").contains("actions.reassert("))
    }

    @Test
    fun stage5_backgroundRecoveryIsIntact() {
        assertTrue(read("${root}core/protection/ProtectionServiceLifecycle.kt").contains("ServiceRestartMode.STICKY"))
        assertTrue(read("${root}core/protection/ProtectionServiceLifecycle.kt").contains("CameraRecoveryBackoff"))
        assertTrue(read("${root}core/protection/ProtectionEngine.kt").contains("fun onCameraInterrupted()"))
    }

    @Test
    fun stage6_permissionAndOemCompatibilityIsIntact() {
        assertTrue(read("${root}domain/oem/OemDetector.kt").contains("fun detect("))
        assertTrue(read("${root}core/oem/AndroidOemSettings.kt").contains("settingsIntentCandidates"))
    }

    @Test
    fun stage7_versionMatrixIsIntact() {
        val compat = read("${root}core/compat/PlatformCompat.kt")
        assertTrue(compat.contains("fun foregroundServiceTypes("))
        assertTrue(compat.contains("fun fullscreenOverlayCutoutMode("))
    }

    @Test
    fun debugScreensStayGatedByTheDebugBuild() {
        assertTrue(read("${root}core/debug/DebugFlags.kt").contains("BuildConfig.DEBUG"))
        val nav = read("${root}navigation/NavGraph.kt")
        assertTrue(
            "the recognition debug route must be guarded by the debug flag",
            nav.contains("if (DebugFlags.DEBUG_SCREENS_ENABLED)") && nav.contains("RECOGNITION_DEBUG"),
        )
    }

    @Test
    fun noHardcodedUserFacingTextInTheProtectionScreen() {
        val screen = read("${root}feature/protection/ProtectionScreen.kt")
        assertFalse(
            "the protection screen must use string resources, not literals",
            Regex("Text\\(\"").containsMatchIn(screen),
        )
    }
}

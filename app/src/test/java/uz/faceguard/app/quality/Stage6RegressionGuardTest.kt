package uz.faceguard.app.quality

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 6: the Stage 1-5 regression guard.
 *
 * Stage 6 only adds permission/OEM guidance; it must not weaken any earlier
 * stage. This guard reads the source and asserts the load-bearing invariant of
 * each stage is still present, so a change that silently removes one fails here
 * instead of on a device. It complements (never replaces) the behavioural suites.
 */
class Stage6RegressionGuardTest {

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

    @Test
    fun stage1_recognitionThresholdsAndModelGatingAreIntact() {
        val recognizer = read("${root}core/recognition/Recognizer.kt")
        assertTrue("identity thresholds must stay", recognizer.contains("Thresholds(val parent"))
        assertTrue("per-face decision engine must stay", recognizer.contains("IdentityDecisionEngine.decide"))
        // Stage 1 rule: only a real model embedding may authorize identity.
        val idEngine = read("${root}core/recognition/IdentityDecision.kt")
        assertTrue("model-embedding gating must stay", idEngine.contains("EmbeddingSource.MODEL"))
    }

    @Test
    fun stage2_multiFaceAggregationIsIntact() {
        assertTrue(read("${root}core/recognition/Recognizer.kt").contains("MultiFacePolicyEngine.decide"))
        assertTrue(read("${root}core/recognition/MultiFacePolicyEngine.kt").isNotBlank())
    }

    @Test
    fun stage3_livenessStaysSeparateFromIdentityAndKeepsSpoofPolicy() {
        val policy = read("${root}domain/policy/LivenessPolicy.kt")
        assertTrue("SPOOF semantics must stay", policy.contains("SPOOF"))
        val engine = read("${root}core/protection/ProtectionEngine.kt")
        assertTrue("liveness must remain its own signal in the engine", engine.contains("livenessEvaluator"))
    }

    @Test
    fun stage4_enforcementSelfHealIsIntact() {
        assertTrue(
            "the enforcement re-assert path must stay",
            read("${root}core/protection/ProtectionActionExecutor.kt").contains("fun reassert(action: ProtectionAction)"),
        )
        assertTrue(
            "the engine must keep re-asserting the block",
            read("${root}core/protection/ProtectionEngine.kt").contains("actions.reassert("),
        )
    }

    @Test
    fun stage5_restartAndCameraRecoveryAreIntact() {
        val lifecycle = read("${root}core/protection/ProtectionServiceLifecycle.kt")
        assertTrue("the sticky restart strategy must stay", lifecycle.contains("ServiceRestartMode.STICKY"))
        assertTrue("bounded camera backoff must stay", lifecycle.contains("class CameraRecoveryBackoff"))

        val service = read("${root}core/protection/ProtectionForegroundService.kt")
        assertTrue("task removal must stay a no-op", service.contains("onTaskRemoved"))
        assertTrue("in-process re-activation must stay", service.contains("runtime.onServiceStarted()"))

        assertTrue(
            "stale recognition clearing must stay",
            read("${root}core/protection/ProtectionEngine.kt").contains("fun onCameraInterrupted()"),
        )
        assertTrue(
            "the service must not stop with the task",
            read("app/src/main/AndroidManifest.xml").contains("android:stopWithTask=\"false\""),
        )
    }

    @Test
    fun stage6_addsNoInternetOrNewDangerousPermissions() {
        val manifest = read("app/src/main/AndroidManifest.xml")
        // Offline-first stays enforced (INTERNET is only ever removed, never added).
        assertTrue(manifest.contains("android.permission.INTERNET\" tools:node=\"remove\""))
        assertFalse(
            "Stage 6 must not request the battery-optimization dialog permission",
            manifest.contains("android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"),
        )
        assertFalse(
            "no new background/network permission may be introduced",
            manifest.contains("android.permission.ACCESS_BACKGROUND_LOCATION"),
        )
    }
}

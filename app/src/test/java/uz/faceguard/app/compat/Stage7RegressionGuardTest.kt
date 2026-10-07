package uz.faceguard.app.compat

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.faceguard.app.core.compat.PlatformCompat

/**
 * Stage 7: the Stage 1–6 regression guard.
 *
 * Stage 7 only normalises Android-version behaviour; it must not weaken any earlier
 * stage. This reads the source and asserts the load-bearing mechanism of each stage
 * is still present and still routed through the same seam, so a version-gate change
 * that silently removed one fails here.
 */
class Stage7RegressionGuardTest {

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
    fun stage1_identityStillRequiresAModelEmbedding() {
        assertTrue(read("${root}core/recognition/Recognizer.kt").contains("Thresholds(val parent"))
        assertTrue(read("${root}core/recognition/IdentityDecision.kt").contains("EmbeddingSource.MODEL"))
    }

    @Test
    fun stage2_multiFaceAggregationIsIntact() {
        assertTrue(read("${root}core/recognition/Recognizer.kt").contains("MultiFacePolicyEngine.decide"))
    }

    @Test
    fun stage3_livenessStaysSeparateWithItsSpoofPolicy() {
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
        assertTrue(read("app/src/main/AndroidManifest.xml").contains("android:stopWithTask=\"false\""))
    }

    @Test
    fun stage6_permissionAndOemCompatibilityIsIntact() {
        assertTrue(read("${root}domain/oem/OemDetector.kt").contains("fun detect("))
        assertTrue(read("${root}core/oem/AndroidOemSettings.kt").contains("settingsIntentCandidates"))
        assertTrue(read("${root}domain/diagnostics/DiagnosticsModels.kt").contains("BATTERY_OPTIMIZATION"))
        assertTrue(read("${root}domain/diagnostics/DiagnosticsModels.kt").contains("OEM_BACKGROUND"))
    }

    @Test
    fun stage7_platformMatrixIsTheSingleSourceOfVersionDecisions() {
        // The two decisions Stage 7 centralised must still live in PlatformCompat.
        val compat = read("${root}core/compat/PlatformCompat.kt")
        assertTrue(compat.contains("fun foregroundServiceTypes("))
        assertTrue(compat.contains("fun fullscreenOverlayCutoutMode("))
        assertTrue(PlatformCompat.foregroundServiceTypes(35, includeCamera = true) != 0)
    }
}

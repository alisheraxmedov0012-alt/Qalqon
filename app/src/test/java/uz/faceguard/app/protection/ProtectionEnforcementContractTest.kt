package uz.faceguard.app.protection

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 4 (Protection Enforcement): source-contract guards for the parts of the enforcement
 * pipeline that need Android (the real WindowManager windows), plus the honest distinction
 * between real input blocking and the visual-only fallback. The live touch behaviour itself
 * is verified by the instrumented `AccessibilityOverlayWindowTest` and, ultimately, needs a
 * physical device.
 */
class ProtectionEnforcementContractTest {

    private val executor by lazy { read("core/protection/ProtectionActionExecutor.kt") }
    private val engine by lazy { read("core/protection/ProtectionEngine.kt") }
    private val accessibilityWindow by lazy { read("core/accessibility/AccessibilityOverlayWindow.kt") }
    private val overlayController by lazy { read("core/protection/OverlayControllerImpl.kt") }

    // ------------------------------------------------ self-heal wiring

    @Test
    fun theExecutorReassertsTheOverlayForBlockActionsAndOverridesTheDefault() {
        assertTrue(
            "the executor must override the re-assert seam",
            executor.contains("override fun reassert(action: ProtectionAction)"),
        )
        assertTrue(
            "a block action must re-show the overlay",
            executor.contains("-> safeOverlay { overlay.show() }"),
        )
    }

    @Test
    fun theEngineReassertsTheBlockWhileItIsStillRequired() {
        assertTrue(
            "the engine must re-assert the enforcement action while blocked",
            engine.contains("actions.reassert(decision.action)"),
        )
    }

    // ------------------------------ real block vs visual fallback (honest distinction)

    @Test
    fun theAccessibilityOverlayIsATouchableFullScreenBlock() {
        val src = accessibilityWindow
        assertTrue(
            "the real block must be an accessibility overlay",
            src.contains("TYPE_ACCESSIBILITY_OVERLAY"),
        )
        assertTrue("the real block must be full-screen", src.contains("MATCH_PARENT"))
        assertTrue(
            "the real block must consume touches",
            src.contains("setOnTouchListener") || src.contains("isClickable = true"),
        )
        assertFalse(
            "the real block must NOT OR-in FLAG_NOT_TOUCHABLE on its window",
            Regex("""\bor\s+WindowManager\.LayoutParams\.FLAG_NOT_TOUCHABLE""").containsMatchIn(src),
        )
    }

    @Test
    fun theLegacyApplicationOverlayIsExplicitlyOnlyAVisualFallback() {
        // Honest: the SYSTEM_ALERT_WINDOW fallback is a non-touchable scrim — visual only.
        // It must not be presented as a real input block anywhere in the code.
        assertTrue(
            "the legacy fallback must declare itself non-touchable",
            overlayController.contains("FLAG_NOT_TOUCHABLE"),
        )
        assertTrue(
            "the fallback path must be separate from the touchable accessibility host",
            overlayController.contains("showLegacy()"),
        )
    }

    @Test
    fun enforcementNeverClaimsToBeAWholeDeviceLock() {
        // A consumer app cannot lock the whole device. The enforcement layer must not claim a
        // system-wide block; the accessibility overlay only covers the foreground app.
        listOf(executor, engine, overlayController).forEach { src ->
            assertFalse(
                "no source may claim an absolute/system-wide lock",
                Regex("100%|unbypassable|impossible to bypass|device lock|kiosk", RegexOption.IGNORE_CASE)
                    .containsMatchIn(src),
            )
        }
    }

    private fun read(relativePath: String): String {
        val file = File(repoRoot(), "app/src/main/java/uz/faceguard/app/$relativePath")
        assertTrue("missing file: ${file.path}", file.isFile)
        return file.readText()
    }

    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        while (dir.parentFile != null) {
            if (File(dir, "app/src/main/res/values/strings.xml").isFile) return dir
            dir = dir.parentFile
        }
        error("could not locate the repository root from ${System.getProperty("user.dir")}")
    }
}

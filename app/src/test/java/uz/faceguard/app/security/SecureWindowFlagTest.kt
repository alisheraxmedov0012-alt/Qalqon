package uz.faceguard.app.security

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `FLAG_SECURE` on the screens and windows that hold sensitive content.
 *
 * A PIN is typed on the unlock/create screens, and the blocking overlay covers a
 * child's app; none of them may appear in a screenshot, a screen recording, or the
 * Recents thumbnail. This is a source-level contract (the project has no Compose UI
 * host), pinned the same way the other UI invariants are.
 */
class SecureWindowFlagTest {

    @Test
    fun theCredentialScreensFlagTheirWindowSecure() {
        listOf(
            "feature/auth/PinUnlockScreen.kt",
            "feature/auth/CreatePinScreen.kt",
        ).forEach { path ->
            val source = read(path)
            assertTrue(
                "$path must call SecureScreenFlag() while a credential is entered",
                source.contains("SecureScreenFlag()"),
            )
        }
    }

    @Test
    fun theSecureFlagHelperSetsAndClearsTheWindowFlag() {
        val helper = read("core/ui/SecureFlag.kt")
        assertTrue(
            "the helper must add FLAG_SECURE",
            helper.contains("WindowManager.LayoutParams.FLAG_SECURE"),
        )
        assertTrue(
            "the helper must add the flag to the hosting window",
            helper.contains("addFlags(WindowManager.LayoutParams.FLAG_SECURE)"),
        )
        assertTrue(
            "the helper must clear the flag on dispose so it never leaks",
            helper.contains("clearFlags(WindowManager.LayoutParams.FLAG_SECURE)"),
        )
    }

    @Test
    fun bothBlockingOverlayWindowsAreMarkedSecure() {
        val accessibility = read("core/accessibility/AccessibilityOverlayWindow.kt")
        assertTrue(
            "the accessibility blocking window must be FLAG_SECURE",
            accessibility.contains("WindowManager.LayoutParams.FLAG_SECURE"),
        )

        val legacy = read("core/protection/OverlayControllerImpl.kt")
        assertTrue(
            "the legacy blocking scrim must be FLAG_SECURE",
            legacy.contains("WindowManager.LayoutParams.FLAG_SECURE"),
        )
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

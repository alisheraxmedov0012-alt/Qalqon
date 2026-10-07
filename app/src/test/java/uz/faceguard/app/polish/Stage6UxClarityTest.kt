package uz.faceguard.app.polish

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 6 (UX / Product Quality) clarity regressions, JVM-testable.
 *
 * Two confirmed Stage 6 defects are pinned here so they cannot return:
 *
 * 1. The parent-profile and face-enrollment ViewModels showed a raw `Throwable`
 *    message (or its class simple name) to the user through a Toast. A parent must
 *    never see an exception string; the technical detail belongs in logcat.
 * 2. The Protection screen carried a stale note claiming that keeping the camera
 *    alive in the background "will need a system service in a later phase". That
 *    service already exists (`ProtectionForegroundService`), so the note misled the
 *    parent about when protection works.
 *
 * These are source/resource contracts (the project has no Compose UI host), pinned
 * the same way as the other UI invariants.
 */
class Stage6UxClarityTest {

    private val parentProfile by lazy {
        read("app/src/main/java/uz/faceguard/app/feature/parent/ParentProfileScreen.kt")
    }
    private val enrollment by lazy {
        read("app/src/main/java/uz/faceguard/app/feature/enrollment/FaceEnrollmentScreen.kt")
    }
    private val uzStrings by lazy { read("app/src/main/res/values/strings.xml") }
    private val enStrings by lazy { read("app/src/main/res/values-en/strings.xml") }
    private val ruStrings by lazy { read("app/src/main/res/values-ru/strings.xml") }

    // ------------------------------------------------ no technical detail to the user

    @Test
    fun noViewModelSurfacesRawThrowableDetailToTheUser() {
        listOf(parentProfile, enrollment).forEach { source ->
            assertFalse(
                "a raw Throwable message must not be shown to the user",
                source.contains(".message?.takeIf") || source.contains("error.message"),
            )
            assertFalse(
                "a class name must not be shown to the user",
                source.contains("javaClass.simpleName"),
            )
        }
    }

    @Test
    fun aFailureIsLoggedAndReportedAsAGenericLocalizedError() {
        listOf(parentProfile, enrollment).forEach { source ->
            assertTrue("a failure must be logged for diagnosis", source.contains("Log.w(TAG,"))
            assertTrue(
                "the user must get the generic error resource",
                source.contains("R.string.error_unexpected"),
            )
            // A Toast must never format the error with an interpolated detail.
            assertFalse(
                "the error Toast must not interpolate internal detail",
                source.contains("R.string.error_generic, detail"),
            )
        }
    }

    @Test
    fun theErrorStateCarriesAResourceIdNotAString() {
        assertTrue(parentProfile.contains("MutableStateFlow<Int?>(null)"))
        assertTrue(enrollment.contains("MutableStateFlow<Int?>(null)"))
    }

    // ------------------------------------------------ honest background-protection note

    @Test
    fun theBackgroundProtectionNoteNoLongerClaimsAFutureService() {
        assertFalse(
            "the note must not claim the background camera needs a future phase",
            uzStrings.contains("tizim xizmati keyingi bosqichda"),
        )
        assertFalse(
            "the note must not claim the background camera needs a later phase",
            enStrings.contains("will need a system service in a later phase"),
        )
        assertFalse(
            "the note must not claim the background camera needs a later phase",
            ruStrings.contains("на следующем этапе потребуется системная служба"),
        )
    }

    @Test
    fun theBackgroundProtectionNoteIsHonestAndLocalized() {
        // It must describe the real foreground-service behaviour in every locale.
        assertTrue(uzStrings.contains("tizim xizmati orqali fonda ham ishlaydi"))
        assertTrue(enStrings.contains("keeps running in the background through a system service"))
        assertTrue(ruStrings.contains("продолжает работать в фоне через системную службу"))
    }

    // ------------------------------------------------ localization parity for the new string

    @Test
    fun theNewGenericErrorStringExistsInEveryLocale() {
        val marker = "name=\"error_unexpected\""
        listOf(uzStrings, enStrings, ruStrings).forEach { strings ->
            assertTrue("error_unexpected must exist in every locale", strings.contains(marker))
        }
        assertEquals(
            "the three locales must have the same number of strings",
            countStrings(uzStrings),
            countStrings(enStrings),
        )
        assertEquals(
            "the three locales must have the same number of strings",
            countStrings(uzStrings),
            countStrings(ruStrings),
        )
    }

    private fun countStrings(contents: String): Int =
        Regex("""<string name="""").findAll(contents).count()

    private fun read(relativePath: String): String {
        val file = File(repoRoot(), relativePath)
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

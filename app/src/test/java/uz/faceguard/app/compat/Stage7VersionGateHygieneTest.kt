package uz.faceguard.app.compat

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 7: version-gate hygiene across the production source.
 *
 * The classic cross-version bug is an off-by-one gate — a bare `SDK_INT >= 33`
 * instead of the named constant, or a gate that disagrees with the one place it
 * should share. These tests keep every version decision either named
 * (`Build.VERSION_CODES.*`) or expressed through the single pure matrix
 * ([uz.faceguard.app.core.compat.PlatformCompat]).
 */
class Stage7VersionGateHygieneTest {

    private fun mainSources(): List<File> =
        File(repoRoot(), "app/src/main/java").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()

    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        while (dir.parentFile != null) {
            if (File(dir, "app/src/main/res/values/strings.xml").isFile) return dir
            dir = dir.parentFile
        }
        error("could not locate the repository root")
    }

    /** `SDK_INT >= 33` / `SDK_INT < 34` — a bare literal instead of a named constant. */
    private val bareLiteralGate = Regex("""SDK_INT\s*[<>!=]+\s*=\s*\d+""")

    @Test
    fun noProductionVersionGateComparesSdkIntToABareLiteral() {
        val offenders = mainSources().flatMap { file ->
            bareLiteralGate.findAll(file.readText()).map { "${file.name}: ${it.value}" }.toList()
        }
        assertTrue(
            "use Build.VERSION_CODES.* or PlatformCompat.API_* instead of a literal: $offenders",
            offenders.isEmpty(),
        )
    }

    @Test
    fun theForegroundServiceTypeDecisionLivesOnlyInPlatformCompat() {
        // Exactly one place decides the FGS type masks, so a second, drifting copy
        // cannot appear in a screen or a service.
        val definers = mainSources().filter { it.readText().contains("FGS_TYPE_SPECIAL_USE =") }
        assertTrue(
            "the specialUse bit must be defined exactly once: ${definers.map { it.name }}",
            definers.size == 1 && definers.first().name == "PlatformCompat.kt",
        )
    }

    @Test
    fun theDisplayCutoutModeDecisionLivesOnlyInPlatformCompat() {
        val definers = mainSources().filter { it.readText().contains("CUTOUT_MODE_ALWAYS =") }
        assertTrue(
            "the cutout mode constants must be defined exactly once: ${definers.map { it.name }}",
            definers.size == 1 && definers.first().name == "PlatformCompat.kt",
        )
    }

    @Test
    fun everyLayoutInDisplayCutoutModeWriteIsGuardedAndUsesPlatformCompat() {
        // The field only exists from API 28; a write must be behind a named gate and
        // must take its value from the shared matrix.
        val writers = mainSources().filter { it.readText().contains("layoutInDisplayCutoutMode =") }
        assertTrue("the overlays must set a cutout mode", writers.isNotEmpty())
        writers.forEach { file ->
            val text = file.readText()
            assertTrue(
                "${file.name} must gate the cutout write behind API 28",
                text.contains("Build.VERSION.SDK_INT >= Build.VERSION_CODES.P"),
            )
            assertTrue(
                "${file.name} must take the mode from PlatformCompat",
                text.contains("PlatformCompat.fullscreenOverlayCutoutMode("),
            )
        }
    }

    @Test
    fun theForegroundServiceTypeUsesTheSharedMatrix() {
        val service = File(repoRoot(), "app/src/main/java/uz/faceguard/app/core/protection/ProtectionForegroundService.kt").readText()
        assertTrue(
            "the service must delegate its type mask to PlatformCompat",
            service.contains("PlatformCompat.foregroundServiceTypes("),
        )
    }

    @Test
    fun theNotificationPermissionGateSharesThePublishedApiLevel() {
        // The requests screen's constant and the matrix must not drift apart.
        val requests = File(repoRoot(), "app/src/main/java/uz/faceguard/app/feature/requests/RequestsScreen.kt").readText()
        assertTrue(
            "the requests screen must re-export the PlatformCompat API level",
            requests.contains("PlatformCompat.NOTIFICATION_PERMISSION_API"),
        )
    }

    @Test
    fun theAppOpUsageReadSplitsOnTheNamedApi29Boundary() {
        val usage = File(repoRoot(), "app/src/main/java/uz/faceguard/app/core/usage/AndroidUsageAccess.kt").readText()
        assertTrue(
            "the non-deprecated app-op read must be gated on API 29",
            usage.contains("Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q"),
        )
        assertTrue("the unsafe overload must be used on modern platforms", usage.contains("unsafeCheckOpNoThrow"))
        assertTrue("the fallback overload must remain for older platforms", usage.contains("checkOpNoThrow"))
    }
}

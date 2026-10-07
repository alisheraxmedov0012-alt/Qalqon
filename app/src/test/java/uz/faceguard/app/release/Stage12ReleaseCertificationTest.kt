package uz.faceguard.app.release

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 12: final-release contract for the shipped artifact.
 *
 * These pin the *release* decisions made in Stage 12 (the chosen production version and
 * the Play-ready release configuration) and keep the certification document honest
 * (it must not claim device/Play results that were not obtained). Behavioural release
 * hardening (R8, permissions, exported surface) is already covered by
 * `Stage10ReleaseHardeningTest` and `Stage11LaunchReadinessTest`; this file does not
 * duplicate it.
 */
class Stage12ReleaseCertificationTest {

    private val appGradle = read("app/build.gradle.kts")
    private val certification = read("docs/STAGE12_FINAL_DEVICE_CERTIFICATION.md")

    // ------------------------------------------------------------ version

    @Test
    fun theProductionVersionIsChosenNotThePlaceholder() {
        // Stage 12 explicitly chose the first production version.
        assertTrue(
            "versionName must be the chosen production version 1.0.0",
            appGradle.contains("val defaultVersionName = \"1.0.0\""),
        )
        assertTrue(
            "versionCode must be 1 for the first production release",
            appGradle.contains("val defaultVersionCode = 1"),
        )
        assertTrue(
            "the version must remain overridable for later Play uploads",
            appGradle.contains("qalqonVersionCode") && appGradle.contains("qalqonVersionName"),
        )
    }

    @Test
    fun theReleaseTargetsApi36AndSupportsApi26() {
        assertTrue(appGradle.contains("compileSdk = 36"))
        assertTrue(appGradle.contains("targetSdk = 36"))
        assertTrue(appGradle.contains("minSdk = 26"))
        assertTrue(appGradle.contains("applicationId = \"uz.faceguard.app\""))
    }

    // ------------------------------------------------- certification honesty

    @Test
    fun theCertificationDocumentDoesNotClaimDeviceOrPlayVerification() {
        val lower = certification.lowercase()
        // It must state the device/Play limitations explicitly...
        assertTrue(
            "certification must state physical device certification is not available",
            lower.contains("physical device certification = not available"),
        )
        assertTrue(
            "certification must state production signing is not verified",
            lower.contains("production signing = not verified"),
        )
        assertTrue(
            "certification must state real billing is not verified",
            lower.contains("real billing = not verified"),
        )
        assertTrue(
            "certification must state anti-spoof is not production-certified",
            lower.contains("not production-certified"),
        )
        assertTrue(
            "certification must report the soak tests as not completed",
            lower.contains("not completed"),
        )
        // ...and must not use a forbidden over-claim.
        listOf("approved by google", "play approved", "100% compatible", "fully certified").forEach { claim ->
            assertTrue("certification must not claim '$claim'", !lower.contains(claim))
        }
    }

    @Test
    fun theCertificationDecisionIsHonest() {
        assertTrue(
            "the final decision must be an explicit PASS WITH LIMITATIONS",
            certification.contains("PASS WITH LIMITATIONS"),
        )
        // Play readiness must be kept as distinct states.
        assertTrue(certification.contains("Play Console verified"))
        assertTrue(certification.contains("Submitted"))
        assertTrue(certification.contains("Approved"))
    }

    // ------------------------------------------------- release blockers

    @Test
    fun noP0OrP1ReleaseBlockerIsDeclared() {
        // The report must explicitly record zero P0/P1 blockers (or list them; if a
        // future change introduces one, this test forces the report to be updated and
        // the release to be re-evaluated).
        val blockers = Regex("\\|\\s*\\*\\*(P0|P1)[^|]*\\*\\*\\s*\\|\\s*\\*\\*(\\d+)\\*\\*")
            .findAll(certification)
            .associate { it.groupValues[1] to it.groupValues[2].toInt() }
        assertEquals("P0 count must be declared", 0, blockers["P0"] ?: -1)
        assertEquals("P1 count must be declared", 0, blockers["P1"] ?: -1)
    }

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
}

package uz.faceguard.app.compliance

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Release signing guard contract.
 *
 * QALQON must never ship an unsigned release artifact. `assembleRelease` already
 * failed without signing, but `bundleRelease` produced an unsigned `.aab` because
 * the guard only matched `assembleRelease`/`packageRelease`. These JVM/static
 * checks pin the guard so that regression cannot return silently: the guard must
 * name every release packaging/lifecycle task, and must match by exact name so
 * non-artifact siblings such as `packageReleaseResources` are not affected.
 *
 * These checks are static only. They prove the configuration; they do not build a
 * signed artifact (no signing material exists in this environment).
 */
class ReleaseSigningGuardContractTest {

    private val appGradle by lazy { read("app/build.gradle.kts") }

    private val guardedTaskNamesBlock: String by lazy {
        val start = appGradle.indexOf("val guardedReleaseTaskNames")
        assertTrue("guardedReleaseTaskNames must be declared", start >= 0)
        val end = appGradle.indexOf(")", start)
        assertTrue("guardedReleaseTaskNames setOf(...) must be closed", end > start)
        appGradle.substring(start, end)
    }

    @Test
    fun theApkPackagingPathIsGuarded() {
        assertTrue(
            "the APK packaging task must be guarded",
            guardedTaskNamesBlock.contains("\"packageRelease\""),
        )
        assertTrue(
            "the APK lifecycle task must be guarded",
            guardedTaskNamesBlock.contains("\"assembleRelease\""),
        )
    }

    @Test
    fun theAabPackagingPathIsGuarded() {
        // The regression this fix closes: the AAB path bypassed the guard.
        assertTrue(
            "the AAB packaging task must be guarded",
            guardedTaskNamesBlock.contains("\"packageReleaseBundle\""),
        )
        assertTrue(
            "the AAB lifecycle task must be guarded",
            guardedTaskNamesBlock.contains("\"bundleRelease\""),
        )
    }

    @Test
    fun theGuardMatchesByExactNameNotByPrefix() {
        // Sibling tasks share the `packageRelease` prefix but produce no installable
        // artifact; guarding them would break legitimate builds.
        assertFalse(
            "the guard must not prefix-match `packageReleaseResources`",
            guardedTaskNamesBlock.contains("packageReleaseResources"),
        )
        assertTrue(
            "the guard must use exact-name membership",
            appGradle.contains("it.name in guardedReleaseTaskNames"),
        )
        assertFalse(
            "the guard must not use a prefix match",
            appGradle.contains("it.name.startsWith(\"packageRelease\")") ||
                appGradle.contains("it.name.startsWith(\"assembleRelease\")") ||
                appGradle.contains("it.name.startsWith(\"bundleRelease\")"),
        )
    }

    @Test
    fun theGuardFailsOnlyWhenSigningIsMissing() {
        // The guard must throw when signing is absent, and must be gated on
        // `!hasReleaseSigning` so a correctly-signed release build is not blocked.
        assertTrue("the guard must throw a GradleException", appGradle.contains("throw GradleException("))
        assertTrue("the guard must be gated on missing signing", appGradle.contains("if (!hasReleaseSigning)"))
    }

    @Test
    fun theGuardNamesTheRequiredSigningInputs() {
        // The error message must tell a developer exactly what to provide.
        listOf(
            "QALQON_RELEASE_STORE_FILE",
            "QALQON_RELEASE_STORE_PASSWORD",
            "QALQON_RELEASE_KEY_ALIAS",
            "QALQON_RELEASE_KEY_PASSWORD",
        ).forEach { env ->
            assertTrue("the guard must name $env", appGradle.contains(env))
        }
        assertTrue(
            "the guard must point to the local.properties fallback",
            appGradle.contains("local.properties"),
        )
    }

    @Test
    fun theSignedReleaseConfigurationStillAppliesWhenSigningExists() {
        // Sanity: the release signing config is only created when a full identity is
        // present, and it is attached to the release build type. This pins that the
        // guard did not replace the signing behaviour.
        assertTrue(appGradle.contains("if (hasReleaseSigning) {"))
        assertTrue(appGradle.contains("signingConfig = signingConfigs.getByName(\"release\")"))
    }

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

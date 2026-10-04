package uz.faceguard.app.docs

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Documentation drift guard.
 *
 * The README and AGENTS.md had drifted from the code (they claimed a destructive
 * Room fallback, no automated tests, and the pre-upgrade toolchain). These assertions
 * tie the docs to the facts the build actually declares, so the next drift is a red
 * test rather than a wrong instruction for the next contributor/agent.
 */
class DocConsistencyTest {

    private val readme = read("README.md")
    private val agents = read("AGENTS.md")
    private val versions = read("gradle/libs.versions.toml")
    private val appGradle = read("app/build.gradle.kts")
    private val appModule = read("app/src/main/java/uz/faceguard/app/di/AppModule.kt")

    @Test
    fun theDocsDoNotClaimADestructiveRoomFallback() {
        assertFalse(
            "README still claims fallbackToDestructiveMigration",
            readme.contains("fallbackToDestructiveMigration"),
        )
        assertFalse(
            "AGENTS.md still claims fallbackToDestructiveMigration",
            agents.contains("fallbackToDestructiveMigration"),
        )
        // …and the code really uses additive migrations (the doc now matches it).
        assertTrue(
            "the database must register the additive migration chain",
            appModule.contains("addMigrations("),
        )
        assertFalse(
            "the database must not use a destructive fallback",
            appModule.contains("fallbackToDestructiveMigration"),
        )
    }

    @Test
    fun theDocsDoNotClaimThereAreNoAutomatedTests() {
        assertFalse(
            "README still claims there are no automated tests",
            readme.contains("No automated tests"),
        )
    }

    @Test
    fun agentsReportsTheToolchainTheBuildActuallyDeclares() {
        assertTrue("AGENTS.md must state the Kotlin version", agents.contains("Kotlin 2.3.21"))
        assertTrue("AGENTS.md must state the Room version", agents.contains("Room 2.7.2"))
        assertTrue("AGENTS.md must state the JVM target", agents.contains("target 17"))

        // Cross-check against the version catalog / Gradle so the doc cannot silently rot.
        assertTrue(versions.contains("kotlin = \"2.3.21\""))
        assertTrue(versions.contains("room = \"2.7.2\""))
        assertTrue(appGradle.contains("VERSION_17"))
    }

    @Test
    fun agentsNoLongerClaimsGradlewIsNotExecutable() {
        assertFalse(
            "AGENTS.md still tells contributors gradlew has no execute bit",
            agents.contains("gradlew` has no execute bit"),
        )
    }

    @Test
    fun theRoomSchemaVersionInTheDocsMatchesTheDatabase() {
        assertTrue(
            "README should describe the current additive migration chain",
            readme.contains("v3 → v10"),
        )
        assertTrue(
            "the database must declare version 10",
            read("app/src/main/java/uz/faceguard/app/data/db/FaceGuardDatabase.kt")
                .contains("version = 10"),
        )
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

package uz.faceguard.app.quality

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 9 test-fortress guard: the unit-test suite must stay deterministic.
 *
 * This is a self-enforcing contract. It fails the build if anyone introduces a known
 * flakiness source into the JVM unit tests — a real wall-clock sleep, a disabled test,
 * nondeterministic randomness, or a process exit. Keeping tests honest this way is
 * stronger than any single assertion: a flaky pattern cannot slip in unnoticed.
 *
 * Scope: `app/src/test` (the JVM suite run on every CI build). The instrumented suite
 * (`app/src/androidTest`) is a separate, device-bound layer and is intentionally not
 * scanned here (it uses `Assume` for genuinely device-only cases, which is documented).
 */
class TestSuiteHygieneTest {

    /** This guard's own file legitimately mentions the forbidden literals; skip it. */
    private val selfName = "TestSuiteHygieneTest.kt"

    /** Forbidden substrings and the reason each is banned from the unit suite. */
    private val forbidden = mapOf(
        "Thread.sleep(" to "a fixed sleep makes a test slow and timing-dependent",
        "@Ignore" to "a disabled test hides a failure instead of fixing it",
        "@Disabled" to "a disabled test hides a failure instead of fixing it",
        "Random(" to "nondeterministic data without a fixed seed makes failures unreproducible",
        "System.exit(" to "a test must never terminate the JVM",
        ".awaitTermination(" to "sleep-driven executor waits are timing-dependent",
    )

    @Test
    fun theUnitTestSuiteContainsNoKnownFlakinessSources() {
        val testRoot = File(repoRoot(), "app/src/test")
        assertTrue("unit test root must exist", testRoot.isDirectory)

        val violations = testRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name != selfName }
            .flatMap { file ->
                val text = file.readText()
                forbidden.entries
                    .filter { (needle, _) -> text.contains(needle) }
                    .map { (needle, reason) -> "${file.name}: contains '$needle' — $reason" }
            }
            .toList()

        assertTrue(
            "forbidden flakiness sources found in unit tests: $violations",
            violations.isEmpty(),
        )
    }

    @Test
    fun everyUnitTestClassDeclaresAtLeastOneTest() {
        // A test class with no @Test is dead weight or a disabled suite in disguise.
        // Only files that declare a test class are checked; shared fixtures are not tests.
        val testRoot = File(repoRoot(), "app/src/test")
        val empty = testRoot.walkTopDown()
            .filter { it.isFile && it.name.endsWith("Test.kt") && it.name != selfName }
            .filter { file ->
                val text = file.readText()
                text.contains("class ") && !text.contains("@Test")
            }
            .map { it.name }
            .toList()
        assertTrue("test classes without any @Test: $empty", empty.isEmpty())
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

package uz.faceguard.app.docs

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SECURITY.md must stay a real threat model.
 *
 * It has to enumerate every bypass vector the repository audit identified, so the
 * honest risk statement cannot silently shrink to a marketing paragraph.
 */
class SecurityThreatModelTest {

    private val security = read("SECURITY.md")

    @Test
    fun theThreatModelEnumeratesEveryKnownBypassVector() {
        listOf(
            "uninstall",
            "clear app data",
            "force stop",
            "safe mode",
            "accessibility service",
            "usage access",
            "overlay",
            "camera covered",
        ).forEach { vector ->
            assertTrue(
                "SECURITY.md must document the '$vector' bypass vector",
                security.contains(vector, ignoreCase = true),
            )
        }
    }

    @Test
    fun theThreatModelStatesResidualRiskAndMitigation() {
        assertTrue("must state residual risk", security.contains("Residual risk", ignoreCase = true))
        assertTrue("must state current mitigation", security.contains("Current mitigation", ignoreCase = true))
        // The honest non-goals must remain explicit.
        assertTrue("must keep the non-goals section", security.contains("Explicit non-goals", ignoreCase = true))
    }

    @Test
    fun theThreatModelKeepsTheUninstallAndForceStopGapsHonest() {
        // These two are accepted gaps today; if a mitigation is ever added the wording
        // must be updated in the same change, not quietly dropped.
        assertTrue(
            "must admit there is no uninstall guard",
            security.contains("no uninstall guard") || security.contains("does not request Device"),
        )
        assertTrue(
            "must admit force stop halts protection and is not bypassed",
            security.contains("Force stop halts background protection"),
        )
        // The restart strategy must be described honestly: a sticky restart is
        // best-effort, distinct from (and never a workaround for) force stop.
        assertTrue(
            "must describe the sticky restart honestly",
            security.contains("START_STICKY"),
        )
        assertTrue(
            "must not claim a force-stop bypass",
            security.contains("never claimed as a", ignoreCase = true),
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

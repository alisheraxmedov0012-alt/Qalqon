package uz.faceguard.app.protection

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guard for the foreground-service **start-timeout** invariant.
 *
 * Android kills the process with
 * `RemoteServiceException$ForegroundServiceDidNotStartInTimeException` if a service
 * started via `Context.startForegroundService()` does not call `startForeground()`
 * quickly. That exception was observed once in CI on a slow emulator; the code already
 * claims foreground *before* the heavy runtime work, and this test keeps it that way so
 * a future reorder cannot reintroduce a process-killing startup crash.
 */
class ServiceStartOrderingTest {

    private val service = read(
        "app/src/main/java/uz/faceguard/app/core/protection/ProtectionForegroundService.kt",
    )

    private fun onCreateBody(): String {
        val start = service.indexOf("override fun onCreate()")
        assertTrue("onCreate must exist", start >= 0)
        val open = service.indexOf('{', start)
        var depth = 0
        var i = open
        while (i < service.length) {
            when (service[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return service.substring(open, i)
                }
            }
            i++
        }
        return service.substring(open)
    }

    @Test
    fun onCreateClaimsForeground() {
        val body = onCreateBody()
        assertTrue(
            "onCreate must claim foreground so the OS cannot kill a slow start",
            body.contains("startForegroundCompat("),
        )
    }

    @Test
    fun foregroundIsClaimedBeforeTheHeavyRuntimeWork() {
        val body = onCreateBody()
        val foreground = body.indexOf("startForegroundCompat(")
        val runtimeStart = body.indexOf("runtime.onServiceStarted()")
        assertTrue("both calls must be present", foreground >= 0 && runtimeStart >= 0)
        assertTrue(
            "startForeground must run before runtime work, or a slow startup crashes the process",
            foreground < runtimeStart,
        )
    }

    @Test
    fun foregroundIsClaimedBeforeTheServiceReportsItselfRunning() {
        val body = onCreateBody()
        val foreground = body.indexOf("startForegroundCompat(")
        val running = body.indexOf("_running.value = true")
        assertTrue("both calls must be present", foreground >= 0 && running >= 0)
        assertTrue(
            "the service must not report running before foreground is established",
            foreground < running,
        )
    }

    @Test
    fun theForegroundCallUsesTheCrossVersionCompatHelper() {
        // ServiceCompat.startForeground handles the API 34+ requirement to pass a
        // foreground-service type; a raw startForeground(int, Notification) would drop it.
        assertTrue(
            "the service must start foreground through ServiceCompat",
            service.contains("ServiceCompat.startForeground("),
        )
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

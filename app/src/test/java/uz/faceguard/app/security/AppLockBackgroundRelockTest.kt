package uz.faceguard.app.security

import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.faceguard.app.core.security.AppLockState

/**
 * Background re-lock of the parent UI.
 *
 * An unlocked session must not stay unlocked for the whole process lifetime: when
 * the app leaves the foreground it re-locks after a short grace period, so a
 * handed-over device returns to the credential gate — but a quick switch away and
 * back inside the grace window keeps the parent's session.
 *
 * The timing tests use a real (tiny) delay with a wide margin rather than a virtual
 * clock, because the project has no `kotlinx-coroutines-test` dependency: the grace
 * is 30 ms and the assertions wait 500 ms, a >10x margin.
 */
class AppLockBackgroundRelockTest {

    private val home = read("core/security/AppLockState.kt")

    // ------------------------------------------------------------------ timing

    @Test
    fun theUiRelocksAfterTheGraceWhenNotCancelled() = runBlocking {
        val lock = AppLockState()
        lock.onAuthenticated()
        assertTrue("precondition: unlocked", lock.isUnlocked())

        lock.lockAfter(this, graceMillis = 30L)
        delay(500L)

        assertFalse("the UI must re-lock once the grace elapses", lock.isUnlocked())
    }

    @Test
    fun aQuickReturnInsideTheGraceKeepsTheSession() = runBlocking {
        val lock = AppLockState()
        lock.onAuthenticated()

        lock.lockAfter(this, graceMillis = 5_000L)
        // Returning immediately (the Activity onStart path) cancels the timer.
        lock.cancelPendingLock()
        delay(200L)

        assertTrue("a quick app switch must not lock the parent out", lock.isUnlocked())
    }

    @Test
    fun aFreshAuthenticationCancelsAPendingRelock() = runBlocking {
        val lock = AppLockState()
        lock.onAuthenticated()
        lock.lockAfter(this, graceMillis = 5_000L)

        // Re-authentication (e.g. the parent unlocks again) wins over the timer.
        lock.onAuthenticated()
        delay(200L)

        assertTrue(lock.isUnlocked())
    }

    @Test
    fun anExplicitLockWinsAndAPendingTimerCannotUndoIt() = runBlocking {
        val lock = AppLockState()
        lock.onAuthenticated()
        lock.lockAfter(this, graceMillis = 5_000L)

        lock.lock()
        delay(100L)

        assertFalse(lock.isUnlocked())
    }

    @Test
    fun aSecondBackgroundSchedulesOnlyOneRelock() = runBlocking {
        val lock = AppLockState()
        lock.onAuthenticated()
        lock.lockAfter(this, graceMillis = 30L)
        lock.lockAfter(this, graceMillis = 30L)
        delay(500L)

        assertFalse(lock.isUnlocked())
    }

    // --------------------------------------------------------------- wiring

    @Test
    fun theGraceIsAShortDocumentedConstant() {
        assertEqualsJvm(15_000L, AppLockState.DEFAULT_BACKGROUND_GRACE_MILLIS)
        assertTrue(
            "the grace must be short enough to re-arm the gate on a real hand-off",
            AppLockState.DEFAULT_BACKGROUND_GRACE_MILLIS <= 60_000L,
        )
    }

    @Test
    fun theActivityWiresTheBackgroundRelockToItsLifecycle() {
        val activity = read("../MainActivity.kt")
        // Leaving the foreground arms the timer; returning cancels it.
        assertTrue(
            "MainActivity.onStop must arm the background re-lock",
            activity.contains("appLockState.lockAfter(lifecycleScope"),
        )
        assertTrue(
            "MainActivity.onStart must cancel a pending re-lock",
            activity.contains("appLockState.cancelPendingLock()"),
        )
    }

    @Test
    fun theHolderExposesTheRelockPrimitivesWithoutOwningNavigation() {
        // The holder schedules/cancels and toggles the flag; the routing gate turns
        // the locked flag into a redirect, so the holder must not know about routes.
        assertTrue(home.contains("fun lockAfter("))
        assertTrue(home.contains("fun cancelPendingLock()"))
        listOf("NavController", "pin_unlock", "Routes.").forEach { forbidden ->
            assertFalse("the lock holder must not own navigation ($forbidden)", home.contains(forbidden))
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun assertEqualsJvm(expected: Long, actual: Long) {
        org.junit.Assert.assertEquals(expected, actual)
    }

    private fun read(relative: String): String {
        val base = File(repoRoot(), "app/src/main/java/uz/faceguard/app")
        val file = if (relative.startsWith("../")) {
            File(base, relative.removePrefix("../"))
        } else {
            File(base, relative)
        }
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

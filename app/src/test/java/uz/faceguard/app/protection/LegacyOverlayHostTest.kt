package uz.faceguard.app.protection

import androidx.lifecycle.Lifecycle
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.faceguard.app.core.protection.OverlayControllerImpl

/**
 * Stage 3 / OEM compatibility regression: the legacy
 * `TYPE_APPLICATION_OVERLAY` scrim is a Compose view hosted in a `WindowManager`
 * overlay, with no Activity or Fragment above it.
 *
 * Without a `ViewTreeLifecycleOwner` (plus the ViewModel store and saved-state
 * registry owners) Compose throws `IllegalStateException: ViewTreeLifecycleOwner
 * not found` the moment the view attaches, so the fallback that is supposed to
 * block (or at least visibly cover) a protected app when the accessibility
 * service is disabled could never render — and, when the attach ran after
 * `addView` returned, could take the whole process down. These tests pin the fix:
 * the owners are installed before the content is set, and they are torn down with
 * the window.
 *
 * The lifecycle owner itself is real (INITIALIZED → RESUMED → DESTROYED) and is
 * never revived, exactly like the process-scoped camera owner.
 */
class LegacyOverlayHostTest {

    private val controller = read("core/protection/OverlayControllerImpl.kt")

    @Test
    fun theComposeOverlayInstallsEveryViewTreeOwner() {
        listOf(
            "setViewTreeLifecycleOwner(owner)",
            "setViewTreeViewModelStoreOwner(owner)",
            "setViewTreeSavedStateRegistryOwner(owner)",
        ).forEach { call ->
            assertTrue("the legacy overlay must install $call", controller.contains(call))
        }
    }

    @Test
    fun theOwnersAreInstalledBeforeTheContentIsSet() {
        // Attaching the view is what makes Compose resolve (and require) the owners,
        // so they must already be set when setContent runs.
        assertTrue(
            "owners must be installed before setContent",
            controller.indexOf("setViewTreeLifecycleOwner(owner)") in
                0 until controller.indexOf("setContent {"),
        )
    }

    @Test
    fun theOwnerIsTornDownWithTheWindowOnBothPaths() {
        // Success path: hideLegacy destroys the owner of the removed window.
        assertTrue(
            "the removed window's owner must be destroyed",
            controller.contains("owner?.destroy()"),
        )
        // Failure path: a window that was never added must not leak its owner.
        assertTrue(
            "a rejected window must not leak its owner",
            controller.contains("owner.destroy()"),
        )
    }

    @Test
    fun theOwnerDrivesARealLifecycle() {
        val owner = OverlayControllerImpl.OverlayWindowOwner()

        assertEquals(Lifecycle.State.INITIALIZED, owner.lifecycle.currentState)
        owner.create()
        assertEquals(Lifecycle.State.RESUMED, owner.lifecycle.currentState)
        owner.destroy()
        assertEquals(Lifecycle.State.DESTROYED, owner.lifecycle.currentState)
    }

    @Test
    fun createAndDestroyAreIdempotentAndNeverReviveTheOwner() {
        val owner = OverlayControllerImpl.OverlayWindowOwner()

        owner.create()
        owner.create()
        assertEquals(Lifecycle.State.RESUMED, owner.lifecycle.currentState)

        owner.destroy()
        owner.destroy()
        assertEquals(Lifecycle.State.DESTROYED, owner.lifecycle.currentState)

        owner.create()
        assertEquals(
            "a destroyed overlay owner must never be revived",
            Lifecycle.State.DESTROYED,
            owner.lifecycle.currentState,
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

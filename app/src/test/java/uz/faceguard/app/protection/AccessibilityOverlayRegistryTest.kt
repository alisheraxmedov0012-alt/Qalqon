package uz.faceguard.app.protection

import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import uz.faceguard.app.core.accessibility.AccessibilityOverlayHost
import uz.faceguard.app.core.accessibility.AccessibilityOverlayRegistry

private class FakeOverlayHost : AccessibilityOverlayHost {
    override fun showBlockingOverlay(): Boolean = true
    override fun hideBlockingOverlay(): Boolean = true
}

/**
 * Phase 7.2: the one-way registry that lets the runtime's overlay controller reach
 * the connected accessibility service without creating a service/runtime cycle.
 */
class AccessibilityOverlayRegistryTest {

    private val registry = AccessibilityOverlayRegistry()

    @Test
    fun startsEmpty() {
        assertNull(registry.current())
    }

    @Test
    fun register_makesTheHostCurrent() {
        val host = FakeOverlayHost()
        registry.register(host)
        assertSame(host, registry.current())
    }

    @Test
    fun unregister_clearsOnlyTheRegisteredInstance() {
        val host = FakeOverlayHost()
        val other = FakeOverlayHost()
        registry.register(host)

        // A late unbind of a different (old) instance must not remove the live one.
        registry.unregister(other)
        assertSame(host, registry.current())

        registry.unregister(host)
        assertNull(registry.current())
    }
}

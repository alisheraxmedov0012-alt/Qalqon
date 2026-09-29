package uz.faceguard.app.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.faceguard.app.core.diagnostics.PlatformCapabilities
import uz.faceguard.app.core.diagnostics.RuntimeHealthMapper
import uz.faceguard.app.core.protection.ProtectionRuntimeState
import uz.faceguard.app.domain.diagnostics.ScheduleSyncMechanism

/**
 * Phase 12: the pure runtime -> snapshot mapping behind the diagnostics screen.
 *
 * The audit must never disagree with the parent-facing protection screen, so the
 * runtime signals come straight from [ProtectionRuntimeState]; only the
 * background-service liveness is supplied separately (it lives on the service,
 * not the runtime state).
 */
class RuntimeHealthMapperTest {

    @Test
    fun runtimeStateFieldsAreCopiedVerbatim() {
        val runtime = ProtectionRuntimeState(
            enabled = true,
            overlayGranted = true,
            usageAccessGranted = false,
            accessibilityEnabled = true,
            notificationsEnabled = false,
        )

        val snapshot = RuntimeHealthMapper.snapshot(
            runtime = runtime,
            signedIn = true,
            foregroundServiceRunning = true,
        )

        assertEquals(true, snapshot.signedIn)
        assertEquals(true, snapshot.protectionEnabled)
        assertEquals(true, snapshot.foregroundServiceRunning)
        assertEquals(true, snapshot.overlayGranted)
        assertEquals(true, snapshot.accessibilityEnabled)
        assertEquals(false, snapshot.usageAccessGranted)
        assertEquals(false, snapshot.notificationsEnabled)
    }

    @Test
    fun platformDefaultsComeFromTheWiringFacts() {
        val snapshot = RuntimeHealthMapper.snapshot(
            runtime = ProtectionRuntimeState(enabled = true),
            signedIn = true,
            foregroundServiceRunning = true,
        )

        assertEquals(PlatformCapabilities.BOOT_RESTORE_WIRED, snapshot.bootRestoreWired)
        assertEquals(PlatformCapabilities.SCHEDULE_SYNC_MECHANISM, snapshot.scheduleSyncMechanism)
        assertTrue(snapshot.bootRestoreWired)
        assertEquals(ScheduleSyncMechanism.EVENT_DRIVEN, snapshot.scheduleSyncMechanism)
    }

    @Test
    fun primitiveOverloadPreservesEveryField() {
        val snapshot = RuntimeHealthMapper.snapshot(
            signedIn = true,
            protectionEnabled = true,
            foregroundServiceRunning = false,
            overlayGranted = false,
            accessibilityEnabled = true,
            usageAccessGranted = true,
            notificationsEnabled = true,
            bootRestoreWired = false,
            scheduleSyncMechanism = ScheduleSyncMechanism.WORK_MANAGER,
        )

        assertEquals(true, snapshot.signedIn)
        assertEquals(true, snapshot.protectionEnabled)
        assertEquals(false, snapshot.foregroundServiceRunning)
        assertEquals(false, snapshot.overlayGranted)
        assertEquals(true, snapshot.accessibilityEnabled)
        assertEquals(true, snapshot.usageAccessGranted)
        assertEquals(true, snapshot.notificationsEnabled)
        assertEquals(false, snapshot.bootRestoreWired)
        assertEquals(ScheduleSyncMechanism.WORK_MANAGER, snapshot.scheduleSyncMechanism)
    }

    @Test
    fun protectionRequestedRequiresBothSignInAndTheIntent() {
        val signedIn = RuntimeHealthMapper.snapshot(
            signedIn = true,
            protectionEnabled = true,
            foregroundServiceRunning = true,
            overlayGranted = true,
            accessibilityEnabled = true,
            usageAccessGranted = true,
            notificationsEnabled = true,
        )
        assertTrue(signedIn.protectionRequested)

        val signedOut = RuntimeHealthMapper.snapshot(
            signedIn = false,
            protectionEnabled = true,
            foregroundServiceRunning = false,
            overlayGranted = false,
            accessibilityEnabled = false,
            usageAccessGranted = false,
            notificationsEnabled = false,
        )
        assertEquals(false, signedOut.protectionRequested)
    }
}

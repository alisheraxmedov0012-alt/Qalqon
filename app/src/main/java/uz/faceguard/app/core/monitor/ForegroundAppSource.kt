package uz.faceguard.app.core.monitor

import kotlinx.coroutines.flow.StateFlow

/**
 * The foreground-app signal the protection engine evaluates against.
 *
 * Reliability seam: [ProtectionEngine] only ever *reads* the current foreground
 * package, but it used to depend on the concrete [ForegroundAppMonitor], whose
 * constructor needs an Android `Context`. That made the whole state machine
 * untestable off-device — the engine's identity/app-switch/recovery invariants
 * could only be checked by an emulator-backed instrumented test.
 *
 * Depending on this narrow read-only contract instead lets the engine (and its
 * reliability invariants) be exercised by fast, deterministic JVM tests, without
 * changing any production behaviour: the runtime still constructs the same
 * [ForegroundAppMonitor] and passes it here.
 */
interface ForegroundAppSource {
    /** The current foreground package, or null when it is unknown. */
    val current: StateFlow<String?>
}

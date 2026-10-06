package uz.faceguard.app.domain.diagnostics

import uz.faceguard.app.domain.oem.OemFamily

/**
 * Phase 12: the individual system checks Qalqon audits before it claims protection
 * is actually working.
 *
 * Every check maps to exactly one runtime prerequisite or one background
 * mechanism; none of them is a UI concept, so this whole package stays free of
 * Android, Room, Compose and coroutines and is unit-testable on the JVM.
 */
enum class DiagnosticCheck {
    /** Protection is switched on and an account is signed in. */
    PROTECTION_INTENT,

    /** The background protection binding (foreground service) is alive. */
    FOREGROUND_SERVICE,

    /** A blocking window mechanism exists (draw-over-other-apps overlay fallback). */
    OVERLAY_PERMISSION,

    /** The accessibility guardrail is enabled — the only true input block. */
    ACCESSIBILITY_GUARDRAIL,

    /** Usage Access is granted, so the foreground app can be observed. */
    USAGE_ACCESS,

    /** Notifications are enabled, so blocking/ongoing notices are visible. */
    NOTIFICATIONS,

    /** Protection is restored after a reboot (BOOT_COMPLETED receiver wired). */
    BOOT_RESTORE,

    /** A mechanism exists that keeps schedule/scan state synchronised in the background. */
    SCHEDULE_SYNC,

    /**
     * Stage 6: the OS will not battery-optimize QALQON. A **recommended** capability —
     * its absence degrades reliability but is never a failure.
     */
    BATTERY_OPTIMIZATION,

    /**
     * Stage 6: whether the device's OEM has a known background-restriction surface the
     * user may need to allow. Guidance only; the app cannot read the OEM's own state.
     */
    OEM_BACKGROUND,
}

/**
 * Outcome of one check.
 *
 * [FAILED] means protection cannot work as promised; [WARNING] means it works in
 * a reduced/degraded form; [UNKNOWN] means the check could not be evaluated
 * (e.g. nobody is signed in), which is deliberately distinct from "bad".
 * [UNAVAILABLE] means the capability does not apply on this platform/device (e.g.
 * battery optimization below API 23) — it is neither healthy nor degraded.
 */
enum class DiagnosticStatus { OK, WARNING, FAILED, UNKNOWN, UNAVAILABLE }

data class DiagnosticFinding(
    val check: DiagnosticCheck,
    val status: DiagnosticStatus,
    /** Optional machine-readable reason, never localized UI text. */
    val detail: String? = null,
)

/** Overall audit verdict derived from the individual findings. */
enum class SystemHealthLevel { HEALTHY, DEGRADED, CRITICAL, UNKNOWN }

data class SystemHealthReport(
    val level: SystemHealthLevel,
    val findings: List<DiagnosticFinding>,
) {
    /** Checks that actively break the protection promise. */
    val blocker: List<DiagnosticFinding>
        get() = findings.filter { it.status == DiagnosticStatus.FAILED }

    /** Checks that still allow protection but in a degraded form. */
    val warnings: List<DiagnosticFinding>
        get() = findings.filter { it.status == DiagnosticStatus.WARNING }

    fun statusOf(check: DiagnosticCheck): DiagnosticStatus? =
        findings.firstOrNull { it.check == check }?.status

    fun isReady(): Boolean = level == SystemHealthLevel.HEALTHY
}

/**
 * How Qalqon keeps its schedule/scan state in sync while backgrounded.
 *
 * The production design is [EVENT_DRIVEN] ([uz.faceguard.app.core.scan.ScanScheduler]
 * on the existing 500ms engine tick): the app is offline-first and must not add
 * periodic OS wake-ups, so WorkManager is intentionally never used. The enum
 * exists so the auditor can *report* that choice instead of hiding it.
 */
enum class ScheduleSyncMechanism { EVENT_DRIVEN, WORK_MANAGER, NONE }

/**
 * An immutable reading of the device/runtime facts the auditor needs.
 *
 * Primitives only, so the evaluator stays pure: the Android adapter
 * (`core.diagnostics`) is the single place that reads the real process/service
 * state and turns it into this value.
 */
data class SystemHealthSnapshot(
    val signedIn: Boolean,
    val protectionEnabled: Boolean,
    val foregroundServiceRunning: Boolean,
    val overlayGranted: Boolean,
    val accessibilityEnabled: Boolean,
    val usageAccessGranted: Boolean,
    val notificationsEnabled: Boolean,
    val bootRestoreWired: Boolean,
    val scheduleSyncMechanism: ScheduleSyncMechanism,
    /**
     * Stage 6: whether the OS will not battery-optimize QALQON. `null` means the
     * concept does not apply on this platform (below API 23) — reported as
     * [DiagnosticStatus.UNAVAILABLE]. Defaults to `true` so a device that has not
     * reported it is not falsely flagged as optimized-away.
     */
    val batteryOptimizationIgnored: Boolean? = true,
    /**
     * Stage 6: the detected OEM family. Defaults to [OemFamily.UNKNOWN], which carries
     * no guidance and audits as OK.
     */
    val oemFamily: OemFamily = OemFamily.UNKNOWN,
    /**
     * Stage 6: true when an OEM-specific guidance page is known for [oemFamily]. Only
     * used to word the OEM check; it never turns the check into a failure.
     */
    val oemGuidanceAvailable: Boolean = false,
) {
    /** True when protection is supposed to be running right now. */
    val protectionRequested: Boolean get() = signedIn && protectionEnabled
}

package uz.faceguard.app.domain.diagnostics

/**
 * Phase 12: the pure system-health auditor.
 *
 * It turns one [SystemHealthSnapshot] into one [SystemHealthReport] with an
 * entry per [DiagnosticCheck]. It performs no I/O, holds no state and reads no
 * Android API, so the exact rules the app runs are the rules the JVM tests
 * verify.
 *
 * The single rule that gives the audit meaning: a check is only **FAILED** when
 * protection is actually requested but cannot be delivered. A device that is
 * signed out, or that intentionally has protection switched off, reports
 * `UNKNOWN` for the runtime prerequisites instead of pretending they passed.
 */
class SystemHealthEvaluator {

    fun evaluate(snapshot: SystemHealthSnapshot): SystemHealthReport {
        // Nothing can be asserted about a device with no signed-in account, so every
        // check is UNKNOWN rather than a misleading "healthy" or "failed".
        if (!snapshot.signedIn) {
            return SystemHealthReport(
                level = SystemHealthLevel.UNKNOWN,
                findings = DiagnosticCheck.entries.map { unknown(it, NOT_SIGNED_IN) },
            )
        }
        val findings = DiagnosticCheck.entries.map { check -> evaluate(check, snapshot) }
        return SystemHealthReport(level = levelOf(findings), findings = findings)
    }

    private fun evaluate(check: DiagnosticCheck, s: SystemHealthSnapshot): DiagnosticFinding =
        when (check) {
            DiagnosticCheck.PROTECTION_INTENT ->
                if (s.protectionEnabled) ok(check) else warning(check, PROTECTION_OFF)

            DiagnosticCheck.FOREGROUND_SERVICE -> when {
                !s.protectionEnabled -> unknown(check, PROTECTION_OFF)
                s.foregroundServiceRunning -> ok(check)
                else -> failed(check, SERVICE_NOT_BOUND)
            }

            DiagnosticCheck.OVERLAY_PERMISSION -> when {
                !s.protectionEnabled -> unknown(check, PROTECTION_OFF)
                s.overlayGranted || s.accessibilityEnabled -> ok(check)
                else -> failed(check, NO_BLOCKING_WINDOW)
            }

            DiagnosticCheck.ACCESSIBILITY_GUARDRAIL ->
                if (s.accessibilityEnabled) ok(check) else warning(check, ACCESSIBILITY_OFF)

            DiagnosticCheck.USAGE_ACCESS -> when {
                !s.protectionEnabled -> unknown(check, PROTECTION_OFF)
                s.usageAccessGranted -> ok(check)
                // Accessibility already supplies window transitions, so a missing
                // usage-stats app-op degrades rather than breaks enforcement.
                s.accessibilityEnabled -> warning(check, USAGE_MISSING_ACCESSIBILITY_COVERS)
                else -> failed(check, USAGE_MISSING)
            }

            DiagnosticCheck.NOTIFICATIONS ->
                if (s.notificationsEnabled) ok(check) else warning(check, NOTIFICATIONS_OFF)

            DiagnosticCheck.BOOT_RESTORE ->
                if (s.bootRestoreWired) ok(check) else failed(check, BOOT_RESTORE_MISSING)

            DiagnosticCheck.SCHEDULE_SYNC ->
                if (s.scheduleSyncMechanism == ScheduleSyncMechanism.NONE) {
                    warning(check, SCHEDULE_SYNC_MISSING)
                } else {
                    ok(check)
                }

            // Stage 6: battery optimization is *recommended*, never required. Its
            // absence is a warning at most; a platform without the concept is
            // UNAVAILABLE rather than a false judgement.
            DiagnosticCheck.BATTERY_OPTIMIZATION -> when {
                !s.protectionEnabled -> unknown(check, PROTECTION_OFF)
                s.batteryOptimizationIgnored == null -> unavailable(check, BATTERY_NOT_APPLICABLE)
                s.batteryOptimizationIgnored -> ok(check)
                else -> warning(check, BATTERY_OPTIMIZED)
            }

            // Stage 6: OEM background management cannot be read by the app, so this is
            // guidance only — a warning for families known to restrict background work,
            // never a failure, and OK where no restriction surface is known.
            DiagnosticCheck.OEM_BACKGROUND -> when {
                !s.protectionEnabled -> unknown(check, PROTECTION_OFF)
                !s.oemFamily.hasKnownBackgroundRestrictions -> ok(check)
                s.oemGuidanceAvailable -> warning(check, OEM_RESTRICTION_KNOWN)
                else -> warning(check, OEM_RESTRICTION_GENERIC)
            }
        }

    private fun levelOf(findings: List<DiagnosticFinding>): SystemHealthLevel = when {
        findings.any { it.status == DiagnosticStatus.FAILED } -> SystemHealthLevel.CRITICAL
        findings.any { it.status == DiagnosticStatus.WARNING } -> SystemHealthLevel.DEGRADED
        else -> SystemHealthLevel.HEALTHY
    }

    private fun ok(check: DiagnosticCheck) = DiagnosticFinding(check, DiagnosticStatus.OK)
    private fun warning(check: DiagnosticCheck, detail: String) =
        DiagnosticFinding(check, DiagnosticStatus.WARNING, detail)

    private fun failed(check: DiagnosticCheck, detail: String) =
        DiagnosticFinding(check, DiagnosticStatus.FAILED, detail)

    private fun unknown(check: DiagnosticCheck, detail: String) =
        DiagnosticFinding(check, DiagnosticStatus.UNKNOWN, detail)

    private fun unavailable(check: DiagnosticCheck, detail: String) =
        DiagnosticFinding(check, DiagnosticStatus.UNAVAILABLE, detail)

    private companion object {
        const val NOT_SIGNED_IN = "not_signed_in"
        const val PROTECTION_OFF = "protection_off"
        const val SERVICE_NOT_BOUND = "foreground_service_not_bound"
        const val NO_BLOCKING_WINDOW = "no_overlay_or_accessibility"
        const val ACCESSIBILITY_OFF = "accessibility_guardrail_off"
        const val USAGE_MISSING = "usage_access_missing"
        const val USAGE_MISSING_ACCESSIBILITY_COVERS = "usage_access_missing_accessibility_covers"
        const val NOTIFICATIONS_OFF = "notifications_disabled"
        const val BOOT_RESTORE_MISSING = "boot_completed_receiver_missing"
        const val SCHEDULE_SYNC_MISSING = "no_schedule_sync_mechanism"
        const val BATTERY_NOT_APPLICABLE = "battery_optimization_not_applicable"
        const val BATTERY_OPTIMIZED = "battery_optimization_active"
        const val OEM_RESTRICTION_KNOWN = "oem_background_restriction_known"
        const val OEM_RESTRICTION_GENERIC = "oem_background_restriction_generic"
    }
}

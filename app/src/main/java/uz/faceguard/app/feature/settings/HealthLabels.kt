package uz.faceguard.app.feature.settings

import uz.faceguard.app.R
import uz.faceguard.app.domain.diagnostics.DiagnosticCheck
import uz.faceguard.app.domain.diagnostics.DiagnosticStatus
import uz.faceguard.app.domain.diagnostics.SystemHealthLevel

/**
 * Phase 12: presentation mappings for the system-health tab.
 *
 * Pure functions returning string-resource ids, so the visible text always comes
 * from `strings.xml` (never a hardcoded literal) and the exhaustive `when` makes a
 * new check/status/level a compile error until it is given a label.
 */

fun SystemHealthLevel.labelRes(): Int = when (this) {
    SystemHealthLevel.HEALTHY -> R.string.health_level_healthy
    SystemHealthLevel.DEGRADED -> R.string.health_level_degraded
    SystemHealthLevel.CRITICAL -> R.string.health_level_critical
    SystemHealthLevel.UNKNOWN -> R.string.health_level_unknown
}

fun DiagnosticCheck.labelRes(): Int = when (this) {
    DiagnosticCheck.PROTECTION_INTENT -> R.string.health_check_protection_intent
    DiagnosticCheck.FOREGROUND_SERVICE -> R.string.health_check_foreground_service
    DiagnosticCheck.OVERLAY_PERMISSION -> R.string.health_check_overlay
    DiagnosticCheck.ACCESSIBILITY_GUARDRAIL -> R.string.health_check_accessibility
    DiagnosticCheck.USAGE_ACCESS -> R.string.health_check_usage_access
    DiagnosticCheck.NOTIFICATIONS -> R.string.health_check_notifications
    DiagnosticCheck.BOOT_RESTORE -> R.string.health_check_boot_restore
    DiagnosticCheck.SCHEDULE_SYNC -> R.string.health_check_schedule_sync
    DiagnosticCheck.BATTERY_OPTIMIZATION -> R.string.health_check_battery_optimization
    DiagnosticCheck.OEM_BACKGROUND -> R.string.health_check_oem_background
}

fun DiagnosticStatus.labelRes(): Int = when (this) {
    DiagnosticStatus.OK -> R.string.health_status_ok
    DiagnosticStatus.WARNING -> R.string.health_status_warning
    DiagnosticStatus.FAILED -> R.string.health_status_failed
    DiagnosticStatus.UNKNOWN -> R.string.health_status_unknown
    DiagnosticStatus.UNAVAILABLE -> R.string.health_status_unavailable
}

package uz.faceguard.app.core.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * QALQON semantic colors — the meaning-carrying palette (success, warning, info,
 * protection, capability, child, request).
 *
 * Material's color roles do not cover product semantics such as "protection is
 * blocking" or "capability missing", which is why screens ended up with inline
 * `Color(0xFF…)` literals (e.g. the health levels in Settings). Those literals now
 * have exactly one home here, with a light and a dark mapping, so no screen has to
 * invent a color again.
 *
 * Values are deliberately restrained: a calm, trustworthy security product, not a
 * colorful dashboard.
 */
data class QalqonSemanticColors(
    // Transport/state semantics
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val warning: Color,
    val onWarning: Color,
    val warningContainer: Color,
    val info: Color,
    val onInfo: Color,
    val infoContainer: Color,

    // Protection state
    val protectionActive: Color,
    val protectionInactive: Color,
    val protectionBlocking: Color,
    val protectionWarning: Color,

    // Capability / permission state
    val capabilityGranted: Color,
    val capabilityMissing: Color,

    // Child setup state
    val childConfigured: Color,
    val childNeedsSetup: Color,

    // Request lifecycle
    val requestPending: Color,
    val requestApproved: Color,
    val requestDenied: Color,

    // Home hero illustration + primary CTA (reference mockup palette).
    // The shield fill / stroke and the CTA are deliberately a brighter, more saturated
    // blue than `primary`, so the hero illustration and its action read as one vivid
    // accent. The danger container/text style the inactive-metric nudge.
    val illustrationShield: Color,
    val illustrationStroke: Color,
    val cta: Color,
    val onCta: Color,
    val dangerContainer: Color,
    val onDangerContainer: Color,
)

/** Light-theme semantic mapping. */
val LightQalqonSemanticColors = QalqonSemanticColors(
    success = Color(0xFF2E7D32),
    onSuccess = Color(0xFFFFFFFF),
    successContainer = Color(0xFFDCEFDC),
    warning = Color(0xFFB26A00),
    onWarning = Color(0xFFFFFFFF),
    warningContainer = Color(0xFFFFEFD4),
    info = Color(0xFF1E5B9E),
    onInfo = Color(0xFFFFFFFF),
    infoContainer = Color(0xFFDCE8F7),

    protectionActive = Color(0xFF2E7D32),
    protectionInactive = Color(0xFF6B7280),
    protectionBlocking = Color(0xFFC62828),
    protectionWarning = Color(0xFFB26A00),

    capabilityGranted = Color(0xFF2E7D32),
    capabilityMissing = Color(0xFFC62828),

    childConfigured = Color(0xFF2E7D32),
    childNeedsSetup = Color(0xFFB26A00),

    requestPending = Color(0xFFB26A00),
    requestApproved = Color(0xFF2E7D32),
    requestDenied = Color(0xFFC62828),

    // Reference mockup: soft pastel shield, vivid blue stroke, deep blue CTA, soft red nudge.
    illustrationShield = Color(0xFFDCE8FF),
    illustrationStroke = Color(0xFF2563EB),
    cta = Color(0xFF1D61E0),
    onCta = Color(0xFFFFFFFF),
    dangerContainer = Color(0xFFFEE2E2),
    onDangerContainer = Color(0xFFDC2626),
)

/** Dark-theme semantic mapping (lighter, less saturated for dark surfaces). */
val DarkQalqonSemanticColors = QalqonSemanticColors(
    success = Color(0xFF8FD694),
    onSuccess = Color(0xFF0B1E10),
    successContainer = Color(0xFF1B3A1E),
    warning = Color(0xFFF0C070),
    onWarning = Color(0xFF2A1C00),
    warningContainer = Color(0xFF3E2E08),
    info = Color(0xFF9EC5F5),
    onInfo = Color(0xFF0B1E33),
    infoContainer = Color(0xFF1B3350),

    protectionActive = Color(0xFF8FD694),
    protectionInactive = Color(0xFF9AA3AF),
    protectionBlocking = Color(0xFFF2A9A4),
    protectionWarning = Color(0xFFF0C070),

    capabilityGranted = Color(0xFF8FD694),
    capabilityMissing = Color(0xFFF2A9A4),

    childConfigured = Color(0xFF8FD694),
    childNeedsSetup = Color(0xFFF0C070),

    requestPending = Color(0xFFF0C070),
    requestApproved = Color(0xFF8FD694),
    requestDenied = Color(0xFFF2A9A4),

    // Dark equivalents: a deep shield, a lighter stroke, a readable blue CTA and a
    // muted red nudge for dark surfaces.
    illustrationShield = Color(0xFF1B3350),
    illustrationStroke = Color(0xFF9EC5F5),
    cta = Color(0xFF3B82F6),
    onCta = Color(0xFF08182E),
    dangerContainer = Color(0xFF3A1D20),
    onDangerContainer = Color(0xFFF2A9A4),
)

/**
 * The active semantic palette. Provided once by `FaceGuardTheme`; read it with
 * [QalqonTheme.colors] so components never hardcode a status color.
 */
val LocalQalqonSemanticColors = staticCompositionLocalOf { LightQalqonSemanticColors }

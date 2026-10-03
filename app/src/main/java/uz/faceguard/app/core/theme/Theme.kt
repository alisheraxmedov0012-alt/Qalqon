package uz.faceguard.app.core.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color

/**
 * The QALQON color schemes.
 *
 * The values that already existed (`primary`, `onPrimary`, `secondary`,
 * `background`, `surface`, `error` in light; `primary`, `onPrimary`, `secondary` in
 * dark) are preserved **exactly**, so no current screen changes appearance. The
 * remaining Material roles are filled in explicitly so components can rely on them
 * instead of guessing.
 */
private val LightColors = lightColorScheme(
    primary = Color(0xFF1E5B9E),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE8F7),
    onPrimaryContainer = Color(0xFF0B1E33),
    secondary = Color(0xFF4E7CAE),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE3EDF7),
    onSecondaryContainer = Color(0xFF152A40),
    // A very light blue canvas (not pure white, not grey) so the white cards read as
    // distinct premium surfaces floating on it.
    background = Color(0xFFEEF3FB),
    onBackground = Color(0xFF1A1C1E),
    surface = Color.White,
    onSurface = Color(0xFF1A1C1E),
    surfaceVariant = Color(0xFFEDF1F6),
    onSurfaceVariant = Color(0xFF5A6068),
    outline = Color(0xFF8A9099),
    outlineVariant = Color(0xFFD6DBE1),
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    // M3 surface-container hierarchy (premium tonal layering). `surfaceContainerLowest`
    // is the brightest card surface; the rest step down towards the screen background so
    // screens can layer cards, sheets and the anchored bottom bar without raw colors.
    surfaceDim = Color(0xFFDDE2E9),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7F9FC),
    surfaceContainer = Color(0xFFF1F4F9),
    surfaceContainerHigh = Color(0xFFEBEFF5),
    surfaceContainerHighest = Color(0xFFE5EAF1),
    surfaceTint = Color(0xFF1E5B9E),
    scrim = Color(0xFF000000),
    inverseSurface = Color(0xFF2E3238),
    inverseOnSurface = Color(0xFFF1F3F6),
    inversePrimary = Color(0xFF9EC5F5),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9EC5F5),
    onPrimary = Color(0xFF0B1E33),
    primaryContainer = Color(0xFF1B3350),
    onPrimaryContainer = Color(0xFFDCE8F7),
    secondary = Color(0xFF8FB8E8),
    onSecondary = Color(0xFF0B1E33),
    secondaryContainer = Color(0xFF2A3B4D),
    onSecondaryContainer = Color(0xFFE3EDF7),
    background = Color(0xFF121417),
    onBackground = Color(0xFFE2E2E5),
    surface = Color(0xFF1B1E22),
    onSurface = Color(0xFFE2E2E5),
    surfaceVariant = Color(0xFF2A2F35),
    onSurfaceVariant = Color(0xFFB4BAC2),
    outline = Color(0xFF8A9099),
    outlineVariant = Color(0xFF3A4048),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
    // Dark equivalents, so the same components layer correctly in dark theme.
    surfaceDim = Color(0xFF121417),
    surfaceBright = Color(0xFF383C42),
    surfaceContainerLowest = Color(0xFF0D0F12),
    surfaceContainerLow = Color(0xFF1B1E22),
    surfaceContainer = Color(0xFF1F2328),
    surfaceContainerHigh = Color(0xFF2A2F35),
    surfaceContainerHighest = Color(0xFF33383F),
    surfaceTint = Color(0xFF9EC5F5),
    scrim = Color(0xFF000000),
    inverseSurface = Color(0xFFE2E2E5),
    inverseOnSurface = Color(0xFF2E3238),
    inversePrimary = Color(0xFF1E5B9E),
)

/** Shape scale wired into Material so all components share the radius hierarchy. */
private val QalqonMaterialShapes = Shapes(
    extraSmall = QalqonShapes.smallShape,
    small = QalqonShapes.smallShape,
    medium = QalqonShapes.mediumShape,
    large = QalqonShapes.largeShape,
    extraLarge = QalqonShapes.largeShape,
)

/**
 * The QALQON design system entry point.
 *
 * Applies the color schemes, the [QalqonTypography] scale, the shape scale and the
 * semantic palette. It replaces the previous theme body — the same four typography
 * values, the same light/dark colors, plus the design-system additions — so
 * existing screens render identically while new components gain tokens.
 *
 * The name stays `FaceGuardTheme` (legacy, referenced by `MainActivity` and every
 * screen) to avoid a repository-wide rename in this phase.
 */
@Composable
fun FaceGuardTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colorScheme = if (dark) DarkColors else LightColors
    val semanticColors = if (dark) DarkQalqonSemanticColors else LightQalqonSemanticColors

    CompositionLocalProvider(LocalQalqonSemanticColors provides semanticColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = QalqonTypography,
            shapes = QalqonMaterialShapes,
            content = content,
        )
    }
}

/**
 * Token accessor used by components, so a screen never writes a status color
 * literal: `QalqonTheme.colors.success`.
 */
object QalqonTheme {
    val colors: QalqonSemanticColors
        @Composable
        @ReadOnlyComposable
        get() = LocalQalqonSemanticColors.current

    /** Alias expressing intent: `QalqonTheme.semantic.protectionActive`. */
    val semantic: QalqonSemanticColors
        @Composable
        @ReadOnlyComposable
        get() = LocalQalqonSemanticColors.current
}

/**
 * Convenience grouping of the whole token scale, so future refactors can reference
 * `QalqonDimens.spacing.lg` from one import.
 */
object QalqonDimens {
    val spacing = QalqonSpacing
    val shapes = QalqonShapes
    val elevation = QalqonElevation
    val icon = QalqonIconSize
    val sizes = QalqonSizes

    /** Default card corner radius, so screens do not invent one. */
    val cardCorner = QalqonShapes.medium

    /** Default hairline border width. */
    val cardBorder = QalqonSizes.border

    /** Standard screen padding. */
    val screenPadding = QalqonSpacing.xl

    /** Standard card inner padding. */
    val cardPadding = QalqonSpacing.lg

    /** Standard list-row vertical padding. */
    val rowPadding = QalqonSpacing.md
}

package uz.faceguard.app.core.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * QALQON design tokens — the single source of truth for spacing, shape, elevation,
 * icon and control sizing.
 *
 * Replaces the ad-hoc `16.dp` / `24.dp` / `12.dp` literals that were sprinkled
 * across the screens with one rhythm, so new (and later refactored) UI stays
 * visually consistent. Pure values only: no Material, no Android, no screen logic,
 * which is what makes them directly unit-testable.
 *
 * Nothing here changes behaviour; screens opt in over time.
 */

/** Spacing rhythm: 0 / 4 / 8 / 12 / 16 / 24 / 32. */
object QalqonSpacing {
    /** 0 dp — no padding (e.g. a grouped card whose rows carry their own inset). */
    val none: Dp = 0.dp

    /** 4 dp — hairline separation (icon ↔ label). */
    val xs: Dp = 4.dp

    /** 8 dp — inside a control or between tightly related lines. */
    val sm: Dp = 8.dp

    /** 12 dp — between list rows / form fields. */
    val md: Dp = 12.dp

    /** 16 dp — the standard card/container padding. */
    val lg: Dp = 16.dp

    /** 24 dp — screen padding, or between distinct groups. */
    val xl: Dp = 24.dp

    /** 32 dp — major section separation. */
    val xxl: Dp = 32.dp
}

/** Corner-radius hierarchy: 8 / 12 / 16 / pill. */
object QalqonShapes {
    val small: Dp = 8.dp
    val medium: Dp = 12.dp
    val large: Dp = 16.dp
    val pill: Dp = 999.dp

    val smallShape = RoundedCornerShape(small)
    val mediumShape = RoundedCornerShape(medium)
    val largeShape = RoundedCornerShape(large)
    val pillShape = RoundedCornerShape(pill)
}

/**
 * Three-level elevation only. A security/productivity UI earns its hierarchy from
 * spacing, typography, surface contrast and borders — not from heavy shadows — so
 * `raised` is deliberately soft.
 */
object QalqonElevation {
    val flat: Dp = 0.dp
    val raised: Dp = 1.dp
    val modal: Dp = 6.dp
}

/** Icon sizes. Icon-only controls must still be at least [QalqonSizes.touchTarget]. */
object QalqonIconSize {
    val xs: Dp = 16.dp
    val sm: Dp = 20.dp
    val md: Dp = 24.dp
    val lg: Dp = 32.dp
}

/** Control sizing and the accessibility floor. */
object QalqonSizes {
    /** Minimum touch target required by the accessibility guidelines. */
    val touchTarget: Dp = 48.dp

    val buttonCompact: Dp = 36.dp
    val buttonDefault: Dp = 48.dp
    val buttonLarge: Dp = 56.dp

    /** Status dot / small indicator. */
    val indicator: Dp = 8.dp

    /** Child avatar diameter. */
    val avatar: Dp = 40.dp

    /** Hairline border. */
    val border: Dp = 1.dp

    /** Progress stroke for compact spinners. */
    val strokeThin: Dp = 2.dp
}

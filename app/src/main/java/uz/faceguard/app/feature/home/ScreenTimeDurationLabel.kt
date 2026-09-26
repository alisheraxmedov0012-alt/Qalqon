package uz.faceguard.app.feature.home

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import uz.faceguard.app.R

/**
 * Renders a duration from exact milliseconds using the single [ScreenTimeDurationFormat].
 *
 * Shared by the Home summary and the per-app rows so a duration is formatted in exactly one way
 * everywhere: the formatter does the arithmetic, and this turns its parts into localized text.
 * Nothing here rounds differently per caller, so `90 seconds` cannot become "90 min" on one
 * screen and "1 min" on another.
 */
@Composable
internal fun durationLabel(ms: Long): String {
    val parts = ScreenTimeDurationFormat.partsOf(ms)
    return when {
        parts.hours > 0L && parts.minutes > 0L ->
            stringResource(R.string.screentime_duration_hours_minutes, parts.hours, parts.minutes)
        parts.hours > 0L -> stringResource(R.string.screentime_duration_hours, parts.hours)
        else -> stringResource(R.string.screentime_duration_minutes, parts.minutes)
    }
}

/** Same formatting for a limit, which the domain stores in whole minutes. */
@Composable
internal fun durationLabelMinutes(minutes: Int): String =
    durationLabel(minutes.toLong() * ScreenTimeDurationFormat.MS_PER_MINUTE)

package uz.faceguard.app.core.debug

import uz.faceguard.app.BuildConfig

/**
 * Dev-only diagnostics switch.
 *
 * Phase 14: this is now derived from [BuildConfig.DEBUG] instead of a hardcoded
 * `true`, so the developer section (recognition debug screen + foreground
 * inspector) is present in debug/development builds and absent from the release
 * build. Previously the flag was `true`, which shipped developer-only screens to
 * end users in release.
 */
object DebugFlags {
    val DEBUG_SCREENS_ENABLED: Boolean = BuildConfig.DEBUG
}

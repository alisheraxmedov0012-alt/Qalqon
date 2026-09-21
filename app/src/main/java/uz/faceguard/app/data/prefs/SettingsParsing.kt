package uz.faceguard.app.data.prefs

import uz.faceguard.app.domain.model.AppSettings

/**
 * Pure parsing/validation helpers for persisted settings.
 *
 * Kept free of Android/DataStore types so they can be unit-tested on the JVM
 * while still being the exact code the real store uses.
 *
 * Corruption policy: an unreadable value must **never** silently switch
 * protection to a more permissive state. Unknown enums and out-of-range numbers
 * fall back to the documented product default (identical to a fresh install),
 * which for `unknownUserPolicy` is SOFT_BLOCK (not ALLOW).
 */

/** Upper bound accepted for `recoveryDelayMs` (1 hour); larger values are treated as corrupt. */
internal const val MAX_RECOVERY_DELAY_MS: Long = 3_600_000L

/** Account namespacing prefix, e.g. `acc_7_unknown_user_policy`. */
internal const val ACCOUNT_KEY_PREFIX = "acc"

internal fun scopedSettingsKeyName(accountId: Long, baseName: String): String =
    "${ACCOUNT_KEY_PREFIX}_${accountId}_$baseName"

/** Parses a stored enum name; unknown or null -> [fallback]. Never throws. */
internal fun <T : Enum<T>> parseEnumValue(raw: String?, fallback: T): T {
    if (raw == null) return fallback
    return runCatching { enumValueOf<T>(raw) }.getOrDefault(fallback)
}

/**
 * `recoveryDelayMs` is stored in milliseconds. Values outside `1..MAX` are
 * treated as corrupt and replaced by the product default.
 */
internal fun validRecoveryDelayMs(raw: Long?, default: Long = AppSettings.DEFAULT_RECOVERY_DELAY_MS): Long {
    val value = raw ?: return default
    return if (value in 1..MAX_RECOVERY_DELAY_MS) value else default
}

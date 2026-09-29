package uz.faceguard.app.domain.security

import java.util.Locale

/**
 * Typed outcome of a PIN check (the parent emergency-unlock path).
 *
 * It replaces a bare `Boolean` so the UI can distinguish "wrong PIN" from "too
 * many attempts, wait N seconds" and show the remaining lockout time. It is
 * deliberately account-agnostic: an unknown/absent account maps to [InvalidPin]
 * rather than a distinct state, so a caller can never use this result to probe
 * whether an account exists (the same no-enumeration rule as login).
 */
sealed interface PinVerification {

    /** The PIN matched and no lockout is active. */
    data object Success : PinVerification

    /** The PIN did not match (also covers "no account"), and the caller is not locked out. */
    data object InvalidPin : PinVerification

    /**
     * Attempts are temporarily locked out. [remainingMillis] is how much longer the
     * caller must wait; it is always in `1..` while locked so the UI never shows a
     * zero timer for an active lockout.
     */
    data class LockedOut(val remainingMillis: Long) : PinVerification
}

/**
 * Formats a lockout duration as `M:SS` for display (e.g. `0:30`, `15:00`).
 *
 * Pure and locale-independent (digits and a colon), so it is unit-testable and the
 * localized wording lives in string resources. Partial seconds round **up** so an
 * active lockout never renders as `0:00`.
 */
fun formatLockoutRemaining(remainingMillis: Long): String {
    val totalSeconds = (remainingMillis.coerceAtLeast(0L) + 999L) / 1_000L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
}

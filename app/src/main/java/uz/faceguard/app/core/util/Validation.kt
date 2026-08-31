package uz.faceguard.app.core.util

import uz.faceguard.app.R

/**
 * Local input validation/normalization helpers. No network involved.
 */
object Validation {

    /** Digits-only representation used for storage and comparison (strips '+', spaces, dashes, parentheses). */
    fun normalizePhone(raw: String): String = raw.filter(Char::isDigit)

    fun isValidPhone(phone: String): Boolean = normalizePhone(phone).length in 7..15

    fun isValidFullName(name: String): Boolean = name.trim().length >= 3

    /** PINs may be 4 or 6 digits. Anything else is malformed. */
    fun isValidPin(pin: String): Boolean =
        (pin.length == PIN_MIN || pin.length == PIN_MAX) && pin.all(Char::isDigit)

    fun pinErrorResFor(pin: String): Int? = when {
        pin.isEmpty() -> null
        pin.length != PIN_MIN && pin.length != PIN_MAX -> R.string.error_pin_length
        !pin.all(Char::isDigit) -> R.string.error_invalid_pin
        else -> null
    }

    private const val PIN_MIN = 4
    private const val PIN_MAX = 6

    const val MAX_PHONE_DIGITS = 15
    const val MAX_PIN_DIGITS = PIN_MAX
}

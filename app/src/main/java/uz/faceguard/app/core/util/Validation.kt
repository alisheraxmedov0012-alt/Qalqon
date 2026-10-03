package uz.faceguard.app.core.util

import uz.faceguard.app.R

/**
 * Local input validation/normalization helpers. No network involved.
 */
object Validation {

    /** Digits-only representation used for storage and comparison (strips '+', spaces, dashes, parentheses). */
    fun normalizePhone(raw: String): String = raw.filter(Char::isDigit)

    fun isValidPhone(phone: String): Boolean = normalizePhone(phone).length in 7..15

    /**
     * A display name must be a real name, not a phone number. A phone-like value
     * (only digits, or digits with the usual phone punctuation) previously passed the
     * old "length >= 3" check, so a phone could be stored as the parent's name and then
     * shown as the Home greeting. It is rejected here so bad data can never be persisted.
     */
    fun isValidFullName(name: String): Boolean {
        val trimmed = name.trim()
        return trimmed.length >= 3 && !isPhoneLike(trimmed)
    }

    /**
     * True when [raw] carries no letters and reads as a phone number (at least 3 digits,
     * with only digits and the usual separators). Pure and unit-testable.
     */
    fun isPhoneLike(raw: String): Boolean {
        val trimmed = raw.trim()
        if (trimmed.any { it.isLetter() }) return false
        val digits = trimmed.count(Char::isDigit)
        return digits >= 3 && trimmed.all { it.isDigit() || it in PHONE_SEPARATORS }
    }

    private val PHONE_SEPARATORS = setOf(' ', '+', '-', '(', ')', '.', '/')

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

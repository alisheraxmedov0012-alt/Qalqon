package uz.faceguard.app.domain.oem

/**
 * Stage 6: pure OEM detection from the standard Android build strings.
 *
 * `Build.MANUFACTURER`, `Build.BRAND` and `Build.MODEL` are read once by the
 * Android adapter (`core.oem.AndroidOemDetector`) and normalised here, so the
 * decision is deterministic, JVM-testable and cheap (no per-frame work — the
 * adapter caches it for the process lifetime).
 *
 * Ordering rules that matter:
 *  - sub-brands are resolved **before** their parent (Redmi/POCO before Xiaomi,
 *    Honor before Huawei, iQOO before Vivo), so a Redmi device never reports as
 *    plain Xiaomi;
 *  - matching is case-insensitive and trimmed, so `XIAOMI`/`xiaomi`/`Xiaomi` are
 *    one family;
 *  - an unrecognised device is [OemFamily.UNKNOWN] — never a guess.
 */
object OemDetector {

    /**
     * Resolves the family from the three standard build strings. Any argument may
     * be null/blank (some OEM builds report an empty brand), in which case it is
     * simply not matched.
     */
    fun detect(
        manufacturer: String?,
        brand: String?,
        model: String?,
    ): OemFamily {
        // A single normalised haystack is enough: sub-brand tokens appear in the
        // brand or model, and the parent token in the manufacturer. Keeping them in
        // one string also makes "POCO X3" / brand="POCO" equivalent for detection.
        val haystack = listOf(manufacturer, brand, model)
            .mapNotNull { it?.lowercase()?.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(" ")

        if (haystack.isBlank()) return OemFamily.UNKNOWN

        return when {
            // Independent brands first.
            containsAny(haystack, "honor", "hihonor") -> OemFamily.HONOR
            containsAny(haystack, "huawei") -> OemFamily.HUAWEI

            // Xiaomi sub-brands before Xiaomi itself.
            containsAny(haystack, "poco") -> OemFamily.POCO
            containsAny(haystack, "redmi") -> OemFamily.REDMI
            containsAny(haystack, "xiaomi") -> OemFamily.XIAOMI

            // BBK / Shenzhen family.
            containsAny(haystack, "oneplus", "one plus") -> OemFamily.ONEPLUS
            containsAny(haystack, "realme") -> OemFamily.REALME
            containsAny(haystack, "oppo") -> OemFamily.OPPO
            containsAny(haystack, "vivo", "iqoo") -> OemFamily.VIVO

            // Everything else.
            containsAny(haystack, "samsung") -> OemFamily.SAMSUNG
            containsAny(haystack, "motorola", "moto") -> OemFamily.MOTOROLA
            containsAny(haystack, "google") -> OemFamily.GOOGLE

            else -> OemFamily.UNKNOWN
        }
    }

    /** Word-ish containment: avoids matching "moto" inside an unrelated token. */
    private fun containsAny(haystack: String, vararg tokens: String): Boolean =
        tokens.any { token -> haystack == token || haystack.contains(token) }
}

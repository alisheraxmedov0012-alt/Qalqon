package uz.faceguard.app.domain.oem

/**
 * Stage 6: the OEM families QALQON recognises for compatibility guidance.
 *
 * Redmi and POCO are Xiaomi sub-brands in practice, but they are modelled as
 * distinct families because their settings surfaces and background restrictions
 * differ per model and users/QA report them separately. [OemDetector] resolves a
 * concrete device to exactly one family.
 *
 * This enum only ever influences *compatibility guidance* — never recognition,
 * policy or enforcement. An unknown family is a first-class value, not an error.
 */
enum class OemFamily {
    SAMSUNG,
    XIAOMI,
    REDMI,
    POCO,
    OPPO,
    ONEPLUS,
    VIVO,
    REALME,
    HONOR,
    HUAWEI,
    MOTOROLA,
    GOOGLE,
    UNKNOWN;

    /**
     * True when the family is known to ship aggressive background management that
     * commonly stops a parental-control service unless the user whitelists the app
     * (autostart + battery). Used to decide whether to show *guidance*, never to
     * claim the restriction is definitely present.
     */
    val hasKnownBackgroundRestrictions: Boolean
        get() = when (this) {
            XIAOMI, REDMI, POCO, OPPO, ONEPLUS, VIVO, REALME, HONOR, HUAWEI -> true
            SAMSUNG, MOTOROLA, GOOGLE, UNKNOWN -> false
        }
}

/**
 * A settings page identified by an explicit component.
 *
 * OEM settings activities are not part of any public API and change between OS
 * versions, so a component is only ever a *candidate*: the Android layer resolves
 * it before launching and skips it when it does not exist. Hard-coding these as
 * "this works" is exactly what this stage avoids.
 */
data class ComponentSpec(
    val packageName: String,
    val className: String,
) {
    val flattened: String get() = "$packageName/$className"
}

/**
 * The compatibility settings pages QALQON may offer to open.
 */
enum class OemSettingsTarget {
    /** OEM "autostart" / "startup manager" — allow the app to launch itself. */
    AUTOSTART,

    /** OEM app-specific battery/background management page. */
    BACKGROUND_RESTRICTION,

    /** The platform battery-optimization list (no special permission required). */
    BATTERY_OPTIMIZATION,
}

/**
 * How much QALQON knows about a family's settings surfaces.
 */
enum class OemSupportLevel {
    /** At least one OEM-specific candidate page is known for this family. */
    SUPPORTED,

    /** No OEM-specific page is known; the generic platform pages are used. */
    GENERIC,
}

/**
 * A family's compatibility profile: the ordered candidate pages QALQON will try
 * for each target. Every candidate is resolved before use and the caller always
 * falls back to the generic platform settings, so an unresolved candidate can
 * never dead-end the user.
 */
data class OemCompatibilityProfile(
    val family: OemFamily,
    val supportLevel: OemSupportLevel,
    val autostartCandidates: List<ComponentSpec> = emptyList(),
    val backgroundCandidates: List<ComponentSpec> = emptyList(),
) {
    /** The candidates for [target], in the order they should be attempted. */
    fun candidatesFor(target: OemSettingsTarget): List<ComponentSpec> = when (target) {
        OemSettingsTarget.AUTOSTART -> autostartCandidates
        OemSettingsTarget.BACKGROUND_RESTRICTION -> backgroundCandidates
        OemSettingsTarget.BATTERY_OPTIMIZATION -> emptyList()
    }
}

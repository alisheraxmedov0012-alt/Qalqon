package uz.faceguard.app.core.accessibility

/**
 * Group 7: collapses the accessibility event stream into meaningful foreground
 * transitions.
 *
 * Accessibility can deliver the same window state many times per second (and
 * from several windows), so the runtime only needs to react when the foreground
 * package actually changes. Pure and Android-free so it is unit-testable.
 */
class AccessibilityForegroundTracker {

    private var last: String? = null

    /** true when [packageName] is a new, non-blank foreground transition. */
    fun onForegroundPackage(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        if (packageName == last) return false
        last = packageName
        return true
    }

    /** Clears the baseline, e.g. when the accessibility service is unbound. */
    fun reset() {
        last = null
    }
}

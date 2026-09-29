package uz.faceguard.app.core.accessibility

import android.view.accessibility.AccessibilityEvent

/**
 * Group 7 privacy boundary for the accessibility layer.
 *
 * QALQON only reacts to foreground window/app transitions. Text changes, view
 * clicks, notification content, window-content changes and every other event
 * type are ignored, and the service config keeps
 * `canRetrieveWindowContent="false"`, so screen content is never readable — let
 * alone stored.
 */
object AccessibilityEventFilter {

    fun isRelevant(eventType: Int): Boolean = when (eventType) {
        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
        AccessibilityEvent.TYPE_WINDOWS_CHANGED,
        -> true

        else -> false
    }

    /**
     * Phase 7.2: true when [packageName] is QALQON's own package, i.e. an event
     * produced by one of QALQON's own windows — the protection UI or the blocking
     * accessibility overlay.
     *
     * The blocking overlay is a full-screen window; its own window transitions
     * reach the same event stream. Adopting them as "the foreground app" would
     * look like the protected app has left the foreground, which would release the
     * block and hide the very overlay that produced them (self-interception /
     * flicker). QALQON's own package is never a protected app, so ignoring it is
     * always correct.
     */
    fun isOwnPackage(packageName: String?, ownPackage: String?): Boolean =
        !packageName.isNullOrBlank() && !ownPackage.isNullOrBlank() && packageName == ownPackage
}

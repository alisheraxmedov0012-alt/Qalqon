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
}

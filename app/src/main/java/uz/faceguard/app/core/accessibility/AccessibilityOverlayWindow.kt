package uz.faceguard.app.core.accessibility

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import uz.faceguard.app.R
import uz.faceguard.app.core.compat.PlatformCompat

/**
 * Phase 7.2: the real `TYPE_ACCESSIBILITY_OVERLAY` blocking window.
 *
 * Unlike the legacy `TYPE_APPLICATION_OVERLAY` fallback (which is
 * `FLAG_NOT_TOUCHABLE`, i.e. visual only), this window is **touchable**: it
 * covers the screen and consumes every touch, so the protected app underneath
 * receives nothing. It carries the same visual as before (a ~92% black scrim, the
 * existing message and the existing extra-time button) so the child-facing UI is
 * unchanged; only the input architecture is new.
 *
 * The window deliberately keeps `FLAG_NOT_FOCUSABLE`: the overlay must not take
 * input focus away from the app (that would disturb IME, hardware keys and BACK
 * behaviour) — touch interception does not require focus. It is created with the
 * [ProtectionAccessibilityService]'s own context/WindowManager, which is what
 * makes the accessibility window type legal, and every operation is guarded and
 * must run on the main thread (the protection engine's own dispatcher).
 */
class AccessibilityOverlayWindow(
    private val context: Context,
    private val windowManager: WindowManager,
    /**
     * Phase 11 seam: the child-facing request action. Null disables the button
     * (e.g. when no account is signed in). The callback belongs to the runtime.
     */
    private val onRequestExtraTime: (() -> Unit)?,
) : BlockingOverlayWindow {

    private var view: View? = null

    override fun attach(): Boolean {
        if (view != null) return true
        val root = buildRootView()
        return runCatching {
            windowManager.addView(root, layoutParams())
            view = root
            true
        }.getOrElse { false }
    }

    override fun detach() {
        val current = view ?: return
        view = null
        runCatching { windowManager.removeView(current) }
    }

    private fun buildRootView(): View = FrameLayout(context).apply {
        // Same visual as the legacy overlay: 92% black scrim, centred content.
        setBackgroundColor(Color.argb(235, 0, 0, 0))
        isClickable = true
        isFocusable = false
        // Consume every touch: the protected app underneath must receive nothing.
        // Block 3 (ClickableViewAccessibility): a touch listener that returns true is
        // still expected to surface a click to accessibility services, so a completed
        // touch (ACTION_UP) also calls performClick(). The listener keeps returning true,
        // so the touch is still consumed and the protected app still receives nothing.
        setOnTouchListener { view, event ->
            if (event.action == MotionEvent.ACTION_UP) view.performClick()
            true
        }

        addView(
            buildContent(),
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ),
        )
    }

    private fun buildContent(): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dpToPx(24), dpToPx(24), dpToPx(24), dpToPx(24))

        addView(
            TextView(context).apply {
                text = context.getString(R.string.protection_overlay_message)
                setTextColor(Color.WHITE)
                textSize = 20f
                gravity = Gravity.CENTER
            },
        )

        if (onRequestExtraTime != null) {
            addView(
                Button(context).apply {
                    text = context.getString(R.string.request_extra_time)
                    setOnClickListener {
                        // Local, per-overlay feedback: one request per blocking cycle.
                        isEnabled = false
                        text = context.getString(R.string.request_extra_time_sent)
                        onRequestExtraTime.invoke()
                    }
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dpToPx(16) },
            )
        }
    }

    private fun dpToPx(dp: Int): Int =
        (dp * context.resources.displayMetrics.density).toInt()

    companion object {
        /**
         * The blocking window's layout parameters. Kept here (and exposed) so the
         * touchable, non-focusable accessibility-overlay contract is asserted by
         * the instrumented test without needing a bound service.
         */
        fun layoutParams(): WindowManager.LayoutParams {
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                // Touchable (no FLAG_NOT_TOUCHABLE); not focusable so IME/keys/BACK are
                // not captured. FLAG_LAYOUT_IN_SCREEN gives full-screen coverage, and
                // FLAG_SECURE keeps the blocking window out of screenshots/Recents.
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_SECURE,
                PixelFormat.TRANSLUCENT,
            )
            // Stage 7: FLAG_LAYOUT_IN_SCREEN alone does not cover the display cutout
            // area on API 28+; without this a notched device would show a strip of the
            // protected app above the block. The mode is a pure PlatformCompat decision.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                params.layoutInDisplayCutoutMode =
                    PlatformCompat.fullscreenOverlayCutoutMode(Build.VERSION.SDK_INT)
            }
            return params
        }
    }
}

package uz.faceguard.app.core.protection

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import uz.faceguard.app.R
import uz.faceguard.app.core.accessibility.AccessibilityOverlayRegistry
import uz.faceguard.app.core.i18n.AppLanguage
import uz.faceguard.app.core.i18n.LocalizedApp

/**
 * Protection overlay, routed to whichever window mechanism can actually block.
 *
 * Phase 7.2: when QALQON's accessibility service is connected it owns a touchable
 * `TYPE_ACCESSIBILITY_OVERLAY` window ([AccessibilityOverlayHost]) that *consumes*
 * the child's touches, so the protected app underneath receives nothing. That is
 * the real input block. The controller only decides *that* a block is required;
 * the platform window lives in the accessibility layer.
 *
 * Fallback: with the accessibility service disabled, the legacy
 * `TYPE_APPLICATION_OVERLAY` window (requiring SYSTEM_ALERT_WINDOW) is used
 * exactly as before — a visible, `FLAG_NOT_TOUCHABLE` scrim. It is an honest
 * visual fallback, **not** a true input-blocking guarantee; the app reports it as
 * such.
 */
class OverlayControllerImpl(
    private val context: Context,
    /**
     * Phase 7.2: the connected accessibility overlay host, if any. Its presence is
     * what upgrades the fallback scrim into real touch blocking.
     */
    private val accessibilityOverlays: AccessibilityOverlayRegistry,
    /**
     * Phase 11: the child-facing "request extra time" action. Null disables the
     * button (e.g. when no account is signed in). The callback is supplied by the
     * runtime, which owns the account and the currently blocked package.
     */
    private val onRequestExtraTime: (() -> Unit)? = null,
    /**
     * The selected application language, so the blocking overlay is shown in the
     * parent's chosen language. Reads the same single store as the rest of the UI.
     */
    private val languageProvider: () -> AppLanguage? = { null },
) : ProtectionEngine.OverlayController {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayView: View? = null

    /**
     * The lifecycle/ViewModel/saved-state owners the legacy Compose overlay needs.
     *
     * A [ComposeView] hosted in a `WindowManager` overlay has no Activity or Fragment
     * above it, so Compose cannot find a `ViewTreeLifecycleOwner` and throws
     * `IllegalStateException: ViewTreeLifecycleOwner not found` the moment it attaches.
     * That made the fallback scrim unable to render (and, when the deferred attach ran
     * after `addView` returned, could crash the process). Installing these owners on the
     * view is the documented fix for a Service-hosted Compose overlay.
     */
    private var overlayOwner: OverlayWindowOwner? = null

    fun hasPermission(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

    fun permissionIntent(): android.content.Intent =
        android.content.Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)

    override fun show() {
        // Phase 7.2: prefer the touchable accessibility overlay — it is the only
        // mechanism that actually blocks the protected app's input. If a host is
        // present we never also add the legacy window (that would double-block).
        val host = accessibilityOverlays.current()
        if (host != null) {
            host.showBlockingOverlay()
            return
        }
        showLegacy()
    }

    override fun hide() {
        // Hide whichever window is up; both calls are idempotent, and hiding the
        // accessibility overlay also covers a host that reconnected mid-cycle.
        accessibilityOverlays.current()?.hideBlockingOverlay()
        hideLegacy()
    }

    private fun showLegacy() {
        if (!hasPermission()) return
        if (overlayView != null) return
        // The owners must be installed *before* the view attaches: attaching is what
        // triggers Compose to resolve (and require) them.
        val owner = OverlayWindowOwner()
        val view = ComposeView(context).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setContent {
                LocalizedApp(languageProvider()) {
                    ProtectionOverlay(onRequestExtraTime = onRequestExtraTime)
                }
            }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                // Keep the blocking scrim out of screenshots and the Recents thumbnail.
                WindowManager.LayoutParams.FLAG_SECURE,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }
        owner.create()
        // A WindowManager failure (an invalid window token after a process/overlay
        // reconnect, or a window already gone) must never crash the caller and must
        // never leave a stale reference: a non-null `overlayView` whose view is not
        // actually attached would make every later show() a silent no-op and
        // permanently disable the block for the rest of the process.
        runCatching { windowManager.addView(view, params) }
            .onSuccess {
                overlayView = view
                overlayOwner = owner
            }
            .onFailure {
                // Never leak the owner of a window that was not added.
                owner.destroy()
                Log.w(TAG, "legacy blocking overlay could not be shown", it)
            }
    }

    private fun hideLegacy() {
        val view = overlayView ?: return
        // Clear the reference first, so a failing removeView cannot leave the stale
        // reference behind (which would block every future show()).
        overlayView = null
        val owner = overlayOwner
        overlayOwner = null
        runCatching { windowManager.removeView(view) }
            .onFailure { Log.w(TAG, "legacy blocking overlay could not be removed", it) }
        // The window is gone, so tear the Compose host's lifecycle down too.
        owner?.destroy()
    }

    /**
     * The owners a Service-hosted [ComposeView] must expose so Compose can run outside
     * an Activity. A real lifecycle (RESUMED while the window is up, DESTROYED once it
     * is removed) plus a ViewModel store and saved-state registry, so the overlay
     * composable works and is fully torn down with the window.
     */
    internal class OverlayWindowOwner :
        LifecycleOwner,
        ViewModelStoreOwner,
        SavedStateRegistryOwner {

        private val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry

        override val viewModelStore: ViewModelStore = ViewModelStore()

        private val savedStateController = SavedStateRegistryController.create(this)
        override val savedStateRegistry: SavedStateRegistry
            get() = savedStateController.savedStateRegistry

        private var created = false
        private var destroyed = false

        fun create() {
            if (created || destroyed) return
            created = true
            savedStateController.performRestore(null)
            registry.currentState = Lifecycle.State.RESUMED
        }

        fun destroy() {
            if (destroyed) return
            destroyed = true
            registry.currentState = Lifecycle.State.DESTROYED
            viewModelStore.clear()
        }
    }

    private companion object {
        const val TAG = "OverlayController"
    }
}

@Composable
private fun ProtectionOverlay(onRequestExtraTime: (() -> Unit)?) {
    // Local, per-overlay-instance feedback: the button can be tapped once per
    // protection cycle and reflects that a request was actually created.
    var requested by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.92f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(24.dp),
        ) {
            Text(
                text = stringResource(R.string.protection_overlay_message),
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
            )
            if (onRequestExtraTime != null) {
                Button(
                    onClick = {
                        requested = true
                        onRequestExtraTime()
                    },
                    enabled = !requested,
                ) {
                    Text(
                        stringResource(
                            if (requested) R.string.request_extra_time_sent
                            else R.string.request_extra_time,
                        ),
                    )
                }
            }
        }
    }
}

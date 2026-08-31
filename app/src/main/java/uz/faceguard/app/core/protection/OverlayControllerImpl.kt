package uz.faceguard.app.core.protection

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import uz.faceguard.app.R

/**
 * WindowManager-based overlay. Requires SYSTEM_ALERT_WINDOW on API 26+.
 * On modern Android this is a special-permission gate; the Settings card
 * explains how to grant it. Interaction blocking is best-effort via
 * FLAG_NOT_TOUCHABLE on the overlay view.
 */
class OverlayControllerImpl(
    private val context: Context,
) : ProtectionEngine.OverlayController {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayView: View? = null

    fun hasPermission(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

    fun permissionIntent(): android.content.Intent =
        android.content.Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)

    override fun show() {
        if (!hasPermission()) return
        if (overlayView != null) return
        val view = ComposeView(context).apply {
            setContent { ProtectionOverlay() }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }
        windowManager.addView(view, params)
        overlayView = view
    }

    override fun hide() {
        overlayView?.let { windowManager.removeView(it) }
        overlayView = null
    }
}

@Composable
private fun ProtectionOverlay() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.92f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(R.string.protection_overlay_message),
            style = MaterialTheme.typography.titleLarge,
            color = Color.White,
            modifier = Modifier.padding(24.dp),
        )
    }
}

package uz.faceguard.app.core.ui

import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import uz.faceguard.app.core.i18n.findComponentActivity

/**
 * Flags the hosting window `FLAG_SECURE` for as long as this composable is in the
 * tree, so a screen holding sensitive material (a PIN is being typed) is excluded
 * from screenshots, screen recording and the Recents thumbnail.
 *
 * It resolves the Activity from the composition's context, so it works from any
 * screen without the caller passing a window. The flag is cleared on dispose, so
 * it never leaks onto a following screen in the same window.
 */
@Composable
fun SecureScreenFlag() {
    val context = LocalContext.current
    DisposableEffect(context) {
        val window = context.findComponentActivity()?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}

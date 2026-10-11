package uz.faceguard.app.core.legal

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import uz.faceguard.app.R

/**
 * Opens one of QALQON's public legal/support links using the system handler.
 *
 * If [url] is not configured (the legal site is not hosted yet) or no app on the device can
 * handle it, an honest "not available yet" message is shown instead of failing silently or
 * opening a fabricated address. This is the single place the app launches a legal link, so
 * the behaviour is identical from the Premium consent screen and Settings.
 */
fun Context.openLegalLink(
    url: String?,
    unavailableMessageRes: Int = R.string.legal_link_unavailable,
) {
    if (!LegalLinks.isConfigured(url)) {
        Toast.makeText(this, unavailableMessageRes, Toast.LENGTH_LONG).show()
        return
    }
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(this, unavailableMessageRes, Toast.LENGTH_LONG).show()
    }
}

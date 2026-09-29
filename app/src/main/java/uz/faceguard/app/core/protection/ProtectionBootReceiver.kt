package uz.faceguard.app.core.protection

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Phase 7.4: the boot adapter. When the device finishes booting it asks the
 * existing [ProtectionBootRestorer] to restore protection from the persisted
 * intent — and nothing else.
 *
 * It creates no engine, recognizer, camera, overlay or scheduler; it only
 * triggers the existing startup path through the app's own singleton graph
 * (Hilt-injected). The receiver holds a pending result (`goAsync`) until the
 * restore attempt completes, so the service start is not lost if the receiver
 * returns first.
 *
 * `android.intent.action.BOOT_COMPLETED` is a protected broadcast: only the
 * system may send it, and QALQON's restore is idempotent regardless, so a stray
 * trigger is harmless.
 */
@AndroidEntryPoint
class ProtectionBootReceiver : BroadcastReceiver() {

    @Inject lateinit var restorer: ProtectionBootRestorer

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val pendingResult = goAsync()
        restorer.restoreAfterBoot { pendingResult.finish() }
    }
}

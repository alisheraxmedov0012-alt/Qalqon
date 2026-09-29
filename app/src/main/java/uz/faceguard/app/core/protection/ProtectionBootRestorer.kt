package uz.faceguard.app.core.protection

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import uz.faceguard.app.domain.repository.AccountRepository
import uz.faceguard.app.domain.repository.SettingsRepository

/**
 * Phase 7.4: restores protection after the process (re)starts — in particular
 * after a device reboot.
 *
 * It is deliberately **only a decision + a trigger**, nothing else:
 *
 * ```
 * BOOT_COMPLETED → ProtectionBootReceiver → ProtectionBootRestorer
 *                → ProtectionServiceLauncher → ProtectionForegroundService
 *                → (existing) ProtectionRuntime → camera/monitor/engine
 * ```
 *
 * It reads exactly one thing — the *persisted protection intent*
 * (`protectionEnabled` for the signed-in account) — and, when protection should be
 * running, re-runs the **existing** startup path by starting the existing
 * [ProtectionForegroundService]. That service already owns the app-scoped
 * [ProtectionRuntime], the camera session and the accessibility overlay, and all of
 * them are idempotent, so a repeated trigger can never create a second runtime,
 * service, camera session or overlay.
 *
 * Transient runtime state is deliberately **not** restored: the foreground app,
 * the recognition result, the overlay, the camera session and the recovery timer
 * are all recomputed by the (new) runtime from live signals. Nothing here touches
 * identity or the engine.
 *
 * Failure isolation: every read and the start itself are guarded, so a missing
 * setting, a DataStore error or a rejected service start can never crash the app
 * during boot. A rejected start is already reported by the Android launcher
 * ([AndroidProtectionServiceLauncher]); this class deliberately stays free of
 * Android APIs so it remains unit-testable.
 */
@Singleton
class ProtectionBootRestorer @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val accountRepository: AccountRepository,
    private val serviceLauncher: ProtectionServiceLauncher,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Fire-and-forget restore for the boot receiver. [onFinished] runs once the
     * attempt completes so the receiver can release its pending result
     * (`goAsync`), keeping the process alive until the service start is issued.
     */
    fun restoreAfterBoot(onFinished: () -> Unit = {}) {
        scope.launch {
            try {
                restore()
            } finally {
                onFinished()
            }
        }
    }

    /**
     * Reads the persistent protection intent and, when protection should be
     * running, requests the existing service start. Returns true when a start was
     * requested. Never throws.
     *
     * The rule is the existing [ProtectionServicePolicy.shouldRun] — the same one
     * the runtime uses — so "protection ON + signed in" is the single definition of
     * "protection should be running", and a protection-OFF device is never
     * auto-enabled.
     */
    suspend fun restore(): Boolean {
        val enabled = runCatching { settingsRepository.settings.first().protectionEnabled }
            .getOrElse { false }
        val accountId = runCatching { accountRepository.currentAccountId.first() }
            .getOrNull()

        if (!ProtectionServicePolicy.shouldRun(enabled, accountId)) return false

        return runCatching { serviceLauncher.start() }.isSuccess
    }
}

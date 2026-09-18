package uz.faceguard.app.core.protection

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import uz.faceguard.app.core.monitor.ForegroundAppMonitor
import uz.faceguard.app.core.recognition.Recognizer
import uz.faceguard.app.core.scan.ScanScheduler
import uz.faceguard.app.domain.model.ChildProfile
import uz.faceguard.app.domain.model.ParentProfile
import uz.faceguard.app.domain.model.ScanMode
import uz.faceguard.app.domain.repository.AccountRepository
import uz.faceguard.app.domain.repository.ActivityLogRepository
import uz.faceguard.app.domain.repository.ChildProfileRepository
import uz.faceguard.app.domain.repository.ParentProfileRepository
import uz.faceguard.app.domain.repository.ProtectedAppsRepository
import uz.faceguard.app.domain.repository.SettingsRepository

/** Parent-facing snapshot of the protection session. */
data class ProtectionRuntimeState(
    val enabled: Boolean = false,
    val active: Boolean = false,
    val protectionState: ProtectionState = ProtectionState.UNPROTECTED,
    val decision: String = "",
    val confidence: Double? = null,
    val foregroundApp: String? = null,
    val overlayGranted: Boolean = false,
    val usageAccessGranted: Boolean = false,
    val scanMode: ScanMode = ScanMode.BALANCED,
    val scanning: Boolean = false,
    val cooldownRemainingMs: Long = 0L,
    val lastTrigger: String = "",
    val protectedCount: Int = 0,
    val parentFaceEnrolled: Boolean = false,
    val childCount: Int = 0,
    val childrenFaceEnrolled: Int = 0,
) {
    /** All prerequisites for protection to actually block are satisfied. */
    val ready: Boolean
        get() = active && parentFaceEnrolled && protectedCount > 0 && usageAccessGranted && overlayGranted
}

/**
 * App-scoped protection session. It is the single owner of the foreground
 * monitor, scan scheduler and protection engine, and is activated purely by the
 * persisted `protectionEnabled` setting (plus a signed-in account).
 *
 * Recognition frames arrive through the shared [Recognizer] flow, so whichever
 * camera is active (the protection screen, enrollment, diagnostics) feeds the
 * engine. Limitation: with no camera bound — e.g. Qalqon backgrounded — the
 * engine sees "no face" and follows the no-face policy. Reliable system-wide
 * blocking still needs a foreground service + AccessibilityService.
 */
@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class ProtectionRuntime @Inject constructor(
    @ApplicationContext private val context: Context,
    private val recognizer: Recognizer,
    private val settingsRepository: SettingsRepository,
    private val protectedAppsRepository: ProtectedAppsRepository,
    private val parentProfileRepository: ParentProfileRepository,
    private val childProfileRepository: ChildProfileRepository,
    private val accountRepository: AccountRepository,
    private val activityLog: ActivityLogRepository,
) {

    private val monitor = ForegroundAppMonitor(context)
    private val overlay = OverlayControllerImpl(context)
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val scheduler = ScanScheduler(context)
    private val engine = ProtectionEngine(recognizer, monitor, audio, overlay)
        .also { it.attachScheduler(scheduler) }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(ProtectionRuntimeState())
    val state: StateFlow<ProtectionRuntimeState> = _state

    private var started = false
    private var active = false

    private var accountId: Long? = null
    private var settings = ProtectionSettings()
    private var parent: ParentProfile? = null
    private var children: List<ChildProfile> = emptyList()
    private var protectedPackages: Set<String> = emptySet()

    fun start() {
        if (started) return
        started = true
        engine.onEvent = { type, detail -> scope.launch { activityLog.log(type, detail) } }

        scope.launch { accountRepository.currentAccountId.collect { id -> accountId = id; syncActive() } }
        scope.launch {
            settingsRepository.settings.collect { value ->
                settings = ProtectionSettings.from(value)
                syncContext()
                syncActive()
            }
        }
        scope.launch {
            protectedAppsRepository.protectedApps.collect { apps ->
                protectedPackages = apps.filter { it.isProtected }.map { it.packageName }.toSet()
                syncContext()
                syncActive()
            }
        }
        scope.launch {
            accountRepository.currentAccountId
                .flatMapLatest { id -> if (id == null) flowOf<ParentProfile?>(null) else parentProfileRepository.observe(id) }
                .collect { profile -> parent = profile; syncContext(); syncActive() }
        }
        scope.launch {
            accountRepository.currentAccountId
                .flatMapLatest { id -> if (id == null) flowOf<List<ChildProfile>>(emptyList()) else childProfileRepository.observeChildren(id) }
                .collect { list -> children = list; syncContext(); syncActive() }
        }

        scope.launch { engine.state.collect { value -> _state.update { it.copy(protectionState = value) } } }
        scope.launch {
            engine.decision.collect { value ->
                _state.update { it.copy(decision = value?.reason ?: "", confidence = value?.confidence) }
            }
        }
        scope.launch { monitor.current.collect { value -> _state.update { it.copy(foregroundApp = value) } } }
        scope.launch { scheduler.scanning.collect { value -> _state.update { it.copy(scanning = value) } } }
        scope.launch {
            scheduler.cooldownRemaining.collect { value -> _state.update { it.copy(cooldownRemainingMs = value) } }
        }
        scope.launch {
            scheduler.lastEvent.collect { value -> _state.update { it.copy(lastTrigger = value?.trigger?.name ?: "") } }
        }
    }

    private fun syncContext() {
        engine.updateContext(parent, children, protectedPackages)
        engine.updateSettings(settings)
    }

    private fun syncActive() {
        val shouldBeActive = settings.enabled && accountId != null
        if (shouldBeActive && !active) activate() else if (!shouldBeActive && active) deactivate()
        _state.update {
            it.copy(
                enabled = settings.enabled,
                active = active,
                scanMode = settings.scanMode,
                protectedCount = protectedPackages.size,
                parentFaceEnrolled = parent?.isFaceEnrolled == true,
                childCount = children.size,
                childrenFaceEnrolled = children.count { child -> child.isFaceEnrolled },
                overlayGranted = overlay.hasPermission(),
                usageAccessGranted = monitor.hasUsageAccess(),
            )
        }
    }

    private fun activate() {
        active = true
        syncContext()
        monitor.start(scope)
        engine.start(scope)
    }

    private fun deactivate() {
        active = false
        engine.stop()
        scheduler.detach()
        monitor.stop()
        overlay.hide()
    }

    /** PIN-based parent emergency unlock; verified against the stored PIN. */
    fun emergencyUnlock(pin: String, onResult: (Boolean) -> Unit) {
        scope.launch {
            val ok = accountRepository.verifyPin(pin)
            if (ok) engine.emergencyUnlock()
            onResult(ok)
        }
    }

    /** Re-reads permission state after returning from system settings. */
    fun refreshPermissions() {
        _state.update {
            it.copy(
                overlayGranted = overlay.hasPermission(),
                usageAccessGranted = monitor.hasUsageAccess(),
            )
        }
    }

    fun usageAccessIntent(): Intent = monitor.usageAccessIntent()

    fun overlayPermissionIntent(): Intent = overlay.permissionIntent()
}

package uz.faceguard.app.core.protection

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import uz.faceguard.app.core.accessibility.AccessibilityCapability
import uz.faceguard.app.core.accessibility.AccessibilityForegroundTracker
import uz.faceguard.app.core.liveness.LivenessResult
import uz.faceguard.app.core.security.SecurityState
import uz.faceguard.app.core.security.SecurityStateHolder
import uz.faceguard.app.core.monitor.ForegroundAppMonitor
import uz.faceguard.app.core.recognition.Recognizer
import uz.faceguard.app.core.scan.ScanScheduler
import uz.faceguard.app.core.screentime.ScreenTimeUsageCollectionRunner
import uz.faceguard.app.domain.screentime.AppUsageSource
import uz.faceguard.app.domain.screentime.ScreenTimeUsageRepository
import uz.faceguard.app.domain.screentime.ScreenTimeLimits
import uz.faceguard.app.domain.screentime.UsageAccessState
import uz.faceguard.app.domain.screentime.UsageDateKey
import uz.faceguard.app.domain.model.ActivityEventType
import uz.faceguard.app.domain.model.ChildProfile
import uz.faceguard.app.domain.model.ParentProfile
import uz.faceguard.app.domain.model.ScanMode
import uz.faceguard.app.domain.policy.AppPolicy
import uz.faceguard.app.domain.policy.ChildAppPolicyRepository
import uz.faceguard.app.domain.notification.AppNotificationDispatcher
import uz.faceguard.app.domain.notification.AppNotificationEvent
import uz.faceguard.app.domain.notification.NotificationCoordinator
import uz.faceguard.app.domain.policy.PolicyEvaluator
import uz.faceguard.app.domain.request.ParentRequest
import uz.faceguard.app.domain.request.ParentRequestRepository
import uz.faceguard.app.domain.request.RequestCreationResult
import uz.faceguard.app.domain.request.RequestDeduplication
import uz.faceguard.app.domain.request.RequestLimits
import uz.faceguard.app.domain.request.RequestType
import uz.faceguard.app.domain.policy.PolicySettings
import uz.faceguard.app.domain.policy.PolicySettingsRepository
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
    /** True when the user has explicitly enabled QALQON's accessibility service. */
    val accessibilityEnabled: Boolean = false,
    /**
     * Group 8: the identity signal, independent of [foregroundApp]. Null means no
     * identity has been observed for the active protection session yet.
     */
    val identity: IdentitySnapshot? = null,
    /**
     * Group 9: the liveness signal, independent of both [identity] and
     * [foregroundApp]. Null means no liveness observation has been published for
     * the active protection session yet.
     */
    val liveness: LivenessResult? = null,
    /**
     * Phase 9: the app the current protection cycle is holding (best-effort
     * restoration target). Null when no cycle is active.
     */
    val blockedApp: String? = null,
    val scanMode: ScanMode = ScanMode.BALANCED,
    val scanning: Boolean = false,
    val cooldownRemainingMs: Long = 0L,
    val lastTrigger: String = "",
    /**
     * Phase 11: true when the OS would actually show our notifications. Request
     * state is independent of this — a denied permission never blocks requests.
     */
    val notificationsEnabled: Boolean = true,
    /**
     * Phase 12: at-rest security state. RECOVERY_REQUIRED/CORRUPTED means stored
     * biometric data could not be decrypted (fail closed: recognition treats the
     * template as unavailable and the parent is told re-enrollment is needed).
     */
    val securityState: SecurityState = SecurityState.SECURE,
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
 * engine. The Group 6 foreground service keeps this session alive while Qalqon
 * is backgrounded, and the Group 7 accessibility service feeds it real
 * foreground window transitions through [onAccessibilityForegroundApp].
 *
 * Group 8 exposes the identity signal ("who is looking") and Group 9 the
 * liveness signal ("is a real person there"): both are independent of the
 * `foregroundApp` signal ("which app is open", from usage-stats/accessibility)
 * and of each other in [ProtectionRuntimeState]. All three are combined by the
 * engine into the existing PolicyContext — the evaluator is still the only
 * decision point, and no camera/ML/liveness algorithm lives in this class.
 *
 * Remaining limitation: with no camera bound — e.g. Qalqon backgrounded — the
 * engine sees "no face" and follows the no-face policy, so identity detection is
 * still camera-bound even though foreground detection is now system-wide.
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
    private val policySettingsRepository: PolicySettingsRepository,
    private val childAppPolicyRepository: ChildAppPolicyRepository,
    private val policyEvaluator: PolicyEvaluator,
    private val serviceLauncher: ProtectionServiceLauncher,
    private val requestRepository: ParentRequestRepository,
    private val notificationCoordinator: NotificationCoordinator,
    private val notificationDispatcher: AppNotificationDispatcher,
    private val securityStateHolder: SecurityStateHolder,
    /**
     * Phase 4 Step 1B-7: production screen-time collection. Injected (rather than built
     * here) so it is the single shared instance and its interval stays owned by DI.
     */
    private val screenTimeCollection: ScreenTimeUsageCollectionRunner,
    /** Phase 4 Step 4: the usage the policy engine enforces app daily limits against. */
    private val screenTimeUsageRepository: ScreenTimeUsageRepository,
    private val appUsageSource: AppUsageSource,
    private val zone: ZoneId,
    private val clock: () -> Long,
) {

    private val monitor = ForegroundAppMonitor(context)
    private val overlay = OverlayControllerImpl(context, onRequestExtraTime = { requestExtraTime() })
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val scheduler = ScanScheduler(context)
    private val engine = ProtectionEngine(
        recognizer = recognizer,
        monitor = monitor,
        actions = OverlayProtectionActionExecutor(overlay, audio),
        policyEvaluator = policyEvaluator,
    ).also { it.attachScheduler(scheduler) }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(ProtectionRuntimeState())
    val state: StateFlow<ProtectionRuntimeState> = _state

    private var started = false
    private var active = false

    private var accountId: Long? = null

    /** Group 8: identity is cleared whenever the signed-in account changes. */
    private var lastAccountId: Long? = null
    private var settings = ProtectionSettings()
    private var policy = PolicySettings()
    private var parent: ParentProfile? = null
    private var children: List<ChildProfile> = emptyList()
    private var protectedPackages: Set<String> = emptySet()

    private var childPolicies: Map<Long, Map<String, AppPolicy>> = emptyMap()
    private val childPolicyJobs = mutableListOf<Job>()

    /** Phase 4 Step 4: whole minutes of today's usage per child and package; see [refreshChildUsage]. */
    private var childUsage: Map<Long, Map<String, Int>> = emptyMap()
    private val childUsageJobs = mutableListOf<Job>()

    /** True only while Usage Access is granted; without it usage is unknown, never zero. */
    @Volatile
    private var usageAccessAvailable = false

    /** Group 7: collapses duplicate accessibility window transitions. */
    private val accessibilityTracker = AccessibilityForegroundTracker()

    /** Keeps the engine's per-child app-policy lookup in sync with Room. */
    private fun refreshChildPolicies() {
        childPolicyJobs.forEach { it.cancel() }
        childPolicyJobs.clear()
        childPolicies = emptyMap()
        val account = accountId ?: return
        children.forEach { child ->
            childPolicyJobs += scope.launch {
                childAppPolicyRepository.observePolicies(account, child.id).collect { list ->
                    childPolicies = childPolicies + (child.id to list.associateBy { it.packageName })
                }
            }
        }
    }

    /**
     * Phase 4 Step 4: today's measured usage per child and package, in whole minutes.
     *
     * Kept in memory beside [childPolicies] and for the same reason — the engine must be able to
     * resolve it without doing I/O while it decides. Refreshed from the existing usage repository
     * whenever the account or the child list changes, which is also what makes the map
     * account-scoped: nothing survives an account switch.
     *
     * While Usage Access is not granted there is **no** measurement, so the map stays empty and
     * the lookup reports "unknown" rather than zero. Availability is re-probed on each refresh,
     * including the screen's existing permission refresh, so granting access starts enforcement
     * without any new timer.
     */
    private fun refreshChildUsage() {
        childUsageJobs.forEach { it.cancel() }
        childUsageJobs.clear()
        childUsage = emptyMap()
        usageAccessAvailable = runCatching {
            appUsageSource.usageAccess() == UsageAccessState.AVAILABLE
        }.getOrDefault(false)
        if (!usageAccessAvailable) return

        val account = accountId ?: return
        val dateKey = UsageDateKey.of(clock(), zone)
        children.forEach { child ->
            childUsageJobs += scope.launch {
                screenTimeUsageRepository.observeDayUsage(account, child.id, dateKey).collect { rows ->
                    // Truncating to whole minutes is exact for the limit comparison the policy
                    // engine performs: floor(usedMs / 60000) >= limit ⟺ usedMs >= limit * 60000.
                    childUsage = childUsage + (
                        child.id to rows.associate { it.packageName to (it.usedMs / ScreenTimeLimits.MS_PER_MINUTE).toInt() }
                        )
                }
            }
        }
    }

    fun start() {
        if (started) return
        started = true
        // One-time permission/capability snapshot; the screen refreshes it on
        // resume. Probes are kept out of the hot syncActive() path because they
        // read system state and run on the main dispatcher.
        refreshPermissions()
        engine.onEvent = { type, detail ->
            // Activity logging is secondary observability: it must never
            // interrupt protection, so a write failure is swallowed (and only
            // reported to logcat) instead of failing the runtime's scope.
            val owner = accountId
            if (owner != null) {
                scope.launch {
                    runCatching { activityLog.log(owner, type, detail) }
                        .onFailure { Log.w(TAG, "activity log write failed", it) }
                }
                // Phase 11: notification-worthy transitions only. The engine already
                // collapses per-frame chatter into transitions (CHILD_BLOCKED once per
                // block, PROTECTION_RELEASED once per recovery cycle), and the
                // coordinator additionally deduplicates, so no event spam is possible.
                notificationEventFor(type, owner)?.let { event ->
                    scope.launch { runCatching { notificationCoordinator.onEvent(event) } }
                }
            }
        }
        engine.appPolicyLookup = { childId, packageName -> childPolicies[childId]?.get(packageName) }
        // Phase 4 Step 4: the same recognised child and package the app policy was resolved for.
        // A null result means "no measurement", which suppresses only the screen-time
        // restriction — the app's own ALLOW/BLOCK policy is decided independently.
        engine.appTimeUsedMinutesLookup = { childId, packageName ->
            if (!usageAccessAvailable) null else childUsage[childId]?.get(packageName)
        }

        scope.launch {
            accountRepository.currentAccountId.collect { id ->
                if (id != lastAccountId) {
                    // Group 8: identity is account-scoped. A session change must not
                    // leave a previous account's child identity behind. The state is
                    // cleared explicitly because a no-op flow emission is deduped.
                    lastAccountId = id
                    engine.resetIdentity()
                    onIdentityChanged(null)
                    // Group 9: liveness is account-scoped too.
                    engine.resetLiveness()
                    onLivenessChanged(null)
                    // Phase 9: a previous account's pending recovery must never
                    // release or alter anything in the new session.
                    engine.cancelRecovery()
                }
                accountId = id
                refreshChildPolicies()
                refreshChildUsage()
                syncActive()
            }
        }
        scope.launch {
            settingsRepository.settings.collect { value ->
                settings = ProtectionSettings.from(value)
                syncContext()
                syncActive()
            }
        }
        scope.launch {
            policySettingsRepository.observe().collect { value ->
                policy = value
                syncContext()
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
                .collect { list ->
                    children = list
                    refreshChildPolicies()
                    refreshChildUsage()
                    syncContext()
                    syncActive()
                }
        }

        scope.launch { engine.state.collect { value -> _state.update { it.copy(protectionState = value) } } }
        scope.launch {
            engine.decision.collect { value ->
                _state.update { it.copy(decision = value?.reason ?: "", confidence = value?.confidence) }
            }
        }
        // Group 8: identity signal ("who is looking") stays separate from the
        // foreground-app signal (Group 7) and is the exact identity the last
        // policy decision was based on.
        scope.launch { engine.identity.collect { snapshot -> onIdentityChanged(snapshot) } }
        // Group 9: liveness ("is it a real person") is its own signal, kept
        // separate from identity and foreground.
        scope.launch { engine.liveness.collect { result -> onLivenessChanged(result) } }
        // Phase 9: the app the current protection cycle is holding.
        scope.launch { engine.blockedApp.collect { pkg -> _state.update { it.copy(blockedApp = pkg) } } }
        scope.launch { monitor.current.collect { value -> _state.update { it.copy(foregroundApp = value) } } }
        // Phase 12: surface the at-rest security state (no secrets, no technical detail).
        scope.launch {
            securityStateHolder.state.collect { value -> _state.update { it.copy(securityState = value) } }
        }
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
        engine.updateSettings(settings, policy)
    }

    private fun syncActive() {
        val shouldBeActive = ProtectionServicePolicy.shouldRun(settings.enabled, accountId)
        if (shouldBeActive && !active) activate() else if (!shouldBeActive && active) deactivate()
        // Phase 9: while protection must not run (disabled / signed out) there can
        // be no pending recovery release either.
        if (!shouldBeActive) engine.cancelRecovery()
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
        // Phase 4 Step 1B-7: screen-time accounting runs only while protection does.
        // Idempotent, so a repeated activate cannot stack collectors.
        screenTimeCollection.start(scope)
        // Keep the process alive so the session survives Qalqon being backgrounded.
        serviceLauncher.start()
    }

    private fun deactivate() {
        active = false
        engine.stop()
        scheduler.detach()
        monitor.stop()
        // Phase 4 Step 1B-7: stop collecting with the session; no leaked loop.
        screenTimeCollection.stop()
        overlay.hide()
        serviceLauncher.stop()
        // Group 8: a stopped session holds no identity state.
        onIdentityChanged(null)
        // Group 9: and no liveness state.
        onLivenessChanged(null)
    }

    /**
     * Clean, idempotent stop of active protection (used by the foreground
     * service when it is destroyed). The settings/account observers are kept:
     * they remain the single source of truth, so protection activates again on
     * the next relevant change instead of leaving a stale session behind.
     */
    fun stop() {
        if (active) deactivate()
        // Phase 4 Step 1B-7: an explicit stop always ends collection, even if the session
        // had already gone inactive. Idempotent, so it cannot cancel a newer loop.
        screenTimeCollection.stop()
        // Phase 9: an explicit stop always drops any pending recovery.
        engine.cancelRecovery()
        _state.update { it.copy(active = false) }
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
        // Phase 4 Step 4: re-probe usage capability and the day, so granting Usage Access (or
        // crossing midnight and returning to the screen) starts/stops enforcement without any
        // new timer. Collectors are only re-created, never started anywhere else.
        refreshChildUsage()
        _state.update {
            it.copy(
                overlayGranted = overlay.hasPermission(),
                usageAccessGranted = monitor.hasUsageAccess(),
                accessibilityEnabled = AccessibilityCapability.isEnabled(context),
                notificationsEnabled = runCatching { notificationDispatcher.areNotificationsEnabled() }.getOrDefault(true),
                securityState = securityStateHolder.state.value,
            )
        }
    }

    /**
     * Group 7: the accessibility service is bound. It becomes the authoritative
     * foreground source for the existing engine (which is unchanged).
     */
    fun onAccessibilityConnected() {
        monitor.setAccessibilityActive(true)
        refreshPermissions()
    }

    /**
     * Group 7: the accessibility service was unbound. Foreground detection falls
     * back to usage-stats polling and the transition baseline is cleared.
     */
    fun onAccessibilityDisconnected() {
        monitor.setAccessibilityActive(false)
        accessibilityTracker.reset()
        refreshPermissions()
    }

    /**
     * Group 7: a foreground window transition reported by the accessibility
     * service. Only the package identifier is used; duplicates are collapsed.
     */
    fun onAccessibilityForegroundApp(packageName: String?) {
        packageName?.let { pkg ->
            if (accessibilityTracker.onForegroundPackage(pkg)) {
                monitor.updateFromAccessibility(pkg)
            }
        }
    }

    /**
     * Group 8: identity signal from the recognition pipeline (single funnel used
     * by the engine collector). The foreground app remains a separate signal in
     * [ProtectionRuntimeState.foregroundApp].
     */
    fun onIdentityChanged(snapshot: IdentitySnapshot?) {
        _state.update { it.copy(identity = snapshot) }
    }

    /**
     * Group 9: liveness signal from the recognition pipeline (single funnel used
     * by the engine collector). Kept separate from [onIdentityChanged] and from
     * the foreground app.
     */
    fun onLivenessChanged(result: LivenessResult?) {
        _state.update { it.copy(liveness = result) }
    }

    /**
     * Phase 11: the child asked for more time on the app that was just blocked
     * (overlay action). Creates a durable PENDING request — an *authorization
     * record only*: no usage is measured and nothing is granted here (Phase 4 owns
     * consumption), and the PolicyEvaluator is never bypassed.
     */
    fun requestExtraTime() {
        val owner = accountId ?: return
        val packageName = engine.blockedApp.value ?: return
        val childId = engine.identity.value?.childId ?: return
        scope.launch {
            val now = System.currentTimeMillis()
            val request = ParentRequest(
                accountId = owner,
                childId = childId,
                targetPackageName = packageName,
                requestType = RequestType.EXTRA_TIME,
                requestedDurationMinutes = RequestLimits.DEFAULT_REQUEST_MINUTES,
                createdAt = now,
                updatedAt = now,
                deduplicationKey = RequestDeduplication.keyFor(
                    accountId = owner,
                    childId = childId,
                    requestType = RequestType.EXTRA_TIME,
                    targetPackageName = packageName,
                ),
            )
            when (val result = requestRepository.create(request)) {
                is RequestCreationResult.Created -> notificationCoordinator.onEvent(
                    AppNotificationEvent.ParentRequestCreated(
                        accountId = owner,
                        requestId = result.request.id,
                        childId = childId,
                        targetPackageName = packageName,
                        requestedDurationMinutes = result.request.requestedDurationMinutes,
                        at = now,
                    ),
                )

                // Already pending: a repeated tap must not create a second request.
                is RequestCreationResult.Duplicate -> Unit

                is RequestCreationResult.Rejected ->
                    Log.w(TAG, "extra-time request rejected: ${result.reason}")
            }
        }
    }

    /** Protection transitions that deserve a parent notification (nothing else). */
    private fun notificationEventFor(type: ActivityEventType, owner: Long): AppNotificationEvent? {
        val now = System.currentTimeMillis()
        return when (type) {
            ActivityEventType.CHILD_BLOCKED -> AppNotificationEvent.ProtectionBlocked(
                accountId = owner,
                childId = engine.identity.value?.childId,
                targetPackageName = engine.blockedApp.value,
                at = now,
            )

            ActivityEventType.PROTECTION_RELEASED -> AppNotificationEvent.ProtectionReleased(owner, now)

            else -> null
        }
    }

    fun usageAccessIntent(): Intent = monitor.usageAccessIntent()

    fun overlayPermissionIntent(): Intent = overlay.permissionIntent()

    fun accessibilitySettingsIntent(): Intent = AccessibilityCapability.settingsIntent()

    private companion object {
        const val TAG = "ProtectionRuntime"
    }
}

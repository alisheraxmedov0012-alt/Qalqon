package uz.faceguard.app.dashboard

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.faceguard.app.core.protection.ProtectionRuntimeState
import uz.faceguard.app.core.protection.ProtectionState
import uz.faceguard.app.core.protection.IdentitySnapshot
import uz.faceguard.app.core.protection.IdentitySource
import uz.faceguard.app.core.liveness.LivenessResult
import uz.faceguard.app.core.liveness.LivenessSource
import uz.faceguard.app.domain.model.ActivityEvent
import uz.faceguard.app.domain.model.ActivityEventType
import uz.faceguard.app.domain.model.AppSettings
import uz.faceguard.app.domain.model.BlockPolicy
import uz.faceguard.app.domain.model.ChildProfile
import uz.faceguard.app.domain.model.EnrollmentStatus
import uz.faceguard.app.domain.model.ParentProfile
import uz.faceguard.app.domain.model.ProtectedApp
import uz.faceguard.app.domain.model.RestrictionLevel
import uz.faceguard.app.domain.model.ScanMode
import uz.faceguard.app.domain.model.UserAccount
import uz.faceguard.app.domain.policy.AppPolicy
import uz.faceguard.app.domain.policy.AppPolicyMode
import uz.faceguard.app.domain.policy.ChildAppPolicyRepository
import uz.faceguard.app.domain.policy.IdentityContext
import uz.faceguard.app.domain.policy.LivenessState
import uz.faceguard.app.domain.policy.ProtectionAction
import uz.faceguard.app.domain.policy.UserIdentity
import uz.faceguard.app.domain.repository.AccountRepository
import uz.faceguard.app.domain.repository.ActivityLogRepository
import uz.faceguard.app.domain.repository.ChildProfileRepository
import uz.faceguard.app.domain.repository.ProtectedAppsRepository
import uz.faceguard.app.domain.repository.SettingsRepository
import uz.faceguard.app.domain.request.ParentRequest
import uz.faceguard.app.domain.request.ParentRequestRepository
import uz.faceguard.app.domain.request.RequestCreationResult
import uz.faceguard.app.domain.request.RequestResolutionResult
import uz.faceguard.app.feature.home.DashboardAggregator
import uz.faceguard.app.feature.home.DashboardStatus
import uz.faceguard.app.feature.home.DashboardUiState

/**
 * Phase 10: the dashboard aggregation layer against hand-written fakes.
 *
 * Verifies the real aggregation/isolation behaviour (account scoping, child
 * switching, stale-load containment, deletion, error + retry, runtime mirroring,
 * bounded activity) without mocking the class under test.
 */
class DashboardAggregatorTest {

    // ---- fakes --------------------------------------------------------------

    private class FakeAccounts : AccountRepository {
        val id = MutableStateFlow<Long?>(null)
        override val currentAccountId: Flow<Long?> = id
        override suspend fun getCurrentAccount(): UserAccount? =
            id.value?.let { UserAccount(id = it, fullName = "Parent", phoneNumber = "123", pinHash = "hash") }
        override suspend fun register(fullName: String, phoneNumber: String, pin: String) = error("not used")
        override suspend fun login(phoneNumber: String, pin: String) = error("not used")
        override suspend fun logout() { id.value = null }
        override suspend fun verifyPin(pin: String) = false
    }

    private class FakeChildren : ChildProfileRepository {
        val byAccount = mutableMapOf<Long, MutableStateFlow<List<ChildProfile>>>()
        fun set(accountId: Long, children: List<ChildProfile>) {
            byAccount.getOrPut(accountId) { MutableStateFlow(emptyList()) }.value = children
        }
        override fun observeChildren(accountId: Long): Flow<List<ChildProfile>> =
            byAccount.getOrPut(accountId) { MutableStateFlow(emptyList()) }
        override suspend fun addChild(accountId: Long, childName: String, level: RestrictionLevel) = error("not used")
        override suspend fun updateChild(accountId: Long, childId: Long, childName: String, level: RestrictionLevel) = error("not used")
        override suspend fun deleteChild(accountId: Long, childId: Long) = error("not used")
        override suspend fun saveFaceEnrollment(accountId: Long, childId: Long, templateRef: String) = error("not used")
        override suspend fun deleteFaceData(accountId: Long, childId: Long) = error("not used")
    }

    private class FakePolicies : ChildAppPolicyRepository {
        val byChild = mutableMapOf<Pair<Long, Long>, MutableStateFlow<List<AppPolicy>>>()
        val firstEmissionDelayMs = mutableMapOf<Long, Long>()
        fun set(accountId: Long, childId: Long, policies: List<AppPolicy>) {
            byChild.getOrPut(accountId to childId) { MutableStateFlow(emptyList()) }.value = policies
        }
        override fun observePolicies(accountId: Long, childId: Long): Flow<List<AppPolicy>> {
            val source = byChild.getOrPut(accountId to childId) { MutableStateFlow(emptyList()) }
            val delayMs = firstEmissionDelayMs[childId] ?: 0L
            return if (delayMs <= 0L) source else source.onStart { delay(delayMs) }
        }
        override suspend fun policyFor(accountId: Long, childId: Long, packageName: String): AppPolicy? = null
        override suspend fun upsert(accountId: Long, childId: Long, policy: AppPolicy) = error("not used")
        override suspend fun delete(accountId: Long, childId: Long, packageName: String) = error("not used")
        override suspend fun deleteAllForChild(accountId: Long, childId: Long) = error("not used")
    }

    private class FakeApps : ProtectedAppsRepository {
        val apps = MutableStateFlow<List<ProtectedApp>>(emptyList())
        override val protectedApps: Flow<List<ProtectedApp>> = apps
        override suspend fun refreshFromDevice() = error("not used")
        override suspend fun toggleProtection(packageName: String, isProtected: Boolean) = error("not used")
        override suspend fun countProtected(): Int = apps.value.count { it.isProtected }
    }

    private class FakeActivity : ActivityLogRepository {
        val byAccount = mutableMapOf<Long, MutableStateFlow<List<ActivityEvent>>>()
        fun set(accountId: Long, events: List<ActivityEvent>) {
            byAccount.getOrPut(accountId) { MutableStateFlow(emptyList()) }.value = events
        }
        override fun recent(accountId: Long): Flow<List<ActivityEvent>> =
            byAccount.getOrPut(accountId) { MutableStateFlow(emptyList()) }
        override suspend fun log(accountId: Long, type: ActivityEventType, detail: String?) = error("not used")
        override suspend fun clear(accountId: Long) = error("not used")
    }

    private class FakeSettings : SettingsRepository {
        val settingsFlow = MutableStateFlow(AppSettings())
        override val settings: Flow<AppSettings> = settingsFlow
        override suspend fun setProtectionEnabled(enabled: Boolean) = error("not used")
        override suspend fun setScanMode(mode: ScanMode) = error("not used")
        override suspend fun setRecoveryDelayMs(delayMs: Long) = error("not used")
        override suspend fun setUnknownUserPolicy(policy: BlockPolicy) = error("not used")
        override suspend fun setNoFacePolicy(policy: BlockPolicy) = error("not used")
        override suspend fun setLowBatteryBehaviorEnabled(enabled: Boolean) = error("not used")
    }

    private class FakeRequests : ParentRequestRepository {
        val pendingCount = MutableStateFlow(0)
        override fun observePending(accountId: Long): Flow<List<ParentRequest>> = MutableStateFlow(emptyList())
        override fun observeForAccount(accountId: Long): Flow<List<ParentRequest>> = MutableStateFlow(emptyList())
        override fun observePendingCount(accountId: Long): Flow<Int> = pendingCount
        override fun observePendingForChild(accountId: Long, childId: Long): Flow<List<ParentRequest>> =
            MutableStateFlow(emptyList())
        override suspend fun byId(accountId: Long, requestId: Long): ParentRequest? = null
        override suspend fun create(request: ParentRequest): RequestCreationResult = error("not used")
        override suspend fun approve(accountId: Long, requestId: Long, approvedDurationMinutes: Int?, now: Long) =
            error("not used")
        override suspend fun reject(accountId: Long, requestId: Long, now: Long) = error("not used")
        override suspend fun cancel(accountId: Long, requestId: Long, now: Long) = error("not used")
        override suspend fun expireStale(accountId: Long, now: Long): Int = 0
    }

    /** Runtime source that can be made to fail once, to exercise the error path. */
    private class FakeRuntime {
        val state = MutableStateFlow(ProtectionRuntimeState())
        var fail = false
        var subscriptions = 0
        val flow: Flow<ProtectionRuntimeState> = flow {
            subscriptions += 1
            if (fail) throw IllegalStateException("runtime unavailable")
            emitAll(state)
        }
    }

    // ---- harness ------------------------------------------------------------

    private val accounts = FakeAccounts()
    private val children = FakeChildren()
    private val policies = FakePolicies()
    private val apps = FakeApps()
    private val activity = FakeActivity()
    private val settings = FakeSettings()
    private val requests = FakeRequests()
    private val runtime = FakeRuntime()

    private fun aggregator() = DashboardAggregator(
        accountRepository = accounts,
        childRepository = children,
        policyRepository = policies,
        protectedAppsRepository = apps,
        activityLogRepository = activity,
        settingsRepository = settings,
        requestRepository = requests,
        runtimeState = runtime.flow,
    )

    private fun child(id: Long, name: String = "Child $id") = ChildProfile(
        id = id,
        accountId = 1L,
        childName = name,
        restrictionLevel = RestrictionLevel.HIGH,
        isFaceEnrolled = true,
        enrollmentStatus = EnrollmentStatus.ENROLLED,
    )

    private fun policy(pkg: String, mode: AppPolicyMode, limit: Int? = null) = AppPolicy(
        packageName = pkg,
        mode = mode,
        action = if (mode == AppPolicyMode.BLOCK) ProtectionAction.HARD_BLOCK else ProtectionAction.ALLOW,
        dailyLimitMinutes = limit,
    )

    private fun event(type: ActivityEventType, at: Long = 1L) = ActivityEvent(accountId = 1L, type = type, at = at)

    private fun withDashboard(block: suspend (StateFlow<DashboardUiState>, DashboardAggregator) -> Unit) = runBlocking {
        val agg = aggregator()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val state = agg.observe(scope)
        val subscription: Job = scope.launch { state.collect {} }
        try {
            block(state, agg)
        } finally {
            subscription.cancel()
            scope.cancel()
        }
    }

    private suspend fun await(
        state: StateFlow<DashboardUiState>,
        timeoutMs: Long = 5_000L,
        predicate: (DashboardUiState) -> Boolean,
    ): DashboardUiState {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate(state.value)) return state.value
            delay(10)
        }
        return state.value
    }

    // ---- tests --------------------------------------------------------------

    @Test
    fun noSignedInAccount_showsTheNoAccountStateNotFakeData() = withDashboard { state, _ ->
        val current = await(state) { it.status == DashboardStatus.NO_ACCOUNT }
        assertEquals(DashboardStatus.NO_ACCOUNT, current.status)
        assertFalse(current.hasChild)
        assertTrue(current.children.isEmpty())
        assertTrue(current.recentEvents.isEmpty())
    }

    @Test
    fun readyAccount_aggregatesChildPoliciesAppsEventsAndSettings() = withDashboard { state, _ ->
        accounts.id.value = 1L
        children.set(1L, listOf(child(5L, "Vali"), child(6L, "Ali")))
        policies.set(
            1L,
            5L,
            listOf(
                policy("com.example.youtube", AppPolicyMode.BLOCK),
                policy("com.example.games", AppPolicyMode.LIMIT, limit = 30),
                policy("com.example.math", AppPolicyMode.ALLOW),
            ),
        )
        apps.apps.value = listOf(
            ProtectedApp("com.example.youtube", "YouTube", isProtected = true),
            ProtectedApp("com.example.games", "Games", isProtected = true),
            ProtectedApp("com.example.math", "Math", isProtected = false),
        )
        settings.settingsFlow.value = AppSettings(protectionEnabled = true)
        activity.set(
            1L,
            (1..7).map { event(ActivityEventType.CHILD_BLOCKED, at = it.toLong()) },
        )

        val ready = await(state) {
            it.status == DashboardStatus.READY && it.child?.policies?.total == 3 && it.recentEvents.size == 5
        }

        assertEquals(DashboardStatus.READY, ready.status)
        assertEquals("first child is selected by default", 5L, ready.selectedChildId)
        assertEquals("Vali", ready.child?.name)
        assertEquals(true, ready.child?.faceEnrolled)
        assertEquals(RestrictionLevel.HIGH, ready.child?.level)
        assertEquals(1, ready.child?.policies?.allow)
        assertEquals(1, ready.child?.policies?.limit)
        assertEquals(1, ready.child?.policies?.block)
        assertEquals(1, ready.child?.limits?.size)
        assertEquals("com.example.games", ready.child?.limits?.first()?.packageName)
        assertEquals(2, ready.protectedAppsCount)
        assertTrue(ready.protectionEnabled)
        assertEquals(5, ready.recentEvents.size)
        assertFalse("usage must never be reported as available", ready.usageAvailable)
    }

    @Test
    fun runtimeStateIsMirroredIntoTheDashboard() = withDashboard { state, _ ->
        accounts.id.value = 1L
        children.set(1L, listOf(child(5L)))
        settings.settingsFlow.value = AppSettings(protectionEnabled = true)

        runtime.state.value = ProtectionRuntimeState(
            enabled = true,
            active = true,
            protectionState = ProtectionState.HARD_BLOCKED,
            identity = IdentitySnapshot(
                context = IdentityContext(identity = UserIdentity.CHILD, childId = 5L, childName = "Vali"),
                source = IdentitySource.CAMERA,
                updatedAt = 1L,
            ),
            liveness = LivenessResult(LivenessState.SPOOF, null, 1L, LivenessSource.HEURISTIC),
            blockedApp = "com.example.youtube",
            overlayGranted = true,
            usageAccessGranted = true,
            accessibilityEnabled = true,
        )

        val current = await(state) { it.protectionState == ProtectionState.HARD_BLOCKED }

        assertEquals(true, current.runtimeActive)
        assertEquals(UserIdentity.CHILD, current.identity)
        assertEquals(LivenessState.SPOOF, current.liveness)
        assertEquals("com.example.youtube", current.blockedApp)
        assertTrue(current.enforcementReady)
    }

    @Test
    fun switchingChild_switchesAllChildScopedData() = withDashboard { state, agg ->
        accounts.id.value = 1L
        children.set(1L, listOf(child(5L, "Vali"), child(6L, "Ali")))
        policies.set(1L, 5L, listOf(policy("a.youtube", AppPolicyMode.BLOCK)))
        policies.set(1L, 6L, listOf(policy("b.games", AppPolicyMode.ALLOW), policy("c.chat", AppPolicyMode.BLOCK)))

        await(state) { it.child?.name == "Vali" && it.child?.policies?.total == 1 }

        agg.selectChild(6L)

        val switched = await(state) { it.selectedChildId == 6L && it.child?.policies?.total == 2 }
        assertEquals("Ali", switched.child?.name)
        assertEquals(1, switched.child?.policies?.allow)
        assertEquals(1, switched.child?.policies?.block)
        assertEquals(0, switched.child?.policies?.limit)
    }

    @Test
    fun aSlowLoadForThePreviousChild_neverAppearsAsTheNewChildsData() = withDashboard { state, agg ->
        accounts.id.value = 1L
        children.set(1L, listOf(child(5L, "Vali"), child(6L, "Ali")))
        // The new child's policies are slow to arrive.
        policies.firstEmissionDelayMs[6L] = 400L
        policies.set(1L, 5L, listOf(policy("a.youtube", AppPolicyMode.BLOCK)))
        policies.set(1L, 6L, listOf(policy("b.games", AppPolicyMode.ALLOW)))

        await(state) { it.child?.name == "Vali" && it.child?.policies?.total == 1 }

        agg.selectChild(6L)

        // While child 6 loads, the header already shows 6 and nothing of 5 leaks.
        val loading = await(state) { it.selectedChildId == 6L && it.child?.policiesLoading == true }
        assertEquals("Ali", loading.child?.name)
        assertEquals(0, loading.child?.policies?.total)

        val loaded = await(state) { it.selectedChildId == 6L && it.child?.policiesLoading == false }
        assertEquals(1, loaded.child?.policies?.total)
        assertEquals(1, loaded.child?.policies?.allow)
    }

    @Test
    fun accountSwitch_neverShowsThePreviousAccountsData() = withDashboard { state, _ ->
        accounts.id.value = 1L
        children.set(1L, listOf(child(5L, "Vali")))
        policies.set(1L, 5L, listOf(policy("a.youtube", AppPolicyMode.BLOCK)))
        activity.set(1L, listOf(event(ActivityEventType.CHILD_BLOCKED)))

        await(state) { it.child?.name == "Vali" && it.recentEvents.isNotEmpty() }

        // Account 2 has its own child and no events.
        children.set(2L, listOf(child(9L, "Aziz")))
        activity.set(2L, emptyList())
        accounts.id.value = 2L

        val switched = await(state) { it.selectedChildId == 9L && it.child?.name == "Aziz" }
        assertEquals("Aziz", switched.child?.name)
        assertTrue("account 1's events must not leak", switched.recentEvents.isEmpty())
        assertTrue(switched.children.none { it.childName == "Vali" })
        assertNull(switched.blockedApp)
    }

    @Test
    fun signingOut_clearsTheDashboardBackToNoAccount() = withDashboard { state, _ ->
        accounts.id.value = 1L
        children.set(1L, listOf(child(5L, "Vali")))
        await(state) { it.child?.name == "Vali" }

        accounts.id.value = null

        val signedOut = await(state) { it.status == DashboardStatus.NO_ACCOUNT }
        assertNull(signedOut.child)
        assertNull(signedOut.selectedChildId)
        assertTrue(signedOut.children.isEmpty())
    }

    @Test
    fun deletingTheSelectedChild_fallsBackSafelyToAnotherChild() = withDashboard { state, _ ->
        accounts.id.value = 1L
        children.set(1L, listOf(child(5L, "Vali"), child(6L, "Ali")))
        policies.set(1L, 5L, listOf(policy("a.youtube", AppPolicyMode.BLOCK)))
        policies.set(1L, 6L, listOf(policy("b.games", AppPolicyMode.ALLOW)))
        await(state) { it.selectedChildId == 5L }

        children.set(1L, listOf(child(6L, "Ali")))

        // Wait for the fallback child's data too: asserting only the selection
        // raced the policy load (found by the Phase 13 flakiness audit).
        val afterDelete = await(state) { it.selectedChildId == 6L && it.child?.policies?.total == 1 }
        assertEquals("Ali", afterDelete.child?.name)
        assertEquals(1, afterDelete.child?.policies?.total)
    }

    @Test
    fun aFailedSource_showsTheErrorStateAndRetryRecovers() = withDashboard { state, agg ->
        accounts.id.value = 1L
        children.set(1L, listOf(child(5L, "Vali")))
        runtime.fail = true
        agg.retry()

        val error = await(state) { it.status == DashboardStatus.ERROR }
        assertEquals(DashboardStatus.ERROR, error.status)

        runtime.fail = false
        agg.retry()

        val recovered = await(state) { it.status == DashboardStatus.READY && it.child?.name == "Vali" }
        assertEquals(DashboardStatus.READY, recovered.status)
        assertTrue("retry must re-subscribe the aggregation", runtime.subscriptions >= 2)
    }

    @Test
    fun noChildren_isAReadyButEmptyChildStateNotAnError() = withDashboard { state, _ ->
        accounts.id.value = 1L
        children.set(1L, emptyList())

        val ready = await(state) { it.status == DashboardStatus.READY }
        assertFalse(ready.hasChild)
        assertNull(ready.child)
        assertTrue(ready.children.isEmpty())
    }
}

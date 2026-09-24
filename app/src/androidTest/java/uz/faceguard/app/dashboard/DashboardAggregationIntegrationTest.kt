package uz.faceguard.app.dashboard

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import uz.faceguard.app.core.protection.ProtectionRuntimeState
import uz.faceguard.app.data.db.FaceGuardDatabase
import uz.faceguard.app.data.repository.ActivityLogRepositoryImpl
import uz.faceguard.app.data.repository.ChildAppPolicyRepositoryImpl
import uz.faceguard.app.data.repository.ChildProfileRepositoryImpl
import uz.faceguard.app.data.repository.ParentRequestRepositoryImpl
import uz.faceguard.app.domain.model.ActivityEventType
import uz.faceguard.app.domain.model.AppSettings
import uz.faceguard.app.domain.model.BlockPolicy
import uz.faceguard.app.domain.model.ProtectedApp
import uz.faceguard.app.domain.model.RestrictionLevel
import uz.faceguard.app.domain.model.ScanMode
import uz.faceguard.app.domain.model.UserAccount
import uz.faceguard.app.domain.policy.AppPolicy
import uz.faceguard.app.domain.policy.AppPolicyMode
import uz.faceguard.app.domain.policy.ProtectionAction
import uz.faceguard.app.domain.repository.AccountRepository
import uz.faceguard.app.domain.repository.ProtectedAppsRepository
import uz.faceguard.app.domain.repository.SettingsRepository
import uz.faceguard.app.feature.home.DashboardAggregator
import uz.faceguard.app.feature.home.DashboardStatus
import uz.faceguard.app.feature.home.DashboardUiState

/**
 * Phase 10 integration: the dashboard aggregation over the REAL Room-backed
 * child/policy/activity repositories (only account/settings/apps/runtime are
 * fakes, since they are not the persistence under test here). Verifies that the
 * dashboard reads genuine persisted data, is account-scoped and follows the
 * selected child.
 */
@RunWith(AndroidJUnit4::class)
class DashboardAggregationIntegrationTest {

    private lateinit var db: FaceGuardDatabase
    private lateinit var childRepository: ChildProfileRepositoryImpl
    private lateinit var policyRepository: ChildAppPolicyRepositoryImpl
    private lateinit var activityRepository: ActivityLogRepositoryImpl
    private lateinit var requestRepository: ParentRequestRepositoryImpl

    private val accountId = MutableStateFlow<Long?>(null)
    private val settingsFlow = MutableStateFlow(AppSettings())
    private val apps = MutableStateFlow<List<ProtectedApp>>(emptyList())
    private val runtimeState = MutableStateFlow(ProtectionRuntimeState())

    private val accounts = object : AccountRepository {
        override val currentAccountId: Flow<Long?> = accountId
        override suspend fun getCurrentAccount(): UserAccount? = null
        override suspend fun register(fullName: String, phoneNumber: String, pin: String) = error("not used")
        override suspend fun login(phoneNumber: String, pin: String) = error("not used")
        override suspend fun logout() { accountId.value = null }
        override suspend fun verifyPin(pin: String) = false
    }

    private val settingsRepository = object : SettingsRepository {
        override val settings: Flow<AppSettings> = settingsFlow
        override suspend fun setProtectionEnabled(enabled: Boolean) = error("not used")
        override suspend fun setScanMode(mode: ScanMode) = error("not used")
        override suspend fun setRecoveryDelayMs(delayMs: Long) = error("not used")
        override suspend fun setUnknownUserPolicy(policy: BlockPolicy) = error("not used")
        override suspend fun setNoFacePolicy(policy: BlockPolicy) = error("not used")
        override suspend fun setLowBatteryBehaviorEnabled(enabled: Boolean) = error("not used")
    }

    private val patchedApps = object : ProtectedAppsRepository {
        override val protectedApps: Flow<List<ProtectedApp>> = apps
        override suspend fun refreshFromDevice() = error("not used")
        override suspend fun toggleProtection(packageName: String, isProtected: Boolean) = error("not used")
        override suspend fun countProtected(): Int = apps.value.count { it.isProtected }
    }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            FaceGuardDatabase::class.java,
        ).allowMainThreadQueries().build()
        childRepository = ChildProfileRepositoryImpl(db.childProfileDao())
        policyRepository = ChildAppPolicyRepositoryImpl(db.childAppPolicyDao())
        activityRepository = ActivityLogRepositoryImpl(db.activityEventDao())
        requestRepository = ParentRequestRepositoryImpl(db.parentRequestDao(), childRepository)
    }

    @After
    fun tearDown() = db.close()

    private fun aggregator() = DashboardAggregator(
        accountRepository = accounts,
        childRepository = childRepository,
        policyRepository = policyRepository,
        protectedAppsRepository = patchedApps,
        activityLogRepository = activityRepository,
        settingsRepository = settingsRepository,
        requestRepository = requestRepository,
        runtimeState = flow {
            emitAll(runtimeState)
        },
    )

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

    private fun withDashboard(block: suspend (StateFlow<DashboardUiState>, DashboardAggregator) -> Unit) = runBlocking {
        val agg = aggregator()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val state = agg.observe(scope)
        val job: Job = scope.launch { state.collect {} }
        try {
            block(state, agg)
        } finally {
            job.cancel()
            scope.cancel()
        }
    }

    @Test
    fun dashboardReadsRealPersistedChildrenPoliciesAndEvents() = withDashboard { state, _ ->
        val vali = childRepository.addChild(1L, "Vali", RestrictionLevel.HIGH)
        childRepository.saveFaceEnrollment(1L, vali, "template")
        policyRepository.upsert(1L, vali, policy("com.example.youtube", AppPolicyMode.BLOCK))
        policyRepository.upsert(1L, vali, policy("com.example.games", AppPolicyMode.LIMIT, limit = 30))
        activityRepository.log(1L, ActivityEventType.CHILD_BLOCKED, "Vali")
        apps.value = listOf(ProtectedApp("com.example.youtube", "YouTube", isProtected = true))
        settingsFlow.value = AppSettings(protectionEnabled = true)

        accountId.value = 1L

        val ready = await(state) { it.child?.policies?.total == 2 && it.recentEvents.isNotEmpty() }

        assertEquals(DashboardStatus.READY, ready.status)
        assertEquals(vali, ready.selectedChildId)
        assertEquals("Vali", ready.child?.name)
        assertTrue("persisted enrollment state is shown", ready.child?.faceEnrolled == true)
        assertEquals(1, ready.child?.policies?.block)
        assertEquals(1, ready.child?.policies?.limit)
        assertEquals(30, ready.child?.limits?.first()?.dailyLimitMinutes)
        assertEquals(1, ready.protectedAppsCount)
        assertEquals(ActivityEventType.CHILD_BLOCKED, ready.recentEvents.first().type)
        assertTrue(ready.recentEvents.first().detail == "Vali")
    }

    @Test
    fun eachAccountOnlySeesItsOwnPersistedData() = withDashboard { state, _ ->
        val vali = childRepository.addChild(1L, "Vali", RestrictionLevel.MEDIUM)
        activityRepository.log(1L, ActivityEventType.CHILD_BLOCKED, "account 1")
        val aziz = childRepository.addChild(2L, "Aziz", RestrictionLevel.LOW)
        activityRepository.log(2L, ActivityEventType.NO_FACE, "account 2")

        accountId.value = 1L
        val first = await(state) { it.selectedChildId == vali && it.recentEvents.isNotEmpty() }
        assertEquals("Vali", first.child?.name)
        assertTrue(first.recentEvents.none { it.detail == "account 2" })

        accountId.value = 2L
        val second = await(state) { it.selectedChildId == aziz }
        assertEquals("Aziz", second.child?.name)
        assertTrue("account 1's event must not leak", second.recentEvents.none { it.detail == "account 1" })
    }

    @Test
    fun switchingChildSelectsThatChildsPersistedPolicies() = withDashboard { state, agg ->
        val vali = childRepository.addChild(1L, "Vali", RestrictionLevel.MEDIUM)
        val ali = childRepository.addChild(1L, "Ali", RestrictionLevel.HIGH)
        policyRepository.upsert(1L, vali, policy("a.youtube", AppPolicyMode.BLOCK))
        policyRepository.upsert(1L, ali, policy("b.games", AppPolicyMode.ALLOW))

        accountId.value = 1L
        await(state) { it.child?.name == "Vali" && it.child?.policies?.total == 1 }

        agg.selectChild(ali)

        val switched = await(state) { it.selectedChildId == ali && it.child?.policies?.total == 1 }
        assertEquals("Ali", switched.child?.name)
        assertEquals(1, switched.child?.policies?.allow)
        assertEquals(0, switched.child?.policies?.block)
    }

    private fun policy(pkg: String, mode: AppPolicyMode, limit: Int? = null) = AppPolicy(
        packageName = pkg,
        mode = mode,
        action = if (mode == AppPolicyMode.BLOCK) ProtectionAction.HARD_BLOCK else ProtectionAction.ALLOW,
        dailyLimitMinutes = limit,
    )
}

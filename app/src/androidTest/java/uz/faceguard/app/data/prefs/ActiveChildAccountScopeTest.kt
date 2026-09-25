package uz.faceguard.app.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import uz.faceguard.app.data.repository.ScreenTimeActiveChildRepositoryImpl

/**
 * Phase 4 Step 1B-7: the persisted screen-time target, through the real DataStore.
 *
 * The point is the *scoping*: a target belongs to one account and nothing about it may
 * leak to another. Uses a fresh on-disk Preferences DataStore plus the real
 * [SessionManager], so the production read/write path is exercised rather than a fake —
 * no Room, and the database version is untouched.
 */
@RunWith(AndroidJUnit4::class)
class ActiveChildAccountScopeTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var file: File
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var sessionManager: SessionManager
    private lateinit var store: SettingsStore
    private lateinit var repository: ScreenTimeActiveChildRepositoryImpl

    @Before
    fun setUp() {
        sessionManager = SessionManager(context)
        runBlocking { sessionManager.clearSession() }
        file = File(context.cacheDir, "active-child-test-${System.nanoTime()}.preferences_pb")
        dataStore = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
        store = SettingsStore(dataStore, sessionManager)
        repository = ScreenTimeActiveChildRepositoryImpl(store)
    }

    @After
    fun tearDown() {
        scope.cancel()
        file.delete()
        runBlocking { sessionManager.clearSession() }
    }

    // ---- basic storage ------------------------------------------------------

    @Test
    fun anAbsentTargetIsNullAndNeverADefaultChild() = runBlocking {
        assertNull("no target means no target", repository.activeChildId(1L))
        assertNull(repository.activeChildId(2L))
    }

    @Test
    fun aTargetCanBeSetAndReadBack() = runBlocking {
        repository.setActiveChildId(1L, 10L)

        assertEquals(10L, repository.activeChildId(1L))
    }

    @Test
    fun aTargetPersistsAcrossStoreInstances() = runBlocking {
        repository.setActiveChildId(1L, 10L)

        // A fresh adapter over the same file, standing in for a restarted process.
        val reopened = ScreenTimeActiveChildRepositoryImpl(SettingsStore(dataStore, sessionManager))

        assertEquals("the target must survive a restart", 10L, reopened.activeChildId(1L))
    }

    @Test
    fun settingATargetAgainReplacesIt() = runBlocking {
        repository.setActiveChildId(1L, 10L)
        repository.setActiveChildId(1L, 11L)

        assertEquals(11L, repository.activeChildId(1L))
    }

    @Test
    fun clearingRemovesTheTarget() = runBlocking {
        repository.setActiveChildId(1L, 10L)

        repository.clearActiveChildId(1L)

        assertNull(repository.activeChildId(1L))
    }

    @Test
    fun clearingAnAbsentTargetIsSafe() = runBlocking {
        repository.clearActiveChildId(1L)

        assertNull(repository.activeChildId(1L))
    }

    // ---- account scoping ----------------------------------------------------

    @Test
    fun eachAccountKeepsItsOwnTarget() = runBlocking {
        repository.setActiveChildId(1L, 10L)
        repository.setActiveChildId(2L, 20L)

        assertEquals(10L, repository.activeChildId(1L))
        assertEquals(20L, repository.activeChildId(2L))
    }

    @Test
    fun oneAccountNeverSeesAnothersTarget() = runBlocking {
        repository.setActiveChildId(2L, 20L)

        assertNull("account 1 has no target of its own", repository.activeChildId(1L))
        assertEquals(20L, repository.activeChildId(2L))
    }

    @Test
    fun clearingOneAccountLeavesTheOtherIntact() = runBlocking {
        repository.setActiveChildId(1L, 10L)
        repository.setActiveChildId(2L, 20L)

        repository.clearActiveChildId(1L)

        assertNull(repository.activeChildId(1L))
        assertEquals("the other account is untouched", 20L, repository.activeChildId(2L))
    }

    @Test
    fun theTargetIsNotTiedToTheSignedInSession() = runBlocking {
        // The value is read and written by explicit account id, so it does not depend on
        // (or follow) whoever is signed in — which is what the background collector needs.
        repository.setActiveChildId(1L, 10L)
        sessionManager.setCurrentAccountId(2L)

        assertEquals(10L, repository.activeChildId(1L))

        sessionManager.clearSession()
        assertEquals(10L, repository.activeChildId(1L))
    }

    @Test
    fun theTargetIsStoredUnderTheAccountScopedKey() = runBlocking {
        repository.setActiveChildId(7L, 42L)

        val keyNames = dataStore.data.first().asMap().keys.map { it.name }

        assertTrue(
            "it must live under the account-scoped key, found $keyNames",
            keyNames.contains("acc_7_active_child_id"),
        )
        assertFalse(
            "and there must be no un-prefixed key that could leak across accounts",
            keyNames.contains("active_child_id"),
        )
    }

    @Test
    fun aFullResetWipesTheTarget() = runBlocking {
        repository.setActiveChildId(1L, 10L)

        store.clearAll()

        assertNull("a reset must not leave a stale screen-time target", repository.activeChildId(1L))
    }
}

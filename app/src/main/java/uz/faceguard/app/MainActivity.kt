package uz.faceguard.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import uz.faceguard.app.core.i18n.LanguageState
import uz.faceguard.app.core.i18n.LocalizedApp
import uz.faceguard.app.core.notification.NotificationNavigation
import uz.faceguard.app.core.protection.ProtectionRuntime
import uz.faceguard.app.core.security.AppLockState
import uz.faceguard.app.core.theme.FaceGuardTheme
import uz.faceguard.app.data.prefs.AppLanguageStore
import uz.faceguard.app.navigation.FaceGuardNavHost

/**
 * `FragmentActivity` (a `ComponentActivity`) is required by `BiometricPrompt`, which
 * needs a `FragmentActivity` host for its system dialog. It keeps every
 * `ComponentActivity` behaviour QALQON relied on — `setContent`, Hilt injection,
 * the CameraX lifecycle owner and the Activity context `hiltViewModel()` needs — so
 * the change is confined to the class declaration.
 *
 * The theme stays the platform Material theme: `FragmentActivity` does not require an
 * AppCompat theme, so no dependency or theme change is involved.
 */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    /**
     * Phase 7.1: the runtime needs to know when Qalqon is actually visible. That
     * is the legal while-in-use moment in which the process-scoped camera session
     * may be started (and, if an earlier background start was rejected, retried).
     */
    @Inject lateinit var protectionRuntime: ProtectionRuntime

    /** The app language, applied to the whole Compose tree at the root. */
    @Inject lateinit var languageStore: AppLanguageStore

    /** Whether the parent UI is unlocked for this process (runtime-only, not persisted). */
    @Inject lateinit var appLockState: AppLockState

    /**
     * Phase 11: a notification click asks for exactly one destination. It is kept
     * as state so the running composition (singleTop re-launch) can route it too.
     */
    private val requestedDestination = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Stage 7: Android 15 (API 35) enforces edge-to-edge for apps targeting 35+,
        // and Android 16 continues it. Calling enableEdgeToEdge() makes that behaviour
        // explicit and *uniform across API 26–36* (the library applies the right system
        // bar transparency/icon contrast and scrims on older levels), instead of the
        // app being edge-to-edge only from API 35 and opaque before it. Content insets
        // are consumed by each screen's Material3 Scaffold.
        enableEdgeToEdge()
        requestedDestination.value = intent?.getStringExtra(NotificationNavigation.EXTRA_DESTINATION)
        setContent {
            // Created outside LocalizedApp: the NavController must keep the real
            // Activity context (it resolves the Activity for navigation).
            val navController = rememberNavController()
            val languageState by languageStore.state.collectAsStateWithLifecycle()
            val language = (languageState as? LanguageState.Selected)?.language
            val unlocked by appLockState.unlocked.collectAsStateWithLifecycle()

            FaceGuardTheme {
                // Applying the selected language here makes every string in the
                // tree — including the first-launch picker itself — re-render in
                // the chosen language immediately, with no activity restart.
                LocalizedApp(language) {
                    FaceGuardNavHost(
                        navController = navController,
                        requestedDestination = requestedDestination.value,
                        isUnlocked = unlocked,
                        // Authoritative read for the gate itself, so a navigation in
                        // the same frame as a successful unlock is never bounced back.
                        isUnlockedNow = { appLockState.isUnlocked() },
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Returning to the foreground cancels a pending re-lock, so a quick switch
        // away and back keeps the parent's session (see AppLockState.lockAfter).
        appLockState.cancelPendingLock()
        protectionRuntime.onUiForeground()
    }

    override fun onStop() {
        // The camera session is deliberately not stopped here: it is process-scoped
        // and keeps running while protection is active (Phase 7.1).
        protectionRuntime.onUiBackground()
        // Re-lock the parent UI once the grace period elapses, so a handed-over or
        // pocketed device returns to the credential gate without punishing a brief
        // app switch. The timer runs on the Activity lifecycle scope.
        appLockState.lockAfter(lifecycleScope, AppLockState.DEFAULT_BACKGROUND_GRACE_MILLIS)
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        requestedDestination.value = intent.getStringExtra(NotificationNavigation.EXTRA_DESTINATION)
    }
}

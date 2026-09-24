package uz.faceguard.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import uz.faceguard.app.core.notification.NotificationNavigation
import uz.faceguard.app.core.theme.FaceGuardTheme
import uz.faceguard.app.navigation.FaceGuardNavHost

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    /**
     * Phase 11: a notification click asks for exactly one destination. It is kept
     * as state so the running composition (singleTop re-launch) can route it too.
     */
    private val requestedDestination = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedDestination.value = intent?.getStringExtra(NotificationNavigation.EXTRA_DESTINATION)
        setContent {
            FaceGuardTheme {
                val navController = rememberNavController()
                FaceGuardNavHost(
                    navController = navController,
                    requestedDestination = requestedDestination.value,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        requestedDestination.value = intent.getStringExtra(NotificationNavigation.EXTRA_DESTINATION)
    }
}

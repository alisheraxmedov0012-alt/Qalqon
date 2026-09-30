package uz.faceguard.app.feature.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import uz.faceguard.app.R
import uz.faceguard.app.core.i18n.StartupDestination
import uz.faceguard.app.core.i18n.startupDestination
import uz.faceguard.app.core.security.AppLockState
import uz.faceguard.app.data.prefs.AppLanguageStore
import uz.faceguard.app.domain.repository.AccountRepository

@HiltViewModel
class SplashViewModel @Inject constructor(
    private val accountRepository: AccountRepository,
    private val languageStore: AppLanguageStore,
    private val appLockState: AppLockState,
) : ViewModel() {
    /** A registered account exists when an account id is persisted in DataStore. */
    suspend fun hasRegisteredAccount(): Boolean = accountRepository.currentAccountId.first() != null

    /**
     * The single startup decision: the persisted language, whether an account is
     * registered, and whether this process has been unlocked.
     *
     * A registered account alone is **not** enough to reach Home — the parent UI
     * must have been unlocked in this process, otherwise the PIN screen comes first.
     */
    suspend fun resolveDestination(): StartupDestination =
        startupDestination(
            language = languageStore.read(),
            hasRegisteredAccount = hasRegisteredAccount(),
            isUnlocked = appLockState.isUnlocked(),
        )
}

@Composable
fun SplashScreen(
    onReady: (StartupDestination) -> Unit,
    viewModel: SplashViewModel = hiltViewModel(),
) {
    LaunchedEffect(Unit) {
        delay(700) // short branding pause
        onReady(viewModel.resolveDestination())
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            stringResource(R.string.splash_tagline),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

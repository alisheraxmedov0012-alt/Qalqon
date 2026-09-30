package uz.faceguard.app.testing

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import uz.faceguard.app.domain.model.ProtectedApp
import uz.faceguard.app.domain.repository.ProtectedAppsRepository

/**
 * Deterministic in-memory installed-app catalogue for instrumented tests.
 *
 * `ProtectedAppsRepositoryImpl.refreshFromDevice()` queries the live
 * PackageManager and prunes rows the device does not report, which cannot be
 * made deterministic and also races the in-memory Room database on teardown.
 * The repositories under test stay real; only this device-facing catalogue seam
 * is replaced.
 */
internal class FakeProtectedAppsRepository : ProtectedAppsRepository {

    private val state = MutableStateFlow<List<ProtectedApp>>(emptyList())

    override val protectedApps: Flow<List<ProtectedApp>> = state

    override suspend fun refreshFromDevice() = Unit

    override suspend fun toggleProtection(packageName: String, isProtected: Boolean) {
        state.value = state.value.map { app ->
            if (app.packageName == packageName) app.copy(isProtected = isProtected) else app
        }
    }

    override suspend fun countProtected(): Int = state.value.count { it.isProtected }

    fun seed(apps: List<ProtectedApp>) {
        state.value = apps
    }
}

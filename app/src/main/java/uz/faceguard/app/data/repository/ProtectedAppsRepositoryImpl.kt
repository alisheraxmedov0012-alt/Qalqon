package uz.faceguard.app.data.repository

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import uz.faceguard.app.data.db.ProtectedAppDao
import uz.faceguard.app.data.db.ProtectedAppEntity
import uz.faceguard.app.domain.model.ProtectedApp
import uz.faceguard.app.domain.repository.ProtectedAppsRepository

/**
 * Backed by the actual installed-apps list via PackageManager (launchable
 * activities only); persists selections in Room. Replacing the PackageManager
 * query with a narrower catalog stays behind this interface.
 */
@Singleton
class ProtectedAppsRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: ProtectedAppDao,
) : ProtectedAppsRepository {

    override val protectedApps: Flow<List<ProtectedApp>> = dao.observeAll().map { rows ->
        rows.map { row ->
            ProtectedApp(
                packageName = row.packageName,
                appDisplayName = row.appDisplayName,
                isProtected = row.isProtected,
            )
        }
    }

    override suspend fun refreshFromDevice() {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val packages = context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        val now = System.currentTimeMillis()
        val entities = packages.mapNotNull { resolved ->
            val activity = resolved.activityInfo ?: return@mapNotNull null
            val pkg = activity.packageName ?: return@mapNotNull null
            if (pkg == context.packageName) return@mapNotNull null
            val label = resolved.loadLabel(context.packageManager)?.toString()?.takeIf { it.isNotBlank() }
                ?: pkg
            ProtectedAppEntity(
                packageName = pkg,
                appDisplayName = label,
                isProtected = false,
                updatedAt = now,
            )
        }.distinctBy { it.packageName }
        dao.upsertAll(entities)
    }

    override suspend fun toggleProtection(packageName: String, isProtected: Boolean) =
        dao.setProtection(packageName, isProtected, System.currentTimeMillis())

    override suspend fun countProtected(): Int = dao.countProtected()
}

package uz.faceguard.app.data.repository

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
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

    override suspend fun refreshFromDevice() = withContext(Dispatchers.IO) {
        val packageManager = context.packageManager
        val existingProtection = dao.getAll().associate { it.packageName to it.isProtected }
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

        val discovered = packageManager
            .queryIntentActivities(intent, PackageManager.MATCH_ALL)
            .mapNotNull { resolved ->
                val activity = resolved.activityInfo ?: return@mapNotNull null
                val appInfo = activity.applicationInfo ?: return@mapNotNull null
                val packageName = activity.packageName ?: return@mapNotNull null
                if (!isVisibleForParentList(packageName, appInfo)) return@mapNotNull null

                val label = resolved
                    .loadLabel(packageManager)
                    ?.toString()
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?: packageName
                packageName to label
            }
            .distinctBy { (packageName, _) -> packageName }

        if (discovered.isEmpty()) return@withContext

        val now = System.currentTimeMillis()
        val merged = discovered.map { (packageName, label) ->
            ProtectedAppEntity(
                packageName = packageName,
                appDisplayName = label,
                isProtected = existingProtection[packageName] ?: false,
                updatedAt = now,
            )
        }

        dao.upsertAll(merged)
        if (merged.size <= SQLITE_IN_LIMIT) {
            dao.deleteNotIn(merged.map { it.packageName })
        }
    }

    private fun isVisibleForParentList(packageName: String, appInfo: ApplicationInfo): Boolean {
        if (packageName == context.packageName) return false
        if (!appInfo.enabled) return false
        if (EXCLUDED_PACKAGES.contains(packageName)) return false
        if (EXCLUDED_PREFIXES.any { packageName.startsWith(it) }) return false

        val isSystemApp = appInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0
        val isUpdatedSystemApp = appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0
        if (isSystemApp && !isUpdatedSystemApp) {
            if (SYSTEM_UTILITY_PREFIXES.any { packageName.startsWith(it) }) return false
        }

        return true
    }


    override suspend fun toggleProtection(packageName: String, isProtected: Boolean) =
        dao.setProtection(packageName, isProtected, System.currentTimeMillis())

    override suspend fun countProtected(): Int = dao.countProtected()

    private companion object {
        const val SQLITE_IN_LIMIT = 900

        val EXCLUDED_PACKAGES = setOf(
            "android",
            "com.android.settings",
            "com.android.systemui",
            "com.google.android.permissioncontroller",
            "com.google.android.packageinstaller",
            "com.google.android.documentsui",
            "com.google.android.inputmethod.latin",
            "com.android.traceur",
        )

        val EXCLUDED_PREFIXES = listOf(
            "android.",
            "com.android.cts.",
            "com.android.test.",
            "com.android.overlay.",
            "com.qualcomm.",
            "com.mediatek.",
        )

        val SYSTEM_UTILITY_PREFIXES = listOf(
            "com.android.systemui",
            "com.android.launcher",
            "com.android.providers.",
            "com.android.printspooler",
            "com.android.bluetooth",
            "com.android.nfc",
            "com.android.wallpaper",
            "com.google.android.ext.",
        )
    }
}

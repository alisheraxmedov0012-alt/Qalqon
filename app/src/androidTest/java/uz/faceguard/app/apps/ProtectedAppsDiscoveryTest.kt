package uz.faceguard.app.apps

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import uz.faceguard.app.data.db.FaceGuardDatabase
import uz.faceguard.app.data.db.ProtectedAppEntity
import uz.faceguard.app.data.repository.ProtectedAppsRepositoryImpl
import uz.faceguard.app.domain.model.ProtectedApp

/**
 * Real-device verification of the protected-app catalogue.
 *
 * Android 11+ package visibility filtering is enforced by the platform inside
 * `PackageManager.queryIntentActivities`, so it can only be exercised on a
 * device/emulator (this runs in the CI `instrumented-tests` job). These tests use
 * the real [ProtectedAppsRepositoryImpl], the real `PackageManager` and real
 * (in-memory) Room — no fakes.
 *
 * The companion JVM test `ProtectedAppsVisibilityManifestTest` asserts the
 * manifest declaration that makes third-party launcher apps visible here at all.
 */
@RunWith(AndroidJUnit4::class)
class ProtectedAppsDiscoveryTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val packageManager: PackageManager get() = context.packageManager

    private lateinit var db: FaceGuardDatabase
    private lateinit var repository: ProtectedAppsRepositoryImpl

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, FaceGuardDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = ProtectedAppsRepositoryImpl(context, db.protectedAppDao())
    }

    @After
    fun tearDown() = db.close()

    /** Every package the platform reports for the launcher intent, unfiltered. */
    private fun installedLauncherPackages(): Set<String> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager
            .queryIntentActivities(intent, PackageManager.MATCH_ALL)
            .mapNotNull { it.activityInfo?.packageName }
            .toSet()
    }

    private fun refreshAndRead(): List<ProtectedApp> = runBlocking {
        repository.refreshFromDevice()
        repository.protectedApps.first()
    }

    /**
     * The packages a parent should be able to choose from: everything the platform
     * reports for the launcher intent, minus QALQON's own documented exclusions.
     *
     * The exclusions are mirrored here on purpose — the point of the assertion is
     * that *visibility* is not silently dropping launcher apps, which is exactly
     * the bug the targeted `<queries>` declaration fixes.
     */
    private fun expectedOfferedPackages(): Set<String> =
        installedLauncherPackages().filter { name ->
            if (name == context.packageName) return@filter false
            val info = runCatching { packageManager.getApplicationInfo(name, 0) }.getOrNull()
                ?: return@filter false
            if (!info.enabled) return@filter false
            if (name in DOCUMENTED_EXCLUSIONS) return@filter false
            if (DOCUMENTED_EXCLUDED_PREFIXES.any { name.startsWith(it) }) return@filter false

            val isSystemApp = info.flags and ApplicationInfo.FLAG_SYSTEM != 0
            val isUpdatedSystemApp = info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0
            if (isSystemApp && !isUpdatedSystemApp &&
                DOCUMENTED_SYSTEM_UTILITY_PREFIXES.any { name.startsWith(it) }
            ) {
                return@filter false
            }
            true
        }.toSet()

    // ---------------------------------------- discovery keeps launcher apps ----

    @Test
    fun thePickerExposesTheDevicesLauncherApps() {
        val discovered = refreshAndRead()

        assertTrue(
            "the picker must list the device's launchable apps, but discovery returned nothing",
            discovered.isNotEmpty(),
        )
        discovered.forEach { app ->
            assertTrue("blank package name", app.packageName.isNotBlank())
            assertTrue("blank display name for ${app.packageName}", app.appDisplayName.isNotBlank())
        }
    }

    /**
     * The fix itself: every launcher app the platform reports (after QALQON's
     * documented exclusions) must actually reach the picker. A visibility
     * regression reappears here as a non-empty `missing` set.
     */
    @Test
    fun everyVisibleLauncherAppIsExposedOrDocumentedAsExcluded() {
        val discovered = refreshAndRead().map { it.packageName }.toSet()
        val missing = expectedOfferedPackages() - discovered

        assertTrue(
            "launcher apps visible to the platform but missing from the picker: ${missing.sorted()}",
            missing.isEmpty(),
        )
    }

    @Test
    fun everythingOfferedIsARealInstalledLauncherApp() {
        val discovered = refreshAndRead().map { it.packageName }
        val installed = installedLauncherPackages()

        val offenders = discovered.filterNot { it in installed }
        assertTrue("offered packages that are not launchable: $offenders", offenders.isEmpty())
    }

    // -------------------------------------------- documented exclusions hold --

    @Test
    fun qalqonNeverOffersItself() {
        val discovered = refreshAndRead().map { it.packageName }
        assertFalse(
            "QALQON must not list itself as a protectable app",
            discovered.contains(context.packageName),
        )
    }

    @Test
    fun systemUtilitiesAreNotOffered() {
        val discovered = refreshAndRead().map { it.packageName }
        val offenders = discovered.filter { name ->
            name in DOCUMENTED_EXCLUSIONS ||
                DOCUMENTED_EXCLUDED_PREFIXES.any { name.startsWith(it) } ||
                DOCUMENTED_SYSTEM_UTILITY_PREFIXES.any { name.startsWith(it) }
        }
        assertTrue("system utilities leaked into the picker: $offenders", offenders.isEmpty())
    }

    // ---------------------------- third-party packages are storable + kept ----

    @Test
    fun thirdPartyPackagesAreStoredAndExposedByPackageName() = runBlocking {
        val dao = db.protectedAppDao()
        dao.upsertAll(
            listOf(
                "com.instagram.android",
                "org.telegram.messenger",
                "com.google.android.youtube",
            ).map { ProtectedAppEntity(packageName = it, appDisplayName = it, isProtected = false) },
        )

        repository.toggleProtection("com.instagram.android", true)
        repository.toggleProtection("org.telegram.messenger", true)

        val listed = repository.protectedApps.first().associateBy { it.packageName }
        assertTrue(listed.containsKey("com.instagram.android"))
        assertTrue(listed.containsKey("org.telegram.messenger"))
        assertTrue(listed.containsKey("com.google.android.youtube"))
        assertTrue(listed.getValue("com.instagram.android").isProtected)
        assertTrue(listed.getValue("org.telegram.messenger").isProtected)
        assertFalse(listed.getValue("com.google.android.youtube").isProtected)
        assertEquals(2, repository.countProtected())
    }

    /**
     * The existing pruning contract must be unchanged: a row for a package the
     * device no longer reports is removed, while rows that are still reported are
     * retained **with their protection flag intact**.
     */
    @Test
    fun refreshPrunesUnreportedPackagesAndPreservesReportedOnes() = runBlocking {
        val dao = db.protectedAppDao()
        val discovered = refreshAndRead().map { it.packageName }
        val stillPresent = discovered.firstOrNull() ?: error("no launcher app available to test with")

        dao.setProtection(stillPresent, true, System.currentTimeMillis())
        dao.upsertAll(
            listOf(ProtectedAppEntity("com.example.absent.app", "Absent", isProtected = true)),
        )

        // A refresh reports only what the device currently exposes.
        repository.refreshFromDevice()

        val listed = repository.protectedApps.first().associateBy { it.packageName }
        assertFalse(
            "a package the device no longer reports must be pruned (existing contract)",
            listed.containsKey("com.example.absent.app"),
        )
        assertTrue(listed.containsKey(stillPresent))
        assertTrue(
            "the protection flag of a still-visible app must survive the refresh",
            listed.getValue(stillPresent).isProtected,
        )
    }

    private companion object {
        /**
         * Mirrors QALQON's documented exclusions as a regression fixture. If the
         * production filter changes, these lists must be updated together — the
         * tests above then still prove that visibility itself is not the cause of
         * a missing app.
         */
        val DOCUMENTED_EXCLUSIONS = setOf(
            "android",
            "com.android.settings",
            "com.android.systemui",
            "com.google.android.permissioncontroller",
            "com.google.android.packageinstaller",
            "com.google.android.documentsui",
            "com.google.android.inputmethod.latin",
            "com.android.traceur",
        )

        val DOCUMENTED_EXCLUDED_PREFIXES = listOf(
            "android.",
            "com.android.cts.",
            "com.android.test.",
            "com.android.overlay.",
            "com.qualcomm.",
            "com.mediatek.",
        )

        val DOCUMENTED_SYSTEM_UTILITY_PREFIXES = listOf(
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

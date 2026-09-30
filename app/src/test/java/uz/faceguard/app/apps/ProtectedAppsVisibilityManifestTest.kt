package uz.faceguard.app.apps

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * Regression guard for the protected-app picker's package visibility.
 *
 * Android 11+ hides third-party packages from `queryIntentActivities` unless the
 * app declares what it needs to see. QALQON enumerates the launcher apps, so the
 * single declaration it requires is the launcher intent; without it Instagram,
 * Telegram and every other user-installed app silently disappear from the picker.
 *
 * The tests read the *source* manifest directly (no device needed), so removing
 * the declaration — or "fixing" it by granting the broad QUERY_ALL_PACKAGES
 * permission or re-adding INTERNET — fails the JVM build immediately.
 */
class ProtectedAppsVisibilityManifestTest {

    private val android = "http://schemas.android.com/apk/res/android"
    private val tools = "http://schemas.android.com/tools"

    private val manifest: Element by lazy {
        val file = File(repoRoot(), "app/src/main/AndroidManifest.xml")
        assertTrue("manifest not found at ${file.path}", file.isFile)
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        factory.newDocumentBuilder().parse(file).documentElement
    }

    private fun elements(parent: Element, tag: String): List<Element> {
        val nodes = parent.getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun androidAttr(element: Element, name: String): String? =
        element.getAttributeNS(android, name).takeIf { it.isNotBlank() }

    private fun toolAttr(element: Element, name: String): String? =
        element.getAttributeNS(tools, name).takeIf { it.isNotBlank() }

    private fun permissionNames(): List<String> =
        elements(manifest, "uses-permission").mapNotNull { androidAttr(it, "name") }

    /** A permission is only *granted* when it is not declared as a node removal. */
    private fun grantedPermissions(): List<String> =
        elements(manifest, "uses-permission")
            .filterNot { toolAttr(it, "node") == "remove" }
            .mapNotNull { androidAttr(it, "name") }

    // ------------------------------------------------- targeted visibility (A)

    @Test
    fun declaresExactlyOneTargetedQueriesBlock() {
        val queries = elements(manifest, "queries")
        assertEquals("expected exactly one <queries> block", 1, queries.size)
    }

    @Test
    fun queriesGrantTheLauncherIntentQalqonEnumerates() {
        val queries = elements(manifest, "queries").single()
        val intents = elements(queries, "intent")
        assertTrue("the <queries> block must declare the launcher intent", intents.isNotEmpty())

        val launcherIntent = intents.any { intent ->
            val actions = elements(intent, "action").mapNotNull { androidAttr(it, "name") }
            val categories = elements(intent, "category").mapNotNull { androidAttr(it, "name") }
            "android.intent.action.MAIN" in actions &&
                "android.intent.category.LAUNCHER" in categories
        }
        assertTrue(
            "<queries> must include an <intent> with MAIN + LAUNCHER — this is what makes " +
                "user-installed launcher apps visible to the protected-app picker",
            launcherIntent,
        )
    }

    @Test
    fun theLauncherQueryMatchesWhatTheRepositoryActuallyAsksFor() {
        // Guard against the declaration and the code drifting apart.
        val repository = File(
            repoRoot(),
            "app/src/main/java/uz/faceguard/app/data/repository/ProtectedAppsRepositoryImpl.kt",
        ).readText()

        assertTrue(
            "the repository must still query the launcher intent",
            repository.contains("Intent.ACTION_MAIN") &&
                repository.contains("Intent.CATEGORY_LAUNCHER"),
        )
    }

    // ---------------------------------------------- broad permission (D) ------

    @Test
    fun theBroadQueryAllPackagesPermissionIsNotDeclared() {
        assertFalse(
            "QUERY_ALL_PACKAGES must not be requested",
            permissionNames().any { it.contains("QUERY_ALL_PACKAGES") },
        )
    }

    @Test
    fun theManifestTextNeverMentionsQueryAllPackages() {
        val raw = File(repoRoot(), "app/src/main/AndroidManifest.xml").readText()
        assertFalse(
            "QUERY_ALL_PACKAGES must not appear anywhere in the manifest",
            raw.contains("QUERY_ALL_PACKAGES"),
        )
    }

    // ------------------------------------------------ offline-first (E) ------

    @Test
    fun internetIsNotGranted() {
        assertFalse(
            "INTERNET must never be granted; QALQON is offline-first",
            grantedPermissions().contains("android.permission.INTERNET"),
        )
    }

    @Test
    fun networkStateIsNotGranted() {
        assertFalse(
            "ACCESS_NETWORK_STATE must never be granted",
            grantedPermissions().contains("android.permission.ACCESS_NETWORK_STATE"),
        )
    }

    @Test
    fun theRemovalDirectivesForTheInjectedNetworkPermissionsAreStillInPlace() {
        // ML Kit's transitive dependency injects these via manifest merging; the
        // explicit removals are what keep the merged release manifest offline.
        listOf("android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE").forEach { name ->
            val declared = elements(manifest, "uses-permission").any {
                androidAttr(it, "name") == name && toolAttr(it, "node") == "remove"
            }
            assertTrue("missing tools:node=\"remove\" for $name", declared)
        }
    }

    // --------------------------------------------- preserved capabilities ----

    @Test
    fun theExistingCapabilitiesAreStillDeclared() {
        val granted = grantedPermissions()
        listOf(
            "android.permission.CAMERA",
            "android.permission.PACKAGE_USAGE_STATS",
            "android.permission.SYSTEM_ALERT_WINDOW",
            "android.permission.FOREGROUND_SERVICE",
            "android.permission.RECEIVE_BOOT_COMPLETED",
        ).forEach { name ->
            assertTrue("$name must remain declared", granted.contains(name))
        }
        // The declaration is additive: nothing was removed from the manifest.
        assertTrue(permissionNames().isNotEmpty())
    }

    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        while (dir.parentFile != null) {
            if (File(dir, "app/src/main/res/values/strings.xml").isFile) return dir
            dir = dir.parentFile
        }
        error("could not locate the repository root from ${System.getProperty("user.dir")}")
    }
}

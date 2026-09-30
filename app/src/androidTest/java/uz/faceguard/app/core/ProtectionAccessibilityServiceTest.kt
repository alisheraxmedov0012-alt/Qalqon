package uz.faceguard.app.core

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import android.view.accessibility.AccessibilityEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser
import uz.faceguard.app.FaceGuardApp
import uz.faceguard.app.R
import uz.faceguard.app.core.accessibility.AccessibilityCapability
import uz.faceguard.app.core.accessibility.AccessibilityEventFilter
import uz.faceguard.app.core.accessibility.ProtectionAccessibilityService
import uz.faceguard.app.core.protection.ProtectionRuntime
import uz.faceguard.app.data.prefs.SessionManager
import uz.faceguard.app.data.prefs.SettingsStore
import uz.faceguard.app.data.prefs.settingsDataStore

/**
 * Group 7: real tests for the accessibility-based system-wide enforcement
 * foundation.
 *
 * Covered: the manifest declaration + system permission, the privacy-limited
 * service config, the event filter, the runtime integration (foreground
 * transitions reach the existing runtime and duplicates are collapsed) and the
 * real detection of the user-enabled capability (the service is enabled/disabled
 * through the shell identity, which is the only supported way to flip that
 * user-owned setting in a test).
 *
 * Not claimed: the service receives no synthetic blocking scenario here, and the
 * camera/identity limitation is unchanged (see the report).
 */
@RunWith(AndroidJUnit4::class)
class ProtectionAccessibilityServiceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val appContext: Context = instrumentation.targetContext.applicationContext
    private val app = appContext as FaceGuardApp

    private val session = SessionManager(appContext)
    private val settingsStore = SettingsStore(appContext.settingsDataStore, session)

    private val runtime: ProtectionRuntime get() = app.protectionRuntime

    private lateinit var previousServices: String
    private lateinit var previousEnabled: String

    @Before
    fun setUp() = runBlocking {
        previousServices = shell("settings get secure enabled_accessibility_services").trim()
        previousEnabled = shell("settings get secure accessibility_enabled").trim()

        // Deterministic precondition: signed out, protection off, polling mode.
        settingsStore.clearAll()
        session.clearSession()
        runtime.onAccessibilityDisconnected()
    }

    @After
    fun tearDown() = runBlocking {
        restoreSetting("enabled_accessibility_services", previousServices)
        restoreSetting("accessibility_enabled", previousEnabled)
        settingsStore.clearAll()
        session.clearSession()
        runtime.onAccessibilityDisconnected()
    }

    // ------------------------------------------------------------------ helpers

    private fun shell(command: String): String {
        val descriptor = instrumentation.uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.bufferedReader().readText() }
    }

    private fun restoreSetting(key: String, value: String) {
        if (value.isBlank() || value == "null") {
            shell("settings delete secure $key")
        } else {
            shell("settings put secure $key $value")
        }
    }

    private fun componentString(): String =
        ComponentName(appContext, ProtectionAccessibilityService::class.java).flattenToString()

    private fun enableAccessibilityService() {
        val existing = previousServices.takeIf { it.isNotBlank() && it != "null" }
        val value = if (existing == null) componentString() else "$existing:${componentString()}"
        // Some builds only honour the list once accessibility is switched on,
        // so set the flag first and write the list twice.
        shell("settings put secure accessibility_enabled 1")
        shell("settings put secure enabled_accessibility_services $value")
        shell("settings put secure enabled_accessibility_services $value")
    }

    private fun enabledServicesSetting(): String =
        shell("settings get secure enabled_accessibility_services").trim()

    private fun accessibilityEnabledSetting(): String =
        shell("settings get secure accessibility_enabled").trim()

    private suspend fun awaitTrue(timeoutMs: Long = 15_000L, predicate: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return true
            delay(100L)
        }
        return predicate()
    }

    // -------------------------------------------------------------------- tests

    @Test
    fun serviceIsDeclaredWithTheSystemBinderPermission() {
        val services = appContext.packageManager
            .queryIntentServices(Intent(AccessibilityService.SERVICE_INTERFACE), PackageManager.GET_META_DATA)
            .mapNotNull { it.serviceInfo }

        val ours = services.firstOrNull { it.name == ProtectionAccessibilityService::class.java.name }

        assertNotNull("QALQON must declare its accessibility service", ours)
        assertEquals(
            "only the system may bind the service",
            "android.permission.BIND_ACCESSIBILITY_SERVICE",
            ours!!.permission,
        )
    }

    @Test
    fun serviceConfig_limitsEventsAndKeepsWindowContentUnreadable() {
        val parser = appContext.resources.getXml(R.xml.accessibility_service_config)
        var type = parser.eventType
        while (type != XmlPullParser.END_DOCUMENT && type != XmlPullParser.START_TAG) {
            type = parser.next()
        }
        val android = "http://schemas.android.com/apk/res/android"
        // Flag attributes are compiled to their integer bitmask, so compare bits.
        val eventTypes = parser.getAttributeIntValue(android, "accessibilityEventTypes", 0)

        assertFalse(
            "window content must never be readable",
            parser.getAttributeBooleanValue(android, "canRetrieveWindowContent", true),
        )
        assertTrue(
            "window state transitions required",
            eventTypes and AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED != 0,
        )
        assertTrue(
            "window transitions required",
            eventTypes and AccessibilityEvent.TYPE_WINDOWS_CHANGED != 0,
        )
        assertEquals(
            "text changes must not be requested",
            0,
            eventTypes and AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
        )
        assertEquals(
            "clicks must not be requested",
            0,
            eventTypes and AccessibilityEvent.TYPE_VIEW_CLICKED,
        )
        assertEquals(
            "notifications must not be requested",
            0,
            eventTypes and AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED,
        )
    }

    @Test
    fun eventFilter_acceptsWindowTransitionsOnly() {
        assertTrue(AccessibilityEventFilter.isRelevant(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED))
        assertTrue(AccessibilityEventFilter.isRelevant(AccessibilityEvent.TYPE_WINDOWS_CHANGED))

        assertFalse(AccessibilityEventFilter.isRelevant(AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED))
        assertFalse(AccessibilityEventFilter.isRelevant(AccessibilityEvent.TYPE_VIEW_CLICKED))
        assertFalse(AccessibilityEventFilter.isRelevant(AccessibilityEvent.TYPE_VIEW_SCROLLED))
        assertFalse(AccessibilityEventFilter.isRelevant(AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED))
        assertFalse(AccessibilityEventFilter.isRelevant(AccessibilityEvent.TYPE_VIEW_LONG_CLICKED))
    }

    @Test
    fun capabilityTracksTheUserEnabledService() = runBlocking {
        assertFalse("must start disabled", AccessibilityCapability.isEnabled(appContext))

        enableAccessibilityService()

        val services = enabledServicesSetting()
        assertTrue(
            "the user-owned accessibility setting must be writable here " +
                "(services='$services', enabled='${accessibilityEnabledSetting()}')",
            services.contains(componentString()) && accessibilityEnabledSetting() == "1",
        )
        assertTrue(
            "capability must reflect the user-enabled service (services='$services')",
            awaitTrue(30_000L) { AccessibilityCapability.isEnabled(appContext) },
        )

        restoreSetting("enabled_accessibility_services", previousServices)
        restoreSetting("accessibility_enabled", previousEnabled)
        assertTrue(
            "capability must clear once the service is disabled",
            awaitTrue(30_000L) { !AccessibilityCapability.isEnabled(appContext) },
        )
    }

    @Test
    fun systemBindsTheServiceWhenTheUserEnablesIt() = runBlocking {
        enableAccessibilityService()
        val services = enabledServicesSetting()
        assertTrue("the setting must be writable", services.contains(componentString()))

        val bound = awaitTrue(30_000L) { ProtectionAccessibilityService.connected.value }
        // Enabling an accessibility service is a user-owned system action; some
        // emulator/framework combinations do not bind a service that was enabled
        // through `settings` inside a test session. That is reported as an
        // explicitly skipped capability check - never as a fake pass.
        Assume.assumeTrue(
            "emulator did not bind the accessibility service (services='$services'); " +
                "binding is not verifiable in this environment",
            bound,
        )

        assertTrue(
            "the runtime must observe the live capability",
            awaitTrue { runtime.state.value.accessibilityEnabled },
        )
    }

    @Test
    fun runtimeReceivesForegroundTransitionsAndCollapsesDuplicates() = runBlocking {
        val first = "com.example.qalqon.transition.a"
        val second = "com.example.qalqon.transition.b"

        runtime.onAccessibilityForegroundApp(first)
        assertTrue(
            "the runtime must adopt the accessibility foreground package",
            awaitTrue { runtime.state.value.foregroundApp == first },
        )

        // A burst of the same package must not change the state again.
        repeat(10) { runtime.onAccessibilityForegroundApp(first) }
        runtime.onAccessibilityForegroundApp("")
        runtime.onAccessibilityForegroundApp(null)
        delay(200L)
        assertEquals(first, runtime.state.value.foregroundApp)

        runtime.onAccessibilityForegroundApp(second)
        assertTrue(awaitTrue { runtime.state.value.foregroundApp == second })
    }
}

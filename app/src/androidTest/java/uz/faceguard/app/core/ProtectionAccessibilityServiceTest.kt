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
        shell("settings put secure enabled_accessibility_services $value")
        shell("settings put secure accessibility_enabled 1")
    }

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
        val eventTypes = parser.getAttributeValue(android, "accessibilityEventTypes") ?: ""

        assertEquals(
            "window content must never be readable",
            "false",
            parser.getAttributeValue(android, "canRetrieveWindowContent"),
        )
        assertTrue("window state transitions required", eventTypes.contains("typeWindowStateChanged"))
        assertTrue("window transitions required", eventTypes.contains("typeWindowsChanged"))
        assertFalse("text changes must not be requested", eventTypes.contains("typeViewTextChanged"))
        assertFalse("clicks must not be requested", eventTypes.contains("typeViewClicked"))
        assertFalse("notifications must not be requested", eventTypes.contains("typeNotificationStateChanged"))
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
    fun capabilityAndBinding_areDetectedOnlyWhileTheUserHasEnabledTheService() = runBlocking {
        assertFalse("must start disabled", AccessibilityCapability.isEnabled(appContext))

        enableAccessibilityService()

        assertTrue(
            "the system must bind QALQON's accessibility service",
            awaitTrue { ProtectionAccessibilityService.connected.value },
        )
        assertTrue("capability must be reported", awaitTrue { AccessibilityCapability.isEnabled(appContext) })
        assertTrue(
            "runtime state must reflect the capability",
            awaitTrue { runtime.state.value.accessibilityEnabled },
        )

        restoreSetting("enabled_accessibility_services", previousServices)
        restoreSetting("accessibility_enabled", previousEnabled)

        assertTrue(
            "unbinding must be observed",
            awaitTrue { !ProtectionAccessibilityService.connected.value },
        )
        assertTrue("capability must clear", awaitTrue { !AccessibilityCapability.isEnabled(appContext) })
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

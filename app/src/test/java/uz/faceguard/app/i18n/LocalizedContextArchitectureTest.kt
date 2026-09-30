package uz.faceguard.app.i18n

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Architectural regression guard for the language-selection crash.
 *
 * The crash was caused by `LocalizedApp` providing a bare
 * `Context.createConfigurationContext(...)` result as `LocalContext`. That context
 * is a `ContextImpl` — not a `ContextWrapper` — so Hilt's `HiltViewModelFactory`
 * could not unwrap to the `ComponentActivity` and threw
 * `IllegalStateException`, killing the app the moment a language was applied.
 *
 * The fix keeps the Activity in the chain: the Compose tree is given a
 * `ContextThemeWrapper` **around the Activity**, whose `baseContext` is the real
 * Activity. These tests pin that invariant at the source level so the crashing
 * pattern cannot silently return. (The behavioural counterpart lives in the
 * instrumented `LocalizedContextActivitySafetyTest`, which needs a real Activity.)
 */
class LocalizedContextArchitectureTest {

    private val appLocale: String by lazy { read("core/i18n/AppLanguage.kt") }
    private val localizedApp: String by lazy { read("core/i18n/LocalizedApp.kt") }
    private val notifications: String by lazy { read("core/notification/AndroidNotifications.kt") }
    private val overlay: String by lazy { read("core/protection/OverlayControllerImpl.kt") }

    // 1. The Compose tree must NOT be handed a bare createConfigurationContext result.
    @Test
    fun theComposeTreeUsesTheActivityRootedLocalizedContext() {
        assertTrue(
            "LocalizedApp must localize through AppLocale.localizedAppContext",
            localizedApp.contains("AppLocale.localizedAppContext(baseContext, language)"),
        )
        assertFalse(
            "LocalizedApp must not localize the tree with the bare createConfigurationContext helper",
            localizedApp.contains("AppLocale.localizedContext(baseContext, language)"),
        )
    }

    @Test
    fun theTreeProviderOnlyEverOffersTheLocalesContextAndConfiguration() {
        // The composition locals the tree is given must come from the localized pair,
        // never from a context that could have lost the Activity.
        assertTrue(localizedApp.contains("LocalContext provides localized.first"))
        assertTrue(localizedApp.contains("LocalConfiguration provides localized.second"))
    }

    // 2. The Activity-rooted context must be a ContextWrapper around the Activity.
    @Test
    fun theActivityRootedContextIsAContextThemeWrapperAroundTheActivity() {
        assertTrue(
            "the localized app context must wrap the Activity in a ContextThemeWrapper",
            appLocale.contains("ContextThemeWrapper(activity, 0)"),
        )
        assertTrue(
            "the wrapper must apply the language as a configuration override",
            appLocale.contains("applyOverrideConfiguration("),
        )
        assertTrue(
            "the Activity must be located through the context-wrapper chain",
            appLocale.contains("fun Context.findComponentActivity(): ComponentActivity?"),
        )
    }

    @Test
    fun theActivityRootedContextFallsBackSafelyWhenThereIsNoActivity() {
        // e.g. the protection overlay, whose ComposeView renders from the application
        // context: no Activity to wrap, and no Hilt ViewModel is created there.
        assertTrue(
            "localizedAppContext must fall back to the plain localized context",
            appLocale.contains("?: return localizedContext(base, language)"),
        )
    }

    @Test
    fun theActivityLookupMatchesHiltsContract() {
        // Hilt unwraps ContextWrapper.baseContext looking for a ComponentActivity;
        // the helper must do exactly the same walk.
        assertTrue(
            "the lookup must walk the ContextWrapper chain",
            appLocale.contains("while (current is ContextWrapper)"),
        )
        assertTrue(
            "the lookup must look for a ComponentActivity (what Hilt requires)",
            appLocale.contains("if (current is ComponentActivity) return current"),
        )
        assertTrue(
            "the lookup must continue through baseContext",
            appLocale.contains("current = current.baseContext"),
        )
    }

    // The non-Compose consumers keep using the plain localized context (unchanged,
    // and safe because they never create a Hilt ViewModel).
    @Test
    fun nonComposeConsumersStillUseThePlainLocalizedContext() {
        assertTrue(
            "notification text must still be localized",
            notifications.contains("AppLocale.localizedContext(context, language)") ||
                notifications.contains("localizedStringsContext(context, languageProvider())"),
        )
        assertTrue(
            "the overlay must still be localized",
            overlay.contains("LocalizedApp(languageProvider())"),
        )
    }

    private fun read(relativePath: String): String {
        val file = File(repoRoot(), "app/src/main/java/uz/faceguard/app/$relativePath")
        assertTrue("missing file: ${file.path}", file.isFile)
        return file.readText()
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

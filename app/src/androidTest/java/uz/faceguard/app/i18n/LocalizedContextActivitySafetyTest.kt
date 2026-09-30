package uz.faceguard.app.i18n

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import uz.faceguard.app.MainActivity
import uz.faceguard.app.R
import uz.faceguard.app.core.i18n.AppLanguage
import uz.faceguard.app.core.i18n.AppLocale

/**
 * Behavioural regression test for the language-selection crash.
 *
 * The crash happened because the Compose tree was given a bare
 * `createConfigurationContext(...)` result as `LocalContext`: that context has no
 * `ComponentActivity` in its `ContextWrapper` chain, so Hilt's
 * `HiltViewModelFactory` threw `IllegalStateException` and the process died as soon
 * as a language was applied.
 *
 * This test drives the real [MainActivity] and asserts, for every supported
 * language, that the context the tree actually receives:
 *  - still resolves to the very same Activity instance (so `hiltViewModel()` keeps
 *    working), and
 *  - resolves strings in the selected language.
 *
 * It fails against the old implementation (whose context contained no Activity)
 * and passes against the Activity-rooted one. It requires a device/emulator.
 */
@RunWith(AndroidJUnit4::class)
class LocalizedContextActivitySafetyTest {

    private fun findActivityFrom(context: Context): ComponentActivity? {
        var current: Context? = context
        while (current is ContextWrapper) {
            if (current is ComponentActivity) return current
            current = current.baseContext
        }
        return null
    }

    @Test
    fun theLocalizedAppContextKeepsTheActivityInTheChainForEveryLanguage() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                AppLanguage.entries.forEach { language ->
                    val localized = AppLocale.localizedAppContext(activity, language)

                    assertNotNull(
                        "the localized context for ${language.languageTag} must reach a ComponentActivity " +
                            "so Hilt can create ViewModels",
                        findActivityFrom(localized),
                    )
                    assertTrue(
                        "the Activity in the chain must be the real one, not a copy",
                        findActivityFrom(localized) === activity,
                    )
                }
            }
        }
    }

    @Test
    fun theLocalizedAppContextResolvesStringsInTheSelectedLanguage() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                AppLanguage.entries.forEach { language ->
                    val localized = AppLocale.localizedAppContext(activity, language)

                    assertEquals(
                        "resources must resolve in ${language.languageTag}",
                        language.languageTag,
                        localized.resources.configuration.locales[0].language,
                    )

                    val title = localized.getString(R.string.welcome_title)
                    assertTrue("welcome_title must not be empty", title.isNotBlank())
                }
            }
        }
    }

    /**
     * Documents the defect this replaces: the bare `createConfigurationContext(...)`
     * context contains no Activity, which is precisely why it cannot be handed to
     * the Compose tree.
     */
    @Test
    fun theBareLocalizedContextHasNoActivityWhichIsWhyItCannotBeProvidedToTheTree() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val bare = AppLocale.localizedContext(activity, AppLanguage.ENGLISH)

                assertNull(
                    "a createConfigurationContext result has no Activity — providing it as " +
                        "LocalContext is what crashed hiltViewModel()",
                    findActivityFrom(bare),
                )
            }
        }
    }

    @Test
    fun theTargetContextIsBackedByTheApplicationNotAnActivity() {
        // Sanity check for the test's own helpers: the instrumentation context is not
        // an Activity, so the chain walk above is actually meaningful.
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        assertNull(findActivityFrom(target))
    }
}

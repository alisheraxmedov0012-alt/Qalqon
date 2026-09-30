package uz.faceguard.app.i18n

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.faceguard.app.core.i18n.AppLanguage
import uz.faceguard.app.core.i18n.AppLocale
import uz.faceguard.app.core.i18n.StartupDestination
import uz.faceguard.app.core.i18n.startupDestination

/**
 * The first-launch / language-selection rules, verified on the JVM.
 *
 * `startupDestination` is the single decision that gates the first-launch picker,
 * so these tests pin the behaviour the whole flow depends on: a fresh install must
 * choose a language before anything else, a chosen language must never re-open the
 * picker, and the choice is independent of authentication.
 */
class LanguageSelectionTest {

    // A. Fresh install / nothing selected -> the picker is required.
    @Test
    fun noLanguageSelectedAlwaysRequiresThePicker() {
        assertEquals(
            StartupDestination.LANGUAGE_SELECTION,
            startupDestination(language = null, hasSession = false),
        )
        // Even a stale session must not skip the picker.
        assertEquals(
            StartupDestination.LANGUAGE_SELECTION,
            startupDestination(language = null, hasSession = true),
        )
    }

    // B/C/D. Once a language is chosen, the normal flow resumes.
    @Test
    fun everySelectedLanguageLeavesThePicker() {
        AppLanguage.entries.forEach { language ->
            assertEquals(
                "signed out after choosing ${language.languageTag}",
                StartupDestination.WELCOME,
                startupDestination(language, hasSession = false),
            )
            assertEquals(
                "signed in after choosing ${language.languageTag}",
                StartupDestination.HOME,
                startupDestination(language, hasSession = true),
            )
        }
    }

    // G. A chosen language prevents the picker from appearing again.
    @Test
    fun aselectedLanguageNeverReturnsToThePicker() {
        AppLanguage.entries.forEach { language ->
            assertTrue(
                startupDestination(language, hasSession = false) != StartupDestination.LANGUAGE_SELECTION,
            )
        }
    }

    // I. Auth is irrelevant to the language gate: signing out keeps the language.
    @Test
    fun logoutAndLoginDoNotChangeTheLanguageDecision() {
        val chosen = AppLanguage.RUSSIAN
        assertEquals(StartupDestination.HOME, startupDestination(chosen, hasSession = true))
        // Log out: still Russian, now at Welcome — never back at the picker.
        assertEquals(StartupDestination.WELCOME, startupDestination(chosen, hasSession = false))
    }

    // J. All three locale identifiers are supported and stable.
    @Test
    fun theThreeSupportedLanguagesAreUzbekEnglishAndRussian() {
        assertEquals(
            listOf("uz", "en", "ru"),
            AppLanguage.entries.map { it.languageTag },
        )
    }

    @Test
    fun tagsRoundTripAndAreCaseInsensitive() {
        AppLanguage.entries.forEach { language ->
            assertEquals(language, AppLanguage.fromTag(language.languageTag))
            assertEquals(language, AppLanguage.fromTag(language.languageTag.uppercase()))
            assertEquals(language, AppLanguage.fromTag(" ${language.languageTag} "))
        }
    }

    @Test
    fun unknownOrMissingTagsAreNotALanguage() {
        assertNull(AppLanguage.fromTag(null))
        assertNull(AppLanguage.fromTag(""))
        assertNull(AppLanguage.fromTag("   "))
        assertNull(AppLanguage.fromTag("de"))
        assertNull(AppLanguage.fromTag("uzbek"))
    }

    @Test
    fun everyLanguageHasItsOwnNativeNameResource() {
        val resources = AppLanguage.entries.map { it.nativeNameRes }
        assertEquals("each language needs a distinct label", resources.size, resources.toSet().size)
        assertTrue(resources.all { it != 0 })
    }

    // H. The applied locale comes only from the stored value, never from the device.
    @Test
    fun theAppliedLocaleIsTheStoredSelectionNotTheDeviceLanguage() {
        val expected = mapOf(
            AppLanguage.UZBEK to "uz",
            AppLanguage.ENGLISH to "en",
            AppLanguage.RUSSIAN to "ru",
        )
        expected.forEach { (language, tag) ->
            assertEquals(
                "the persisted selection, not the system locale, decides the app language",
                tag,
                AppLocale.localeFor(language).language,
            )
        }
    }

    @Test
    fun theStoredTagIsExactlyWhatIsApplied() {
        AppLanguage.entries.forEach { language ->
            // Round-tripping the persisted tag must give the same language back,
            // which is what makes restart/logout keep the choice.
            assertEquals(language, AppLanguage.fromTag(language.languageTag))
            assertEquals(language.languageTag, AppLocale.localeFor(language).language)
        }
    }
}

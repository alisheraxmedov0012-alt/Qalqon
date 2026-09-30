package uz.faceguard.app.i18n

import org.junit.Assert.assertEquals
import org.junit.Test
import uz.faceguard.app.core.i18n.AppLanguage
import uz.faceguard.app.core.i18n.StartupDestination
import uz.faceguard.app.core.i18n.startupDestination

/**
 * Startup routing for the parent-UI lock.
 *
 * The two ideas the gate rests on are kept separate here: a *registered account*
 * (persisted) and an *unlocked UI* (this process only). A registered account must
 * never be enough to reach Home on a later launch.
 */
class StartupRoutingTest {

    // A1. No language -> the picker, before anything else.
    @Test
    fun noLanguageAlwaysShowsThePicker() {
        listOf(false to false, false to true, true to false, true to true).forEach { (account, unlocked) ->
            assertEquals(
                "account=$account unlocked=$unlocked",
                StartupDestination.LANGUAGE_SELECTION,
                startupDestination(language = null, hasRegisteredAccount = account, isUnlocked = unlocked),
            )
        }
    }

    // A2. Language but no account -> onboarding.
    @Test
    fun languageWithoutAnAccountGoesToWelcome() {
        AppLanguage.entries.forEach { language ->
            assertEquals(
                StartupDestination.WELCOME,
                startupDestination(language, hasRegisteredAccount = false, isUnlocked = false),
            )
        }
    }

    // A3. Language + registered account + locked -> the PIN screen.
    @Test
    fun aRegisteredAccountThatIsLockedGoesToThePinScreen() {
        AppLanguage.entries.forEach { language ->
            assertEquals(
                "language=${language.languageTag}",
                StartupDestination.PIN_UNLOCK,
                startupDestination(language, hasRegisteredAccount = true, isUnlocked = false),
            )
        }
    }

    // A4. Language + registered + unlocked -> Home.
    @Test
    fun anUnlockedUiGoesToHome() {
        AppLanguage.entries.forEach { language ->
            assertEquals(
                StartupDestination.HOME,
                startupDestination(language, hasRegisteredAccount = true, isUnlocked = true),
            )
        }
    }

    // E. A "restart" is exactly the locked case: unlocked is runtime-only, so the
    // same inputs as a fresh process must reproduce the PIN screen.
    @Test
    fun aRestartLocksTheUiAgain() {
        // Before the restart the user was in Home, i.e. unlocked.
        assertEquals(
            StartupDestination.HOME,
            startupDestination(AppLanguage.ENGLISH, hasRegisteredAccount = true, isUnlocked = true),
        )
        // After process death the unlock flag is gone, so the same persisted state
        // must land on the PIN screen.
        assertEquals(
            StartupDestination.PIN_UNLOCK,
            startupDestination(AppLanguage.ENGLISH, hasRegisteredAccount = true, isUnlocked = false),
        )
    }

    @Test
    fun theUnlockFlagIsTheOnlyDifferenceBetweenHomeAndThePinScreen() {
        AppLanguage.entries.forEach { language ->
            val locked = startupDestination(language, hasRegisteredAccount = true, isUnlocked = false)
            val unlocked = startupDestination(language, hasRegisteredAccount = true, isUnlocked = true)

            assertEquals(StartupDestination.PIN_UNLOCK, locked)
            assertEquals(StartupDestination.HOME, unlocked)
        }
    }
}

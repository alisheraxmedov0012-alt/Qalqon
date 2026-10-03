package uz.faceguard.app.help

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.faceguard.app.R
import uz.faceguard.app.navigation.QalqonTopLevelDestination
import uz.faceguard.app.navigation.Routes
import uz.faceguard.app.navigation.isProtectedRoute
import uz.faceguard.app.navigation.lockRedirectFor

/**
 * Help as a first-class bottom-navigation destination, and the Help Center /
 * Assistant refinement around it (UI/UX redesign, navigation shell — no new phase).
 *
 * The composables cannot be inflated on the JVM, so this pins the *wiring* the way the
 * other navigation tests do — the tab model, the route graph and the screen's source
 * contract — plus the localization parity of the new Help UI strings. The assistant's
 * routing behaviour itself is covered by [QalqonAssistantRoutingTest].
 */
class HelpCenterNavigationTest {

    private val locales = listOf("values", "values-en", "values-ru")

    // -------------------------------------------------- bottom navigation (Help item)

    @Test
    fun helpIsAFirstClassBottomNavigationItem() {
        assertEquals(
            listOf("HOME", "CHILDREN", "ACTIVITY", "HELP", "SETTINGS"),
            QalqonTopLevelDestination.entries.map { it.name },
        )
        val help = QalqonTopLevelDestination.HELP
        assertEquals(Routes.HELP, help.route)
        assertEquals(R.string.nav_help, help.labelRes)
        assertNotNull("Help must have an icon", help.icon)
    }

    @Test
    fun selectingTheHelpTabResolvesToTheHelpCenter() {
        assertEquals(QalqonTopLevelDestination.HELP, QalqonTopLevelDestination.forRoute(Routes.HELP))
        // The Assistant is a pushed screen, so it must not light up the Help tab.
        assertNull(QalqonTopLevelDestination.forRoute(Routes.HELP_ASSISTANT))
    }

    @Test
    fun helpReusesTheExistingRouteAndDoesNotDuplicateIt() {
        // Exactly one Help route, and it is the pre-existing one.
        assertEquals("help", Routes.HELP)
        val declared = Regex("""const val HELP(_ASSISTANT)? = "([^"]+)"""")
            .findAll(read("navigation/NavGraph.kt"))
            .map { it.groupValues[2] }
            .toList()
        assertEquals(listOf("help", "help_assistant"), declared)
    }

    @Test
    fun theHelpTabStaysBehindTheExistingPinGate() {
        assertTrue(isProtectedRoute(Routes.HELP))
        assertEquals(Routes.PIN_UNLOCK, lockRedirectFor(Routes.HELP, isUnlocked = false))
        assertNull(lockRedirectFor(Routes.HELP, isUnlocked = true))
    }

    // ----------------------------------------------------- Help Center (host + content)

    @Test
    fun theHelpCenterIsHostedAtTheExistingHelpRoute() {
        val navGraph = read("navigation/NavGraph.kt")
        assertTrue(navGraph.contains("composable(Routes.HELP)"))
        assertTrue(navGraph.contains("HelpScreen("))
        assertTrue(navGraph.contains("onOpenAssistant = { navController.navigate(Routes.HELP_ASSISTANT) }"))
        assertTrue(navGraph.contains("composable(Routes.HELP_ASSISTANT)"))
        assertTrue(navGraph.contains("HelpAssistantScreen("))
    }

    @Test
    fun helpIsATopLevelTabSoItRendersNoBackArrow() {
        // Same contract the other tabs (e.g. Activity) are held to.
        val screen = read("feature/help/HelpScreen.kt")
        assertFalse("a top-level tab must not render a back arrow", screen.contains("ArrowBack"))
        assertFalse("a top-level tab must not use the back label", screen.contains("request_back"))
    }

    @Test
    fun theHelpCenterPresentsSearchAndAProminentAssistantEntry() {
        val screen = read("feature/help/HelpScreen.kt")
        assertTrue(screen.contains("R.string.help_intro"))
        assertTrue(screen.contains("R.string.help_search_hint"))
        assertTrue(screen.contains("R.string.help_search_clear"))
        assertTrue(screen.contains("R.string.help_assistant_title"))
        assertTrue(screen.contains("R.string.help_assistant_subtitle"))
        assertTrue(screen.contains("onOpenAssistant"))
        // The Assistant is not presented as generative AI.
        val assistant = read("feature/help/HelpScreen.kt") + read("feature/help/HelpAssistantScreen.kt")
        listOf("sun'iy intellekt", "AI yordamchi", "GPT", "neural", "generative").forEach { claim ->
            assertFalse("must not market the assistant as $claim", assistant.contains(claim))
        }
    }

    @Test
    fun theHelpCenterStillExposesEveryKnowledgeCategory() {
        // The requested topics are all backed by the existing knowledge base (no
        // duplicate articles were introduced).
        val categories = uz.faceguard.app.feature.help.HelpCategory.entries.map { it.name }
        listOf(
            "GETTING_STARTED", "PROTECTION", "CHILDREN", "PROTECTED_APPS", "SCREEN_TIME",
            "SCHEDULE", "EYE_SAFETY", "PIN_BIOMETRIC", "NOTIFICATIONS_REQUESTS", "PRIVACY",
            "TROUBLESHOOTING",
        ).forEach { required -> assertTrue("missing help category $required", required in categories) }
    }

    // ------------------------------------------------------------- navigation regression

    @Test
    fun allFiveTabsRoundTripAndRemainProtected() {
        listOf(
            "HOME" to Routes.HOME,
            "CHILDREN" to Routes.CHILD_PROFILES,
            "ACTIVITY" to Routes.ACTIVITY_LOG,
            "HELP" to Routes.HELP,
            "SETTINGS" to Routes.SETTINGS,
        ).forEach { (name, route) ->
            val destination = QalqonTopLevelDestination.valueOf(name)
            assertEquals("$name route changed", route, destination.route)
            assertEquals("$name does not round-trip", destination, QalqonTopLevelDestination.forRoute(route))
            assertTrue("$name must stay protected", isProtectedRoute(route))
        }
    }

    // --------------------------------------------------------------------- localization

    @Test
    fun theNewHelpUiStringsExistInEveryLocale() {
        val keys = listOf(
            "nav_help", "help_intro", "help_search_hint", "help_search_clear",
            "help_search_no_results", "help_assistant_subtitle",
        )
        locales.forEach { locale ->
            val declared = declaredKeys(locale)
            keys.forEach { key -> assertTrue("$locale is missing $key", key in declared) }
        }
    }

    @Test
    fun theHelpTabLabelIsTranslatedPerLanguage() {
        assertEquals("Yordam", values("values")["nav_help"])
        assertEquals("Help", values("values-en")["nav_help"])
        assertEquals("Помощь", values("values-ru")["nav_help"])
    }

    // ------------------------------------------------------------------------ helpers

    private fun declaredKeys(locale: String): Set<String> =
        Regex("""name="([^"]+)"""")
            .findAll(stringsFile(locale).readText())
            .map { it.groupValues[1] }
            .toSet()

    private fun values(locale: String): Map<String, String> =
        Regex("""<string name="([^"]+)">([^<]*)</string>""")
            .findAll(stringsFile(locale).readText())
            .associate { it.groupValues[1] to it.groupValues[2] }

    private fun stringsFile(locale: String): File =
        File(repoRoot(), "app/src/main/res/$locale/strings.xml")

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

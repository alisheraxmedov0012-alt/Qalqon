package uz.faceguard.app.help

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.faceguard.app.feature.help.HelpCategory
import uz.faceguard.app.feature.help.QalqonKnowledgeBase

/**
 * The Help Center's content model.
 *
 * Pure JVM: the knowledge base is resource ids only, so its shape (the required
 * categories, real content for each, the FAQ/troubleshooting coverage) is pinned here,
 * and the localization parity of the `help_*` keys is checked against the three
 * resource files.
 */
class HelpCenterTest {

    @Test
    fun theHelpCenterOffersTheRequiredCategories() {
        assertEquals(
            listOf(
                "GETTING_STARTED", "PROTECTION", "CHILDREN", "PROTECTED_APPS", "SCREEN_TIME",
                "SCHEDULE", "EYE_SAFETY", "PIN_BIOMETRIC", "NOTIFICATIONS_REQUESTS", "PRIVACY",
                "TROUBLESHOOTING",
            ),
            HelpCategory.entries.map { it.name },
        )
        HelpCategory.entries.forEach { category ->
            assertNotEquals("${category.name} has no label", 0, category.labelRes)
        }
    }

    @Test
    fun everyNonTroubleshootingCategoryHasRealContent() {
        HelpCategory.entries
            .filter { it != HelpCategory.TROUBLESHOOTING }
            .forEach { category ->
                val articles = QalqonKnowledgeBase.articles.filter { it.categoryRes == category.labelRes }
                assertTrue("${category.name} has no article", articles.isNotEmpty())
            }
    }

    @Test
    fun articlesFaqAndTroubleshootingArePopulatedAndUniquelyIdentified() {
        assertTrue("no articles", QalqonKnowledgeBase.articles.size >= 10)
        assertTrue("no FAQ", QalqonKnowledgeBase.faq.size >= 10)
        assertTrue("no troubleshooting", QalqonKnowledgeBase.troubleshooting.size >= 10)

        val ids = QalqonKnowledgeBase.articles.map { it.id } +
            QalqonKnowledgeBase.faq.map { it.id } +
            QalqonKnowledgeBase.troubleshooting.map { it.id }
        assertEquals("entry ids must be unique", ids.size, ids.toSet().size)
    }

    @Test
    fun everyEntryHasResolvableResourceIds() {
        QalqonKnowledgeBase.articles.forEach {
            assertNotEquals("${it.id} title", 0, it.titleRes)
            assertNotEquals("${it.id} body", 0, it.bodyRes)
        }
        QalqonKnowledgeBase.faq.forEach {
            assertNotEquals("${it.id} question", 0, it.questionRes)
            assertNotEquals("${it.id} answer", 0, it.answerRes)
        }
        QalqonKnowledgeBase.troubleshooting.forEach {
            assertNotEquals("${it.id} question", 0, it.questionRes)
            assertNotEquals("${it.id} body", 0, it.bodyRes)
        }
    }

    @Test
    fun theTroubleshootingSectionCoversTheRequiredSymptoms() {
        val ids = QalqonKnowledgeBase.troubleshooting.map { it.id }.toSet()
        listOf(
            "ts_app_missing",
            "ts_child_face",
            "ts_recognition",
            "ts_protection_off",
            "ts_app_not_blocked",
            "ts_no_notification",
            "ts_biometric",
            "ts_pin",
            "ts_pin_again",
            "ts_screen_time",
        ).forEach { required -> assertTrue("missing troubleshooting entry $required", required in ids) }
    }

    @Test
    fun theRequiredFaqTopicsArePresent() {
        val ids = QalqonKnowledgeBase.faq.map { it.id }.toSet()
        listOf(
            "faq_offline",
            "faq_biometric_first",
            "faq_pin",
            "faq_face",
            "faq_add_child",
            "faq_protect_app",
            "faq_protection_on",
            "faq_protection_off",
            "faq_data_storage",
            "faq_refresh_apps",
        ).forEach { required -> assertTrue("missing FAQ $required", required in ids) }
    }

    @Test
    fun everyHelpStringExistsInAllThreeLocalesWithParity() {
        val locales = listOf("values", "values-en", "values-ru")
        val keys = locales.associateWith { locale -> declaredKeys(locale) }
        val reference = keys.getValue("values").filter { it.startsWith("help_") }.toSet()
        assertTrue("no help_* keys found", reference.size >= 100)
        locales.forEach { locale ->
            val declared = keys.getValue(locale).filter { it.startsWith("help_") }.toSet()
            assertEquals(
                "$locale help_* keys drifted (missing ${(reference - declared).sorted()})",
                reference,
                declared,
            )
        }
    }

    @Test
    fun everyDeclaredHelpStringIsActuallyUsed() {
        // The copy and the code cannot drift: each help_* key must be referenced.
        val referenced = File(repoRoot(), "app/src/main/java").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                Regex("""R\.string\.(help_[a-z0-9_]+)""")
                    .findAll(file.readText())
                    .map { it.groupValues[1] }
                    .asSequence()
            }
            .toSet()
        val declared = declaredKeys("values").filter { it.startsWith("help_") }.toSet()
        val dead = declared.filter { it !in referenced }.sorted()
        assertTrue("declared but unused help strings: $dead", dead.isEmpty())
    }

    // ------------------------------------------------------------------ helpers

    private fun declaredKeys(locale: String): Set<String> =
        Regex("""name="([a-z0-9_]+)"""")
            .findAll(File(repoRoot(), "app/src/main/res/$locale/strings.xml").readText())
            .map { it.groupValues[1] }
            .toSet()

    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        while (dir.parentFile != null) {
            if (File(dir, "app/src/main/res/values/strings.xml").isFile) return dir
            dir = dir.parentFile
        }
        error("could not locate the repository root from ${System.getProperty("user.dir")}")
    }
}

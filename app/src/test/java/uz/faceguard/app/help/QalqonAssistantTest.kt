package uz.faceguard.app.help

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.faceguard.app.feature.help.AssistantReply
import uz.faceguard.app.feature.help.HelpArticle
import uz.faceguard.app.feature.help.HelpContent
import uz.faceguard.app.feature.help.HelpEntryKind
import uz.faceguard.app.feature.help.HelpFaq
import uz.faceguard.app.feature.help.HelpTextProvider
import uz.faceguard.app.feature.help.HelpTroubleshooting
import uz.faceguard.app.feature.help.LocalQalqonKnowledgeAssistant
import uz.faceguard.app.feature.help.QalqonAssistantContext

/**
 * The Qalqon Assistant: domain binding, retrieval-only answers and the security
 * boundary.
 *
 * Honest capability note: the repository has no on-device language model, so this is
 * the deterministic local retrieval implementation — these tests pin its actual
 * behaviour (retrieval, refusal, "not found") and must never be read as testing a
 * generative model.
 *
 * The assistant runs against a small deterministic fixture so the logic is verified on
 * the JVM without Android resources.
 */
class QalqonAssistantTest {

    // ----------------------------------------------------------------- fixture

    private val protectionArticleRes = 101
    private val protectionBodyRes = 102
    private val pinFaqRes = 201
    private val pinAnswerRes = 202
    private val appListTsRes = 301
    private val appListBodyRes = 302

    private val text = mapOf(
        protectionArticleRes to "Turning protection on",
        protectionBodyRes to "Open Settings, then Protection, and turn on protection enabled.",
        pinFaqRes to "How do I sign in with the pin?",
        pinAnswerRes to "Press use pin on the unlock screen and enter your pin.",
        appListTsRes to "An app is missing from the list",
        appListBodyRes to "Press refresh apps; a hidden app is filtered out.",
    )

    private val provider = HelpTextProvider { res -> text[res] ?: "" }

    private val content = object : HelpContent {
        override val articles = listOf(
            HelpArticle("protection", 1, protectionArticleRes, protectionBodyRes),
        )
        override val faq = listOf(
            HelpFaq("pin", pinFaqRes, pinAnswerRes),
        )
        override val troubleshooting = listOf(
            HelpTroubleshooting("app_missing", appListTsRes, appListBodyRes),
        )
        override val domainTerms = listOf("qalqon", "protection", "pin", "app", "himoya")
    }

    private val assistant = LocalQalqonKnowledgeAssistant(provider, content)

    private fun ask(question: String, context: QalqonAssistantContext? = null): AssistantReply =
        runBlocking { assistant.ask(question, context) }

    // ------------------------------------------------------- in-domain answers

    @Test
    fun aQalqonProtectionQuestionIsAnsweredFromTheKnowledgeBase() {
        val reply = ask("How do I turn protection on?")
        assertTrue("expected a knowledge answer, was $reply", reply is AssistantReply.Knowledge)
        reply as AssistantReply.Knowledge
        assertEquals(protectionArticleRes, reply.titleRes)
        assertEquals(protectionBodyRes, reply.bodyRes)
        assertEquals(HelpEntryKind.ARTICLE, reply.kind)
    }

    @Test
    fun aQalqonPinQuestionIsAnsweredFromTheFaq() {
        val reply = ask("How do I sign in with the pin?")
        assertTrue(reply is AssistantReply.Knowledge)
        assertEquals(HelpEntryKind.FAQ, (reply as AssistantReply.Knowledge).kind)
    }

    @Test
    fun aQalqonTroubleshootingQuestionIsAnsweredFromTroubleshooting() {
        val reply = ask("An app is missing from the list")
        assertTrue(reply is AssistantReply.Knowledge)
        assertEquals(HelpEntryKind.TROUBLESHOOTING, (reply as AssistantReply.Knowledge).kind)
    }

    @Test
    fun theAnswerIsVerbatimKnowledgeBaseTextNeverComposed() {
        // Both resource ids come straight from the fixture: the assistant cannot
        // produce an answer that is not in the knowledge base.
        val reply = ask("protection enabled settings") as AssistantReply.Knowledge
        assertTrue(reply.titleRes == protectionArticleRes || reply.titleRes == protectionBodyRes ||
            reply.titleRes in text.keys)
        assertTrue(reply.bodyRes in text.keys)
    }

    // ---------------------------------------------------------- domain refusal

    @Test
    fun unrelatedQuestionsAreRefused() {
        listOf(
            "What is the weather in Tashkent today?",
            "Teach me Python programming",
            "Which phone is the best to buy?",
            "What is the price of bitcoin?",
            "Solve this math equation for me",
            "Translate this sentence for me",
        ).forEach { question ->
            assertEquals(
                "'$question' must be refused",
                AssistantReply.OutOfDomain,
                ask(question),
            )
        }
    }

    @Test
    fun anEmptyQuestionIsNotAnswered() {
        assertEquals(AssistantReply.NotFound, ask("   "))
    }

    // ------------------------------------------------------------- not found

    @Test
    fun aQalqonQuestionTheGuideDoesNotCoverIsReportedHonestly() {
        // In domain (mentions a QALQON concept) but absent from the fixture: the
        // assistant must not invent an answer.
        val reply = ask("qalqon quantum teleport")
        assertEquals(AssistantReply.NotFound, reply)
    }

    // ------------------------------------------------------- context-awareness

    @Test
    fun theProtectionContextAnswerIsUsedOnlyWhenProtectionIsOff() {
        val offContext = QalqonAssistantContext(
            protectionEnabled = false,
            protectedAppsCount = 3,
            hasChildren = true,
            notificationsEnabled = true,
        )
        val reply = ask("Why is qalqon protection off?", offContext)
        assertTrue("expected a contextual answer, was $reply", reply is AssistantReply.Contextual)

        val onContext = offContext.copy(protectionEnabled = true)
        val whenOn = ask("Why is qalqon protection off?", onContext)
        assertFalse("must not claim protection is off when it is on", whenOn is AssistantReply.Contextual)
    }

    @Test
    fun theNoAppsContextAnswerIsUsedOnlyWhenNothingIsProtected() {
        val none = QalqonAssistantContext(false, 0, true, true)
        assertTrue(ask("which app can I protect", none) is AssistantReply.Contextual)

        val some = none.copy(protectedAppsCount = 5)
        assertFalse(ask("which app can I protect", some) is AssistantReply.Contextual)
    }

    @Test
    fun theAssistantNeverAnswersOutOfDomainEvenWithContext() {
        val context = QalqonAssistantContext(false, 0, false, false)
        assertEquals(
            AssistantReply.OutOfDomain,
            ask("What is the weather tomorrow?", context),
        )
    }

    // ------------------------------------------------------------- localization

    @Test
    fun theRefusalAndTheNoticesExistInAllThreeLocalesAndDiffer() {
        val locales = listOf("values", "values-en", "values-ru")
        val values = locales.associateWith { locale -> stringValues(locale) }
        val keys = listOf("help_assistant_ood", "help_assistant_not_found", "help_assistant_unavailable")
        locales.forEach { locale ->
            keys.forEach { key ->
                assertTrue("$locale is missing $key", values.getValue(locale)[key]?.isNotBlank() == true)
            }
        }
        // The refusal is genuinely translated, not an English fallback.
        val uz = values.getValue("values")["help_assistant_ood"]
        val en = values.getValue("values-en")["help_assistant_ood"]
        val ru = values.getValue("values-ru")["help_assistant_ood"]
        assertNotEquals(uz, en)
        assertNotEquals(en, ru)
        assertNotEquals(uz, ru)
    }

    @Test
    fun theSuggestedQuestionsAreLocalizedStringsInEveryLocale() {
        val locales = listOf("values", "values-en", "values-ru")
        val keys = listOf(
            "help_assistant_suggest_protection",
            "help_assistant_suggest_add_child",
            "help_assistant_suggest_apps",
            "help_assistant_suggest_pin",
            "help_assistant_suggest_face",
        )
        locales.forEach { locale ->
            val declared = stringValues(locale).keys
            keys.forEach { key -> assertTrue("$locale is missing $key", key in declared) }
        }
    }

    // -------------------------------------------------------- security boundary

    @Test
    fun theHelpAndAssistantCodeCannotAccessCredentialsOrBiometrics() {
        val forbidden = listOf(
            "PinHasher",
            "verifyPin",
            "AccountRepository",
            "SecureCrypto",
            "KeystoreBiometricTemplateCipher",
            "BiometricTemplateCipher",
            "FaceEmbeddingCodec",
            "AndroidBiometricPrompt",
            "BiometricPrompt",
        )
        helpSources().forEach { (name, source) ->
            forbidden.forEach { symbol ->
                assertFalse("$name must not touch $symbol", source.contains(symbol))
            }
        }
    }

    @Test
    fun theAssistantCannotBypassTheAppLock() {
        helpSources().forEach { (name, source) ->
            assertFalse("$name must not reference the lock state", source.contains("AppLockState"))
            assertFalse("$name must not unlock the UI", source.contains("onAuthenticated"))
        }
    }

    @Test
    fun theHelpAndAssistantCodeCannotModifyProtection() {
        val mutators = listOf(
            "ProtectionEngine",
            "ProtectionForegroundService",
            "ProtectionAccessibilityService",
            "ProtectionCameraSession",
            "serviceLauncher",
            "stopService",
            "startForegroundService",
            "engine.start(",
            "engine.stop(",
            "runtime.stop(",
            "runtime.start(",
        )
        helpSources().forEach { (name, source) ->
            mutators.forEach { symbol ->
                assertFalse("$name must not operate protection ($symbol)", source.contains(symbol))
            }
        }
    }

    @Test
    fun theAssistantIntroducedNoNetworkAccess() {
        val manifest = File(repoRoot(), "app/src/main/AndroidManifest.xml").readText()
        manifest.lines()
            .filter { it.contains("android.permission.INTERNET") }
            .forEach { line ->
                assertTrue(
                    "INTERNET must only ever appear as a tools:node=\"remove\" removal",
                    line.contains("tools:node=\"remove\""),
                )
            }
        // No network/remote-AI client anywhere in the Help feature.
        val network = listOf("http://", "https://", "okhttp", "retrofit", "openai", "gemini", "anthropic", "apiKey", "api_key")
        helpSources().forEach { (name, source) ->
            network.forEach { symbol ->
                assertFalse("$name must not reference $symbol", source.contains(symbol))
            }
        }
    }

    @Test
    fun theHelpScreensStayInsideTheDesignSystem() {
        listOf(
            "feature/help/HelpScreen.kt",
            "feature/help/HelpAssistantScreen.kt",
        ).forEach { path ->
            val source = read(path)
            assertFalse("$path defines a raw color", source.contains("Color(0x"))
            assertFalse("$path uses a raw dp literal", Regex("""\b\d+\.dp\b""").containsMatchIn(source))
            assertTrue("$path must use the spacing tokens", source.contains("QalqonDimens."))
        }
    }

    @Test
    fun theAssistantScreenExposesLocalizedAccessibilityForItsControls() {
        val source = read("feature/help/HelpAssistantScreen.kt")
        assertTrue(source.contains("R.string.request_back"))
        assertTrue(source.contains("R.string.help_assistant_send"))
        assertTrue(source.contains("R.string.help_assistant_input_hint"))
    }

    // ------------------------------------------------------------------ helpers

    private fun helpSources(): Map<String, String> =
        File(repoRoot(), "app/src/main/java/uz/faceguard/app/feature/help")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .associate { it.name to it.readText() }

    private fun stringValues(locale: String): Map<String, String> =
        Regex("""<string name="([a-z0-9_]+)">([^<]*)</string>""")
            .findAll(File(repoRoot(), "app/src/main/res/$locale/strings.xml").readText())
            .associate { it.groupValues[1] to it.groupValues[2] }

    private fun read(relativePath: String): String =
        File(repoRoot(), "app/src/main/java/uz/faceguard/app/$relativePath").readText()

    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        while (dir.parentFile != null) {
            if (File(dir, "app/src/main/res/values/strings.xml").isFile) return dir
            dir = dir.parentFile
        }
        error("could not locate the repository root from ${System.getProperty("user.dir")}")
    }
}

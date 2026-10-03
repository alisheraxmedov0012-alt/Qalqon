package uz.faceguard.app.help.ai

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.faceguard.app.R
import uz.faceguard.app.feature.help.AssistantReply
import uz.faceguard.app.feature.help.HelpTextProvider
import uz.faceguard.app.feature.help.LocalQalqonKnowledgeAssistant
import uz.faceguard.app.feature.help.QalqonAssistantContext
import uz.faceguard.app.feature.help.QalqonKnowledgeBase
import uz.faceguard.app.feature.help.ai.GroundedAssistantContext
import uz.faceguard.app.feature.help.ai.GroundedGenerator
import uz.faceguard.app.feature.help.ai.GroundedKnowledgeContext
import uz.faceguard.app.feature.help.ai.GroundedQalqonAssistant
import uz.faceguard.app.feature.help.ai.GenerationFailureReason
import uz.faceguard.app.feature.help.ai.GenerationResult

/**
 * QALQON Phase A2 — integration of the optional grounded layer into the assistant.
 *
 * The deterministic assistant is the real one, over the real knowledge base and real localized
 * strings; the generator is a fake. These tests prove the deterministic answer is always the
 * total fallback and that generation runs only for a knowledge-backed answer.
 */
class GroundedQalqonAssistantTest {

    private class CapturingGenerator(private val result: GenerationResult) : GroundedGenerator {
        var calls = 0
        var lastIntent: String? = null
        var lastContext: GroundedAssistantContext? = null
        var lastKnowledge: GroundedKnowledgeContext? = null
        override suspend fun generate(
            question: String,
            intentLabel: String,
            knowledge: GroundedKnowledgeContext,
            context: GroundedAssistantContext,
        ): GenerationResult {
            calls++
            lastIntent = intentLabel
            lastKnowledge = knowledge
            lastContext = context
            return result
        }
    }

    private fun assistant(locale: String, generator: GroundedGenerator): GroundedQalqonAssistant {
        val provider = provider(locale)
        return GroundedQalqonAssistant(
            deterministic = LocalQalqonKnowledgeAssistant(provider),
            generator = generator,
            text = provider,
            knowledge = QalqonKnowledgeBase,
        )
    }

    private fun ask(locale: String, question: String, generator: GroundedGenerator, context: QalqonAssistantContext? = null) =
        runBlocking { assistant(locale, generator).ask(question, context) }

    private val apps = "Qaysi ilovalarni himoyalash mumkin?"
    private val fullContext = QalqonAssistantContext(
        protectionEnabled = false,
        protectedAppsCount = 0,
        hasChildren = false,
        notificationsEnabled = false,
    )

    // ------------------------------------------------------------------- A: generated path

    @Test
    fun anAvailableModelWithValidOutputYieldsAGeneratedAnswer() {
        val generator = CapturingGenerator(GenerationResult.Success("Himoyalangan ilovalar bo'limida kerakli ilovani belgilang."))
        val reply = ask("values", apps, generator, fullContext)
        assertTrue("expected a Generated reply, was $reply", reply is AssistantReply.Generated)
        assertEquals("Himoyalangan ilovalar bo'limida kerakli ilovani belgilang.", (reply as AssistantReply.Generated).text)
        assertEquals("PROTECTED_APPS", generator.lastIntent)
    }

    // --------------------------------------------- B..H: every failure -> deterministic

    @Test
    fun everyGenerationFailureFallsBackToTheDeterministicKnowledgeAnswer() {
        val deterministicOnly = runBlocking {
            LocalQalqonKnowledgeAssistant(provider("values")).ask(apps, fullContext)
        }
        assertTrue(deterministicOnly is AssistantReply.Knowledge)
        GenerationFailureReason.entries.forEach { reason ->
            val reply = ask("values", apps, CapturingGenerator(GenerationResult.Failure(reason)), fullContext)
            assertEquals("fallback for $reason", deterministicOnly, reply)
            assertFalse(reply is AssistantReply.Generated)
        }
    }

    // ---------------------------------- generation never runs for non-knowledge answers

    @Test
    fun outOfDomainAndNotFoundAreReturnedWithoutCallingTheModel() {
        val ood = CapturingGenerator(GenerationResult.Success("should not happen"))
        val oodReply = ask("values", "Bugun ob-havo qanday?", ood, fullContext)
        assertEquals(AssistantReply.OutOfDomain, oodReply)
        assertEquals("the model must not be called for an out-of-domain question", 0, ood.calls)

        val notFound = CapturingGenerator(GenerationResult.Success("should not happen"))
        val nfReply = ask("values", "How do I cancel my account?", notFound, fullContext)
        assertTrue("expected NotFound, was $nfReply", nfReply is AssistantReply.NotFound)
        assertEquals("the model must not be called when the guide does not cover it", 0, notFound.calls)
    }

    // --------------------------------------------------- context flags: only relevant ones

    @Test
    fun onlyTheContextFlagRelevantToTheIntentIsDisclosed() {
        val generator = CapturingGenerator(GenerationResult.Failure(GenerationFailureReason.UNAVAILABLE))
        ask("values", apps, generator, fullContext)
        val context = generator.lastContext!!
        assertEquals(0, context.protectedAppsCount)
        assertEquals(null, context.protectionEnabled)
        assertEquals(null, context.hasChildren)
        assertEquals(null, context.notificationsEnabled)
    }

    @Test
    fun theGroundedKnowledgeIsTheSelectedEntryOnly() {
        val generator = CapturingGenerator(GenerationResult.Failure(GenerationFailureReason.UNAVAILABLE))
        ask("values", apps, generator, fullContext)
        assertEquals("apps_protect", generator.lastKnowledge!!.entryId)
        assertTrue(generator.lastKnowledge!!.content.isNotBlank())
    }

    // ------------------------------------------------------------------ J: localization

    @Test
    fun englishQuestionsKeepAWorkingDeterministicFallback() {
        val reply = ask("values-en", "Which apps can I protect?", CapturingGenerator(GenerationResult.Failure(GenerationFailureReason.UNAVAILABLE)))
        assertTrue("expected a knowledge answer, was $reply", reply is AssistantReply.Knowledge)
        assertEquals(res("help_article_protect_app_title"), (reply as AssistantReply.Knowledge).titleRes)
    }

    // ------------------------------------------------------------------------- helpers

    private fun res(name: String): Int = R.string::class.java.getField(name).getInt(null)

    private fun provider(locale: String): HelpTextProvider {
        val xml = File(repoRoot(), "app/src/main/res/$locale/strings.xml").readText()
        val byName = Regex("""<string name="([a-z0-9_]+)">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml)
            .associate { it.groupValues[1] to decode(it.groupValues[2]) }
        val idToText = HashMap<Int, String>()
        byName.forEach { (name, value) ->
            val field = runCatching { R.string::class.java.getField(name) }.getOrNull() ?: return@forEach
            idToText[field.getInt(null)] = value
        }
        return HelpTextProvider { id -> idToText[id] ?: "" }
    }

    private fun decode(raw: String): String = raw
        .replace("\\'", "'")
        .replace("&gt;", ">")
        .replace("&lt;", "<")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&amp;", "&")

    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        while (dir.parentFile != null) {
            if (File(dir, "app/src/main/res/values/strings.xml").isFile) return dir
            dir = dir.parentFile
        }
        error("could not locate the repository root from ${System.getProperty("user.dir")}")
    }
}

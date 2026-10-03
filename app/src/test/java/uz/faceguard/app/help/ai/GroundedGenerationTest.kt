package uz.faceguard.app.help.ai

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.faceguard.app.feature.help.ai.AssistantAnswerValidator
import uz.faceguard.app.feature.help.ai.GeminiNanoCapability
import uz.faceguard.app.feature.help.ai.GeminiNanoGateway
import uz.faceguard.app.feature.help.ai.GeminiNanoGenerator
import uz.faceguard.app.feature.help.ai.GeminiNanoStatus
import uz.faceguard.app.feature.help.ai.GroundedAssistantContext
import uz.faceguard.app.feature.help.ai.GroundedGeminiNanoGenerator
import uz.faceguard.app.feature.help.ai.GroundedKnowledgeContext
import uz.faceguard.app.feature.help.ai.GenerationFailureReason
import uz.faceguard.app.feature.help.ai.GenerationResult
import uz.faceguard.app.feature.help.ai.GroundedPromptBuilder

/**
 * QALQON Phase A2 — grounded generation: availability gating, prompt construction,
 * deterministic output validation and privacy of the request payload.
 *
 * Everything runs on the JVM with fakes; no AICore and no device are involved.
 */
class GroundedGenerationTest {

    private val knowledge = GroundedKnowledgeContext(
        entryId = "apps_protect",
        title = "Ilovani himoyalash",
        content = "Sozlamalar > Himoya > Himoyalangan ilovalar bo'limini ochib, kerakli ilovani belgilang.",
    )

    private val validAnswer = "Himoyalangan ilovalar bo'limida kerakli ilovani belgilashingiz mumkin."

    private class FakeStatusGateway(private val result: Result<GeminiNanoStatus>) : GeminiNanoGateway {
        override suspend fun status(): GeminiNanoStatus = result.getOrThrow()
        override suspend fun warmup() = Unit
        override fun close() = Unit
    }

    private class FakeGenerator(private val produce: (String) -> String) : GeminiNanoGenerator {
        var lastPrompt: String? = null
        override suspend fun generate(prompt: String): String {
            lastPrompt = prompt
            return produce(prompt)
        }
    }

    private fun generator(
        status: GeminiNanoStatus,
        produce: (String) -> String = { validAnswer },
    ): GroundedGeminiNanoGenerator {
        val capability = GeminiNanoCapability(FakeStatusGateway(Result.success(status)))
        return GroundedGeminiNanoGenerator(capability, FakeGenerator(produce))
    }

    private fun run(
        status: GeminiNanoStatus,
        knowledgeContext: GroundedKnowledgeContext = knowledge,
        context: GroundedAssistantContext = GroundedAssistantContext(),
        produce: (String) -> String = { validAnswer },
    ): GenerationResult = runBlocking {
        generator(status, produce).generate("Qaysi ilovalarni himoyalash mumkin?", "PROTECTED_APPS", knowledgeContext, context)
    }

    private fun reasonOf(result: GenerationResult): GenerationFailureReason =
        (result as GenerationResult.Failure).reason

    // ---------------------------------------------------- A: available + valid generation

    @Test
    fun availableWithValidGroundedOutputSucceeds() {
        assertEquals(GenerationResult.Success(validAnswer), run(GeminiNanoStatus.AVAILABLE))
    }

    // --------------------------------------------- B/C: available + empty/invalid output

    @Test
    fun availableWithEmptyOutputFallsBackAsEmptyOutput() {
        assertEquals(GenerationFailureReason.EMPTY_OUTPUT, reasonOf(run(GeminiNanoStatus.AVAILABLE) { "" }))
        assertEquals(GenerationFailureReason.EMPTY_OUTPUT, reasonOf(run(GeminiNanoStatus.AVAILABLE) { "   \n " }))
    }

    @Test
    fun availableWithSdkGarbageOrRefusalFallsBackAsInvalid() {
        listOf(
            "As an AI language model I cannot answer.",
            "INSUFFICIENT_QALQON_KNOWLEDGE",
            "java.lang.IllegalStateException",
        ).forEach { bad ->
            assertEquals(
                "for '$bad'",
                GenerationFailureReason.INVALID_OUTPUT,
                reasonOf(run(GeminiNanoStatus.AVAILABLE) { bad }),
            )
        }
    }

    @Test
    fun availableWithUnrelatedOutputFallsBackAsInvalid() {
        assertEquals(
            GenerationFailureReason.INVALID_OUTPUT,
            reasonOf(run(GeminiNanoStatus.AVAILABLE) { "Bugungi ob-havo juda yaxshi va quyoshli." }),
        )
    }

    @Test
    fun availableWithAnInventedOrContradictoryClaimFallsBackAsInvalid() {
        listOf(
            "Qalqon yuz malumotlarini serverga yuboradi.",
            "Qalqon ilovalarni avtomatik bloklaydi.",
            "This is powered by the cloud.",
        ).forEach { bad ->
            assertEquals(
                "for '$bad'",
                GenerationFailureReason.INVALID_OUTPUT,
                reasonOf(run(GeminiNanoStatus.AVAILABLE) { bad }),
            )
        }
    }

    // ------------------------------------------------ D/E/F/G: not-available statuses

    @Test
    fun downloadableDownloadingUnavailableAllFallBackAsUnavailable() {
        listOf(
            GeminiNanoStatus.DOWNLOADABLE,
            GeminiNanoStatus.DOWNLOADING,
            GeminiNanoStatus.UNAVAILABLE,
        ).forEach { status ->
            assertEquals(
                "for $status",
                GenerationFailureReason.UNAVAILABLE,
                reasonOf(run(status)),
            )
        }
    }

    @Test
    fun sdkErrorFallsBackAsSdkError() {
        val capability = GeminiNanoCapability(FakeStatusGateway(Result.failure(IllegalStateException("no AICore"))))
        val generator = GroundedGeminiNanoGenerator(capability, FakeGenerator { validAnswer })
        val result = runBlocking {
            generator.generate("Qaysi ilovalarni himoyalash mumkin?", "PROTECTED_APPS", knowledge, GroundedAssistantContext())
        }
        assertEquals(GenerationFailureReason.SDK_ERROR, reasonOf(result))
    }

    // ----------------------------------------------------------- H: generation exception

    @Test
    fun aThrowingGeneratorFallsBackAsGenerationError() {
        assertEquals(
            GenerationFailureReason.GENERATION_ERROR,
            reasonOf(run(GeminiNanoStatus.AVAILABLE) { error("model crashed") }),
        )
    }

    // --------------------------------------------------- I: no grounding content available

    @Test
    fun blankGroundingKnowledgeIsRefusedBeforeCallingTheModel() {
        val capture = FakeGenerator { validAnswer }
        val capability = GeminiNanoCapability(FakeStatusGateway(Result.success(GeminiNanoStatus.AVAILABLE)))
        val result = runBlocking {
            GroundedGeminiNanoGenerator(capability, capture).generate(
                "?",
                "PROTECTED_APPS",
                GroundedKnowledgeContext("empty", " ", " "),
                GroundedAssistantContext(),
            )
        }
        assertEquals(GenerationFailureReason.NO_GROUNDING, reasonOf(result))
        assertEquals("the model must not be called", null, capture.lastPrompt)
    }

    // ------------------------------------------------------------------ prompt structure

    @Test
    fun thePromptIsDeterministicAndCarriesQuestionIntentAndGrounding() {
        val capture = FakeGenerator { validAnswer }
        val capability = GeminiNanoCapability(FakeStatusGateway(Result.success(GeminiNanoStatus.AVAILABLE)))
        val generator = GroundedGeminiNanoGenerator(capability, capture)
        runBlocking {
            generator.generate(
                "Qaysi ilovalarni himoyalash mumkin?",
                "PROTECTED_APPS",
                knowledge,
                GroundedAssistantContext(protectedAppsCount = 0),
            )
        }
        val prompt = capture.lastPrompt!!
        assertTrue(prompt.contains("Qaysi ilovalarni himoyalash mumkin?"))
        assertTrue(prompt.contains("PROTECTED_APPS"))
        assertTrue(prompt.contains(knowledge.title))
        assertTrue(prompt.contains(knowledge.content))
        assertTrue(prompt.contains("protected apps count: 0"))

        // Deterministic: identical inputs -> identical prompt.
        val again = GroundedPromptBuilder.build(
            "Qaysi ilovalarni himoyalash mumkin?",
            "PROTECTED_APPS",
            knowledge,
            GroundedAssistantContext(protectedAppsCount = 0),
        )
        assertEquals(again, prompt)
    }

    // ----------------------------------------------------------------- A2 privacy (STEP 16)

    @Test
    fun theGenerationRequestCarriesNoSensitiveData() {
        val prompt = GroundedPromptBuilder.build(
            question = "Qaysi ilovalarni himoyalash mumkin?",
            intentLabel = "PROTECTED_APPS",
            knowledge = knowledge,
            context = GroundedAssistantContext(protectedAppsCount = 0),
        )
        // Values/terms that would represent protected data must never appear in the request.
        val forbidden = listOf(
            "pin", "pinsk", "parol", "password",
            "embedding", "template", "keystore", "biometr",
            "telefon raqam", "+998", "phone number",
            "accountid", "account id", "childname", "child name",
            "token", "apikey", "api_key", "secret",
        )
        val lower = prompt.lowercase()
        forbidden.forEach { marker ->
            assertFalse("prompt must not contain '$marker'", lower.contains(marker))
        }
        // And it must genuinely contain the grounding it is allowed to carry.
        assertTrue(prompt.contains(knowledge.content))
    }

    @Test
    fun theValidatorRejectsOutputWithoutKnowledgeOverlap() {
        val unrelated = AssistantAnswerValidator.validate("Salom, men sizga qanday yordam beraman?", knowledge)
        assertTrue(unrelated is AssistantAnswerValidator.Outcome.Rejected)
    }
}

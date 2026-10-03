package uz.faceguard.app.help.ai

import com.google.mlkit.genai.common.FeatureStatus
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.faceguard.app.feature.help.ai.GeminiNanoCapability
import uz.faceguard.app.feature.help.ai.GeminiNanoGateway
import uz.faceguard.app.feature.help.ai.GeminiNanoStatus
import uz.faceguard.app.feature.help.ai.GenerationRoute
import uz.faceguard.app.feature.help.ai.MlKitGeminiNanoGateway

/**
 * QALQON Phase A1 — Gemini Nano capability detection.
 *
 * The real ML Kit GenAI client cannot be constructed on the JVM (no AICore), so the SDK is
 * reached through [GeminiNanoGateway] and these tests drive a fake. Only the pure
 * `@FeatureStatus` mapping is tested against the real SDK constants.
 *
 * Phase A1 is detection-only: these tests also assert that the AI layer contains no generation
 * call and cannot touch QALQON's protected data.
 */
class GeminiNanoCapabilityTest {

    private class FakeGateway(
        private val result: Result<GeminiNanoStatus>,
        private val warmupThrows: Boolean = false,
        private val closeThrows: Boolean = false,
    ) : GeminiNanoGateway {
        var warmed = false
        var closed = false
        override suspend fun status(): GeminiNanoStatus = result.getOrThrow()
        override suspend fun warmup() {
            warmed = true
            if (warmupThrows) error("warmup failed")
        }

        override fun close() {
            closed = true
            if (closeThrows) error("close failed")
        }
    }

    private fun capability(status: GeminiNanoStatus) =
        GeminiNanoCapability(FakeGateway(Result.success(status)))

    // ---------------------------------------------------------- status mapping (real SDK ids)

    @Test
    fun everySdkFeatureStatusMapsToTheMatchingCapabilityStatus() {
        assertEquals(
            GeminiNanoStatus.AVAILABLE,
            MlKitGeminiNanoGateway.featureStatusToGeminiNanoStatus(FeatureStatus.AVAILABLE),
        )
        assertEquals(
            GeminiNanoStatus.DOWNLOADABLE,
            MlKitGeminiNanoGateway.featureStatusToGeminiNanoStatus(FeatureStatus.DOWNLOADABLE),
        )
        assertEquals(
            GeminiNanoStatus.DOWNLOADING,
            MlKitGeminiNanoGateway.featureStatusToGeminiNanoStatus(FeatureStatus.DOWNLOADING),
        )
        assertEquals(
            GeminiNanoStatus.UNAVAILABLE,
            MlKitGeminiNanoGateway.featureStatusToGeminiNanoStatus(FeatureStatus.UNAVAILABLE),
        )
    }

    @Test
    fun anUnknownSdkStatusIsReportedAsSdkErrorNotAsAvailable() {
        // A value outside the @IntDef set must never be mistaken for a usable model.
        assertEquals(
            GeminiNanoStatus.SDK_ERROR,
            MlKitGeminiNanoGateway.featureStatusToGeminiNanoStatus(Int.MAX_VALUE),
        )
        assertEquals(
            GeminiNanoStatus.SDK_ERROR,
            MlKitGeminiNanoGateway.featureStatusToGeminiNanoStatus(-1),
        )
    }

    // ------------------------------------------------------------------- detection behaviour

    @Test
    fun detectReturnsTheGatewayStatus() {
        GeminiNanoStatus.entries
            .filter { it != GeminiNanoStatus.SDK_ERROR }
            .forEach { status ->
                assertEquals(status, runBlocking { capability(status).detect() })
            }
    }

    @Test
    fun aThrowingGatewayIsReportedAsSdkErrorInsteadOfCrashing() {
        val crashy = GeminiNanoCapability(FakeGateway(Result.failure(IllegalStateException("no AICore"))))
        assertEquals(GeminiNanoStatus.SDK_ERROR, runBlocking { crashy.detect() })
    }

    // ----------------------------------------------------------------- fallback contract

    @Test
    fun availableMapsToTheGenerativeModel() {
        val capability = capability(GeminiNanoStatus.AVAILABLE)
        assertTrue(capability.isGenerativeModelUsable(GeminiNanoStatus.AVAILABLE))
        assertEquals(GenerationRoute.GENERATIVE_MODEL, capability.routeFor(GeminiNanoStatus.AVAILABLE))
    }

    @Test
    fun downloadableDownloadingUnavailableAllMapToTheDeterministicFallback() {
        listOf(
            GeminiNanoStatus.DOWNLOADABLE,
            GeminiNanoStatus.DOWNLOADING,
            GeminiNanoStatus.UNAVAILABLE,
        ).forEach { status ->
            val capability = capability(status)
            assertFalse("$status must not use the model", capability.isGenerativeModelUsable(status))
            assertEquals(
                "the fallback must be total for $status",
                GenerationRoute.DETERMINISTIC_FALLBACK,
                capability.routeFor(status),
            )
        }
    }

    @Test
    fun anSdkErrorAlsoMapsToTheDeterministicFallback() {
        val capability = capability(GeminiNanoStatus.AVAILABLE)
        assertEquals(
            GenerationRoute.DETERMINISTIC_FALLBACK,
            capability.routeFor(GeminiNanoStatus.SDK_ERROR),
        )
    }

    @Test
    fun onlyAvailableEverSelectsTheGenerativeModel() {
        val capability = capability(GeminiNanoStatus.AVAILABLE)
        GeminiNanoStatus.entries.forEach { status ->
            val expected = if (status == GeminiNanoStatus.AVAILABLE) {
                GenerationRoute.GENERATIVE_MODEL
            } else {
                GenerationRoute.DETERMINISTIC_FALLBACK
            }
            assertEquals("route for $status", expected, capability.routeFor(status))
        }
    }

    @Test
    fun routeAfterDetectMirrorsTheDetectedStatus() {
        assertEquals(GenerationRoute.GENERATIVE_MODEL, runBlocking { capability(GeminiNanoStatus.AVAILABLE).route() })
        assertEquals(GenerationRoute.DETERMINISTIC_FALLBACK, runBlocking { capability(GeminiNanoStatus.UNAVAILABLE).route() })
        val crashy = GeminiNanoCapability(FakeGateway(Result.failure(RuntimeException("boom"))))
        assertEquals(GenerationRoute.DETERMINISTIC_FALLBACK, runBlocking { crashy.route() })
    }

    // ---------------------------------------------------------------------- lifecycle safety

    @Test
    fun warmupAndCloseAreBestEffortAndNeverThrow() {
        val gateway = FakeGateway(Result.success(GeminiNanoStatus.AVAILABLE))
        val capability = GeminiNanoCapability(gateway)
        runBlocking { capability.warmup() }
        assertTrue("warmup must reach the gateway", gateway.warmed)
        capability.close()
        assertTrue("close must reach the gateway", gateway.closed)

        // A throwing warmup/close must not propagate.
        val hostile = FakeGateway(
            Result.success(GeminiNanoStatus.AVAILABLE),
            warmupThrows = true,
            closeThrows = true,
        )
        val safe = GeminiNanoCapability(hostile)
        runBlocking { safe.warmup() }
        safe.close()
    }

    // ------------------------------------------------------------------ security boundary

    @Test
    fun theAiLayerCannotTouchProtectedData() {
        val protectedData = listOf(
            // credentials / secrets
            "PinHasher", "verifyPin", "SecureCrypto", "Keystore", "AndroidBiometricPrompt", "BiometricPrompt",
            // face data
            "FaceEmbedding", "FaceTemplate", "FaceEmbeddingCodec", "tflite", "Interpreter",
            // storage / PII
            "AccountRepository", "ChildRepository", "FaceGuardDatabase", "DataStore", "ActivityLog",
            "phoneNumber", "childName", "accountId",
            // protection authority
            "ProtectionEngine", "ProtectionRuntime", "AppLockState", "startForegroundService",
        )
        aiSources().forEach { (name, source) ->
            val code = code(source)
            protectedData.forEach { symbol ->
                assertFalse("$name must not reference $symbol", code.contains(symbol))
            }
        }
    }

    @Test
    fun onlyTheSingleGatewayFileMayUseGenerationApis() {
        // Phase A2: generation is allowed, but ONLY inside MlKitGeminiNanoGateway.kt. Every
        // other ai file — and every cloud marker anywhere — stays forbidden.
        val generationApis = listOf(
            "generateContent", "GenerateContentRequest", "TextPart", "generateContentStream",
        )
        val cloudOrNetwork = listOf("openai", "retrofit", "okhttp", "apiKey", "api_key", "generativelanguage")
        aiSources().forEach { (name, source) ->
            val code = code(source)
            cloudOrNetwork.forEach { symbol ->
                assertFalse("$name must not reference $symbol", code.contains(symbol))
            }
            if (name != "MlKitGeminiNanoGateway.kt") {
                generationApis.forEach { symbol ->
                    assertFalse("$name must not reference $symbol", code.contains(symbol))
                }
            }
        }
    }

    @Test
    fun theAiLayerAddsNoNetworkPermissionRuleOrClient() {
        aiSources().forEach { (name, source) ->
            val code = code(source)
            listOf("INTERNET", "ACCESS_NETWORK_STATE").forEach { symbol ->
                assertFalse("$name must not reference $symbol", code.contains(symbol))
            }
        }
    }

    @Test
    fun theSdkIsOnlyReachedThroughTheSingleGatewayFile() {
        // Exactly one main-source file in the ai package may import the ML Kit GenAI SDK.
        val genaiImport = Regex("(?m)^import com\\.google\\.mlkit\\.genai\\.")
        val importers = aiSources().filterValues { genaiImport.containsMatchIn(it) }.keys
        assertEquals(setOf("MlKitGeminiNanoGateway.kt"), importers)
    }

    @Test
    fun theCapabilityApiTakesNoPersonalData() {
        // The gateway surface receives no arguments, so no personal data can be passed in.
        val gatewaySource = aiSources().getValue("GeminiNanoCapability.kt")
        assertTrue(gatewaySource.contains("suspend fun status()"))
        assertTrue(gatewaySource.contains("suspend fun warmup()"))
        assertTrue(gatewaySource.contains("fun close()"))
        assertFalse("status() must not take parameters", gatewaySource.contains("fun status(p"))
        assertFalse("warmup() must not take parameters", gatewaySource.contains("fun warmup(p"))
    }

    // ------------------------------------------------------------------------- helpers

    /** Strips Kotlin line and block comments so a scan only sees executable code. */
    private fun code(source: String): String =
        source
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("//[^\\n]*"), "")

    private fun aiSources(): Map<String, String> =
        File(repoRoot(), "app/src/main/java/uz/faceguard/app/feature/help/ai")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .associate { it.name to it.readText() }

    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        while (dir.parentFile != null) {
            if (File(dir, "app/src/main/res/values/strings.xml").isFile) return dir
            dir = dir.parentFile
        }
        error("could not locate the repository root from ${System.getProperty("user.dir")}")
    }
}

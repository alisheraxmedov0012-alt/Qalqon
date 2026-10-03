package uz.faceguard.app.feature.help.ai

import androidx.annotation.VisibleForTesting
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel

/**
 * The single place in QALQON that touches the ML Kit GenAI Prompt API.
 *
 * It implements both A1 capability detection and the A2 text-generation seam, and is the only
 * file that may import `com.google.mlkit.genai.*`. It receives no QALQON data other than the
 * already-grounded prompt string, so no protected data can flow in.
 *
 * The client is created once, lazily; if creation fails (a device without AICore) the failure
 * is cached, so detection does not keep retrying and simply reports `SDK_ERROR`. `status()`
 * and `generate()` never throw — a broken SDK becomes `SDK_ERROR` / empty text, and the caller
 * falls back to the deterministic assistant.
 */
class MlKitGeminiNanoGateway(
    private val clientFactory: () -> GenerativeModel = { Generation.getClient() },
) : GeminiNanoGateway, GeminiNanoGenerator {

    private val clientResult: Result<GenerativeModel> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        runCatching { clientFactory() }
    }

    override suspend fun status(): GeminiNanoStatus =
        clientResult.getOrNull()?.let { featureStatusToGeminiNanoStatus(it.checkStatus()) }
            ?: GeminiNanoStatus.SDK_ERROR

    override suspend fun warmup() {
        clientResult.getOrNull()?.warmup()
    }

    /**
     * Sends the grounded [prompt] to the on-device model.
     *
     * Returns the first candidate's text, or an empty string when the client is unavailable or
     * the model returned nothing; the caller's validator treats empty output as a fallback.
     * Only the prompt string is sent — no arguments, no QALQON data.
     */
    override suspend fun generate(prompt: String): String {
        val model = clientResult.getOrNull() ?: return ""
        val response = model.generateContent(prompt)
        return response.candidates.firstOrNull()?.text.orEmpty()
    }

    override fun close() {
        clientResult.getOrNull()?.let { runCatching { it.close() } }
    }

    companion object {
        /**
         * Maps the SDK's `@FeatureStatus` integer to QALQON's [GeminiNanoStatus].
         *
         * `checkStatus()` is annotated `@FeatureStatus` (an `@IntDef` of the four constants
         * below), so any value outside that set is treated as [GeminiNanoStatus.SDK_ERROR].
         * Exposed for JVM unit testing.
         */
        @VisibleForTesting
        fun featureStatusToGeminiNanoStatus(@FeatureStatus code: Int): GeminiNanoStatus = when (code) {
            FeatureStatus.AVAILABLE -> GeminiNanoStatus.AVAILABLE
            FeatureStatus.DOWNLOADABLE -> GeminiNanoStatus.DOWNLOADABLE
            FeatureStatus.DOWNLOADING -> GeminiNanoStatus.DOWNLOADING
            FeatureStatus.UNAVAILABLE -> GeminiNanoStatus.UNAVAILABLE
            else -> GeminiNanoStatus.SDK_ERROR
        }
    }
}

package uz.faceguard.app.feature.help.ai

import androidx.annotation.VisibleForTesting
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel

/**
 * The single place in QALQON that touches the ML Kit GenAI Prompt API.
 *
 * Phase A1 scope: this gateway only *queries* Gemini Nano availability (and can optionally
 * warm up / release the client). It never calls `generateContent`, never builds a prompt and
 * never sends a user question or any QALQON data to the model. It receives no arguments, so
 * no protected data can flow into it by construction.
 *
 * The client is created lazily, so importing the SDK costs nothing until a caller actually
 * asks for the status; on a device without AICore the first call is caught by
 * [GeminiNanoCapability] and reported as `SDK_ERROR`.
 */
class MlKitGeminiNanoGateway(
    private val clientFactory: () -> GenerativeModel = { Generation.getClient() },
) : GeminiNanoGateway {

    private val client: GenerativeModel by lazy(LazyThreadSafetyMode.SYNCHRONIZED, clientFactory)

    override suspend fun status(): GeminiNanoStatus =
        featureStatusToGeminiNanoStatus(client.checkStatus())

    override suspend fun warmup() {
        client.warmup()
    }

    override fun close() {
        runCatching { client.close() }
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

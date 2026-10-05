package uz.faceguard.app.recognition

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source-contract guards for the recognition wiring that cannot be exercised on the JVM
 * (it needs the Android camera/ML Kit stack). The decision logic itself is fully covered by
 * [RecognitionDecisionTest] and [MultiFacePolicyTest]; these assertions pin the integration
 * points that make that logic apply in production.
 *
 * Stage 1 pinned the single-face wiring; Stage 2 pins the multi-face wiring (all faces are
 * embedded and evaluated, and aggregation is centralised in `MultiFacePolicyEngine`).
 */
class RecognitionPipelineContractTest {

    private val recognizer by lazy { read("core/recognition/Recognizer.kt") }
    private val controller by lazy { read("core/pipeline/FaceCaptureController.kt") }
    private val frameEvent by lazy { read("core/pipeline/FrameEvent.kt") }
    private val engine by lazy { read("core/protection/ProtectionEngine.kt") }

    @Test
    fun theRecognizerEvaluatesEveryFaceAndAggregatesDeterministically() {
        assertTrue(
            "the recogniser must decide each face via IdentityDecisionEngine",
            recognizer.contains("IdentityDecisionEngine.decide("),
        )
        assertTrue(
            "each face's own embedding source must be passed (geometry cannot identify)",
            recognizer.contains("source = face.source"),
        )
        assertTrue(
            "the per-face decisions must be aggregated by MultiFacePolicyEngine",
            recognizer.contains("MultiFacePolicyEngine.decide("),
        )
    }

    @Test
    fun theRecognizerFallsBackToTheSingleFeatureVectorForSingleFaceFrames() {
        // Pre-Stage-2 frames (and callers that only set `features`) must keep working.
        assertTrue(recognizer.contains("faces.isNotEmpty()"))
        assertTrue(recognizer.contains("listOf(FaceFeature(features, embeddingSource))"))
    }

    @Test
    fun theCaptureControllerEmbedsEveryFaceNotJustTheFirst() {
        assertTrue(
            "every detected face must be embedded (bounded)",
            controller.contains("faces.take(MAX_ANALYZED_FACES)"),
        )
        assertTrue(
            "the frame must carry all per-face features",
            controller.contains("faces = faceFeatures"),
        )
        assertFalse(
            "the identity path must not be decided from faces.firstOrNull()",
            controller.contains("features = primary?.let { extractEmbedding"),
        )
    }

    @Test
    fun theEmbeddingSourceIsDefaultedSoDirectlyBuiltFramesKeepTheirBehaviour() {
        assertTrue(
            "FrameEvent.embeddingSource must default to MODEL",
            frameEvent.contains("val embeddingSource: EmbeddingSource = EmbeddingSource.MODEL"),
        )
        assertTrue(
            "FrameEvent.faces must default to an empty list",
            frameEvent.contains("val faces: List<FaceFeature> = emptyList()"),
        )
    }

    @Test
    fun theEngineRoutesThroughTheMultiFaceEvaluationAndPassesTheChildActionResolver() {
        assertTrue(
            "the protection engine must evaluate all faces",
            engine.contains("recognizer.evaluateAll(f, parent, children)"),
        )
        assertTrue(
            "the engine must supply the per-child configured action for the foreground app",
            engine.contains("appPolicyLookup(childId, it) }?.action"),
        )
    }

    @Test
    fun noBiometricVectorIsEverLogged() {
        // Privacy: an embedding/template *value* must never be interpolated into a log line.
        listOf(recognizer, controller, engine).forEach { source ->
            assertFalse(
                "a biometric value must never be logged",
                Regex("""Log\.[dviwe]\([^)]*\$[{(]?(embedding|features|template|templateRef|plainRef)\b""")
                    .containsMatchIn(source),
            )
        }
    }

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

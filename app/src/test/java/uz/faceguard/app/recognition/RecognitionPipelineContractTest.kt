package uz.faceguard.app.recognition

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 1 (Recognition Reliability): source-contract guards for the wiring that cannot
 * be exercised on the JVM (it needs the Android camera/ML Kit stack). The decision logic
 * itself is fully covered by [RecognitionDecisionTest]; these assertions pin the three
 * integration points that make that logic apply in production.
 */
class RecognitionPipelineContractTest {

    private val recognizer by lazy { read("core/recognition/Recognizer.kt") }
    private val controller by lazy { read("core/pipeline/FaceCaptureController.kt") }
    private val frameEvent by lazy { read("core/pipeline/FrameEvent.kt") }

    @Test
    fun theRecognizerDelegatesToTheSingleDecisionEngineAndPassesTheEmbeddingSource() {
        assertTrue(
            "the recogniser must delegate the decision to IdentityDecisionEngine",
            recognizer.contains("IdentityDecisionEngine.decide("),
        )
        assertTrue(
            "the recogniser must pass the frame's embedding source so geometry is excluded",
            recognizer.contains("source = frame.embeddingSource"),
        )
    }

    @Test
    fun theCaptureControllerTagsModelEmbeddingsAsModelAndFallbacksAsGeometry() {
        assertTrue(
            "the model embedding must be tagged MODEL",
            controller.contains("FeatureVector(embedding, EmbeddingSource.MODEL)"),
        )
        assertTrue(
            "the geometry fallback must be tagged GEOMETRY",
            controller.contains("FeatureVector(it, EmbeddingSource.GEOMETRY)"),
        )
        assertTrue(
            "the frame must carry the feature source",
            controller.contains("embeddingSource = feature?.source ?: EmbeddingSource.MODEL"),
        )
    }

    @Test
    fun theEmbeddingSourceIsDefaultedSoDirectlyBuiltFramesKeepTheirBehaviour() {
        assertTrue(
            "FrameEvent.embeddingSource must default to MODEL",
            frameEvent.contains("val embeddingSource: EmbeddingSource = EmbeddingSource.MODEL"),
        )
    }

    @Test
    fun noBiometricVectorIsEverLogged() {
        // Privacy: an embedding/template *value* must never be interpolated into a log
        // line. (A message such as "embedding inference failed" is a failure notice, not
        // the data, and is allowed.)
        listOf(recognizer, controller).forEach { source ->
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

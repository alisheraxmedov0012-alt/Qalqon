package uz.faceguard.app.core.pipeline

import com.google.mlkit.vision.common.InputImage

/** Frame approved by the detector; representation here is the InputImage seam. */
data class FrameEvent(
    val image: InputImage,
    val faceCount: Int = 1,
    val timestamp: Long = System.currentTimeMillis(),
)

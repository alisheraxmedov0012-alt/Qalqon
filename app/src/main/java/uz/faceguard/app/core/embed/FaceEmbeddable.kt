package uz.faceguard.app.core.embed

import java.io.File
import uz.faceguard.app.core.pipeline.FrameEvent

/**
 * MVP storage: keeps only accepted-frame timestamps in app-private memory.
 * TODO(real-embedding): swap `PrivateStorageEmbeddable` for an on-device
 * embedding model writing a secure template; the interface stays stable.
 */
interface FaceEmbeddable {
    /**
     * Store accepted artifacts (today: timestamps). Returns a non-null
     * template reference if success.
     */
    suspend fun collect(faces: List<FrameEvent>): String
}

class PrivateStorageEmbeddable : FaceEmbeddable {
    @Volatile var counter = 0
    private val collected = mutableListOf<Long>()

    override suspend fun collect(faces: List<FrameEvent>): String {
        faces.forEach { collected.add(it.timestamp) }
        counter += faces.size
        return "template-reference-${System.identityHashCode(this)}"
    }
}

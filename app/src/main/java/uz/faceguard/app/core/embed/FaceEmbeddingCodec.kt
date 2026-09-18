package uz.faceguard.app.core.embed

import android.util.Base64
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Lossless, locale-safe serialization for an on-device face template vector. */
object FaceEmbeddingCodec {

    fun encode(values: FloatArray): String {
        val buffer = ByteBuffer.allocate(values.size * java.lang.Float.BYTES).order(ByteOrder.LITTLE_ENDIAN)
        values.forEach { buffer.putFloat(it) }
        return Base64.encodeToString(buffer.array(), Base64.NO_WRAP)
    }

    fun decode(encoded: String): FloatArray? {
        return try {
            val bytes = Base64.decode(encoded, Base64.NO_WRAP)
            if (bytes.isEmpty() || bytes.size % java.lang.Float.BYTES != 0) return null
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            FloatArray(bytes.size / java.lang.Float.BYTES) { buffer.float }
        } catch (_: Exception) {
            null
        }
    }
}

package uz.faceguard.app.feature.settings

import android.content.Context
import android.graphics.drawable.Drawable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Resolves the *real* launcher icon of an installed application.
 *
 * Presentation-only helper: it reads the icon the device already exposes through
 * `PackageManager` (the same metadata source the protected-apps catalogue is built
 * from) and never invents, downloads or recolours anything. Work happens on
 * [Dispatchers.IO], and results are memoised, so a large catalogue never re-queries
 * `PackageManager` while the list scrolls or recomposes.
 *
 * The cache holds only [ImageBitmap] results (never a `Context`), is bounded, and is
 * keyed by package name — the same stable identity the catalogue already uses.
 */
class AppIconResolver(
    /** Application context: the resolver is process-scoped, so no Activity is retained. */
    private val context: Context,
    /** The square edge (in pixels) every icon is decoded at, so rows stay uniform. */
    private val iconSizePx: Int,
) {

    private val lock = Any()

    /** Access-ordered so the least recently used entry is evicted first. */
    private val cache = object : LinkedHashMap<String, ImageBitmap?>(INITIAL_CAPACITY, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap?>?): Boolean =
            size > MAX_ENTRIES
    }

    /** The already-resolved icon, or `null` when it has not been looked up yet. */
    fun cached(packageName: String): ImageBitmap? = synchronized(lock) { cache[packageName] }

    /**
     * The app's icon, or `null` when the platform has none to offer. A `null` is
     * cached too, so a missing icon is not retried on every recomposition.
     */
    suspend fun resolve(packageName: String): ImageBitmap? {
        synchronized(lock) {
            if (cache.containsKey(packageName)) return cache[packageName]
        }
        val icon = withContext(Dispatchers.IO) { load(packageName) }
        synchronized(lock) { cache[packageName] = icon }
        return icon
    }

    private fun load(packageName: String): ImageBitmap? = runCatching {
        val drawable: Drawable = context.packageManager.getApplicationIcon(packageName)
        // Drawn by the platform into a square bitmap of the requested size: the real
        // icon (including an adaptive icon's own background) is preserved, never
        // cropped or tinted by QALQON.
        drawable.toBitmap(width = iconSizePx, height = iconSizePx).asImageBitmap()
    }.getOrNull()

    private companion object {
        const val INITIAL_CAPACITY = 64
        const val MAX_ENTRIES = 256
    }
}

/**
 * The resolved icon for [packageName] as composition state: seeded from the cache so a
 * row that scrolls back into view shows its icon immediately, and resolved once
 * otherwise. Returns `null` until (or unless) the platform provides one.
 */
@Composable
fun rememberAppIcon(resolver: AppIconResolver, packageName: String): ImageBitmap? =
    produceState<ImageBitmap?>(initialValue = resolver.cached(packageName), key1 = packageName) {
        if (value == null) value = resolver.resolve(packageName)
    }.value

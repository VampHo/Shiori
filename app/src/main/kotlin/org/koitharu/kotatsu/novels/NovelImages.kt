package org.koitharu.kotatsu.novels

import android.content.Context
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import java.io.File

/** One loader and disk cache across novel screens, instead of a new cache per activity. */
object NovelImages {
    @Volatile private var instance: ImageLoader? = null
    fun get(context: Context): ImageLoader = instance ?: synchronized(this) {
        instance ?: ImageLoader.Builder(context.applicationContext)
            .components { add(OkHttpNetworkFetcherFactory()) }
            .diskCache { DiskCache.Builder().directory(File(context.cacheDir, "novel_covers")).maxSizeBytes(64L * 1024 * 1024).build() }
            .build().also { instance = it }
    }
}

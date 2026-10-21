package org.koitharu.kotatsu.novels

import android.content.Context
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import java.io.File
import okio.Path.Companion.toOkioPath

/** One loader and disk cache across novel screens, instead of a new cache per activity. */
object NovelImages {
    @Volatile private var instance: ImageLoader? = null
    fun get(context: Context): ImageLoader = instance ?: synchronized(this) {
        instance ?: ImageLoader.Builder(context.applicationContext)
            .components {
                add(OkHttpNetworkFetcherFactory())
                add(coil3.svg.SvgDecoder.Factory())
                add(org.koitharu.kotatsu.core.image.AvifImageDecoder.Factory())
                if (android.os.Build.VERSION.SDK_INT >= 28) add(coil3.gif.AnimatedImageDecoder.Factory())
                else add(coil3.gif.GifDecoder.Factory())
            }
            .diskCache { DiskCache.Builder().directory(File(context.cacheDir, "novel_covers").toOkioPath()).maxSizeBytes(64L * 1024 * 1024).build() }
            .build().also { instance = it }
    }
}

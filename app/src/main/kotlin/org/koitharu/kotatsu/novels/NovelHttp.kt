package org.koitharu.kotatsu.novels

import android.content.Context
import android.webkit.WebSettings
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import org.koitharu.kotatsu.core.network.cookies.AndroidCookieJar
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Novel requests and the in-app source browser share the same cookie store and user agent. */
internal object NovelHttp {
    var userAgent: String = "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 Chrome/131.0.0.0 Mobile Safari/537.36"
        private set
    fun initialize(context: Context) {
        userAgent = runCatching { WebSettings.getDefaultUserAgent(context) }.getOrDefault(userAgent)
        client // Initialize the WebView-backed cookie store on the activity thread.
    }
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .cookieJar(runCatching<CookieJar> { AndroidCookieJar() }.getOrDefault(CookieJar.NO_COOKIES))
            .build()
    }
}

internal class NovelAccessException(val address: String, message: String) : IOException(message)

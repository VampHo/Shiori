/* Detached parser viewport from Kotatsu-Redo, GPL-3.0. */
package org.koitharu.kotatsu.core.util.ext
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
fun WebView.prepareDetachedParserViewport() {
    if (width > 0 && height > 0) return
    layoutParams = ViewGroup.LayoutParams(1080, 1920)
    measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY))
    layout(0, 0, 1080, 1920)
}

package org.koitharu.kotatsu.novels

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.target
import coil3.request.placeholder
import coil3.request.error
import coil3.request.fallback
import com.google.android.material.color.MaterialColors
import org.koitharu.kotatsu.R

/** Recycled cover cells shared by novel browse, library and history. */
class NovelGridAdapter(
    private val images: ImageLoader,
    private val books: List<NovelLink>,
    private val sources: Boolean = false,
    private val listMode: Boolean = false,
    private val onClick: (NovelLink) -> Unit,
    private val onLongClick: (NovelLink) -> Unit = {},
) : RecyclerView.Adapter<NovelGridAdapter.Holder>() {
    class Holder(val root: LinearLayout, val cover: ImageView, val title: TextView) : RecyclerView.ViewHolder(root)
    override fun getItemCount() = books.size
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val c = parent.context
        fun dp(n: Int) = (n * c.resources.displayMetrics.density).toInt()
        val row = LinearLayout(c).apply {
            orientation = if (listMode) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(8), dp(4), dp(8))
            layoutParams = RecyclerView.LayoutParams(-1, -2)
            isFocusable = true
        }
        val cover = ImageView(c).apply {
            scaleType = if (sources) ImageView.ScaleType.CENTER_INSIDE else ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply {
                cornerRadius = dp(if (sources) 16 else 8).toFloat()
                setColor(MaterialColors.getColor(parent, com.google.android.material.R.attr.colorSurfaceContainerHigh))
            }
            clipToOutline = true
            importantForAccessibility = ImageView.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val width = if (listMode) dp(64) else if (sources) dp(52) else (parent.resources.displayMetrics.widthPixels - dp(48)) / 3
        row.addView(cover, LinearLayout.LayoutParams(width, if (sources) width else (width * 1.45f).toInt()).apply { gravity = Gravity.CENTER_HORIZONTAL })
        val title = TextView(c).apply {
            textSize = if (sources) 14f else 13f
            gravity = if (listMode) Gravity.START else Gravity.CENTER
            maxLines = 2; minLines = 2; ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(6), dp(8), dp(6), 0)
            if (sources) setTypeface(typeface, Typeface.BOLD)
        }
        row.addView(title, LinearLayout.LayoutParams(if (listMode) 0 else -1, -2, if (listMode) 1f else 0f))
        return Holder(row, cover, title)
    }
    override fun onBindViewHolder(holder: Holder, position: Int) {
        val book = books[position]
        holder.title.text = book.title
        holder.root.contentDescription = book.title
        if (sources) holder.cover.setImageResource(when (book.source) {
            "inori" -> R.drawable.novel_source_inori
            "novelfire" -> R.drawable.novel_source_novelfire
            "wattpad" -> R.drawable.novel_source_wattpad
            "fenrir" -> R.drawable.novel_source_fenrirealm
            "wuxiaworld" -> R.drawable.novel_source_wuxiaworld
            "novelfull" -> R.drawable.novel_source_novelfull
            "readhive" -> R.drawable.novel_source_readhive
            "freewebnovel" -> R.drawable.novel_source_freewebnovel
            "libread" -> R.drawable.novel_source_libread
            else -> R.drawable.novel_source_royalroad
        }) else images.enqueue(ImageRequest.Builder(holder.root.context).data(book.cover.ifBlank { null })
            .fallback(R.drawable.ic_book_page).placeholder(R.drawable.ic_book_page).error(R.drawable.ic_book_page).target(holder.cover).build())
        holder.root.setOnClickListener { onClick(book) }
        holder.root.setOnLongClickListener { onLongClick(book); true }
    }
}

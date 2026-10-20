package org.koitharu.kotatsu.novels

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.SeekBar
import androidx.core.content.edit
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch

/** Shared controls for the settings screen and in-reader appearance sheet. */
class NovelReaderOptions(private val context: Context) {
    private val prefs = context.getSharedPreferences("shiori_novels", Context.MODE_PRIVATE)
    val fullscreen get() = prefs.getBoolean("reader_fullscreen", true)
    val keepScreen get() = prefs.getBoolean("keep_screen", false)
    val margin get() = prefs.getInt("reader_margin", 20).coerceIn(8, 40)
    val colors: Pair<Int, Int> get() = when (prefs.getString("reader_theme", "Dark")) {
        "Light" -> Color.rgb(250, 250, 247) to Color.rgb(32, 33, 36)
        "Sepia" -> Color.rgb(244, 232, 208) to Color.rgb(69, 52, 33)
        "AMOLED" -> Color.BLACK to Color.rgb(210, 210, 210)
        else -> Color.rgb(18, 19, 24) to Color.rgb(211, 211, 219)
    }
    private val fonts = linkedMapOf("Serif" to "serif", "Sans serif" to "sans-serif", "Book serif" to "serif-monospace",
        "Rounded" to "sans-serif-rounded", "Condensed" to "sans-serif-condensed", "Monospace" to "monospace")
    fun style(text: TextView) {
        text.textSize = prefs.getFloat("font_size", 20f).coerceIn(14f, 32f)
        text.typeface = Typeface.create(fonts[prefs.getString("reader_font", "Serif")] ?: "serif", Typeface.NORMAL)
        text.setTextColor(colors.second)
        text.setLineSpacing(0f, when (prefs.getString("reader_spacing", "Comfortable")) {
            "Compact" -> 1.2f; "Spacious" -> 1.7f; else -> 1.4f
        })
        text.letterSpacing = prefs.getInt("reader_letter_spacing", 0).coerceIn(0, 8) / 100f
        if (android.os.Build.VERSION.SDK_INT >= 26) text.justificationMode = if (prefs.getBoolean("reader_justify", false))
            android.text.Layout.JUSTIFICATION_MODE_INTER_WORD else android.text.Layout.JUSTIFICATION_MODE_NONE
    }
    fun build(changed: () -> Unit): LinearLayout {
        fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()
        val panel = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(20)) }
        fun heading(value: String) { panel.addView(TextView(context).apply { text=value; textSize=18f; setPadding(0,dp(20),0,dp(8)); setTypeface(typeface, Typeface.BOLD) }) }
        heading("Reading preview")
        val preview = TextView(context).apply { text="A quiet chapter\n\nThe morning light fell across the pages. There was always another story waiting to be discovered."; setPadding(dp(16),dp(16),dp(16),dp(16)) }
        panel.addView(preview)
        fun update() { style(preview); preview.setBackgroundColor(colors.first); changed() }
        style(preview); preview.setBackgroundColor(colors.first)
        fun choice(label: String, key: String, options: List<String>, fallback: String) {
            val button = MaterialButton(context, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply { isAllCaps=false; gravity=android.view.Gravity.START; text="$label · ${prefs.getString(key, fallback)}" }
            button.setOnClickListener {
                MaterialAlertDialogBuilder(context).setTitle(label).setSingleChoiceItems(options.toTypedArray(), options.indexOf(prefs.getString(key, fallback))) { dialog, index ->
                    prefs.edit { putString(key, options[index]) }; button.text="$label · ${options[index]}"; update(); dialog.dismiss()
                }.setNegativeButton("Cancel",null).show()
            }
            panel.addView(button, LinearLayout.LayoutParams(-1,-2))
        }
        fun slider(label: String, key: String, min: Int, max: Int, fallback: Int, floating: Boolean=false) {
            val initial = (if(floating) prefs.getFloat(key,fallback.toFloat()).toInt() else prefs.getInt(key,fallback)).coerceIn(min,max)
            val caption = TextView(context).apply { text="$label · $initial"; setPadding(0,dp(12),0,0) }; panel.addView(caption)
            panel.addView(SeekBar(context).apply {
                this.max=max-min; progress=initial-min; contentDescription=label
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(bar: SeekBar?, value: Int, user: Boolean) {
                        if(!user) return
                        caption.text="$label · ${value+min}"
                        prefs.edit { if(floating) putFloat(key,(value+min).toFloat()) else putInt(key,value+min) }; update()
                    }
                    override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                    override fun onStopTrackingTouch(bar: SeekBar?) = Unit
                })
            })
        }
        fun toggle(label: String, key: String, fallback: Boolean=false) {
            panel.addView(MaterialSwitch(context).apply { text=label; isChecked=prefs.getBoolean(key,fallback); setPadding(0,dp(8),0,dp(8)); setOnCheckedChangeListener { _, checked -> prefs.edit { putBoolean(key,checked) }; update() } }, LinearLayout.LayoutParams(-1,-2))
        }
        heading("Appearance")
        choice("Reading theme", "reader_theme", listOf("Dark","Light","Sepia","AMOLED"), "Dark")
        choice("Font", "reader_font", fonts.keys.toList(), "Serif")
        slider("Text size", "font_size",14,32,20,true)
        heading("Page layout")
        choice("Line spacing", "reader_spacing", listOf("Compact","Comfortable","Spacious"),"Comfortable")
        slider("Side margins", "reader_margin",8,40,20)
        slider("Letter spacing", "reader_letter_spacing",0,8,0)
        toggle("Justify text", "reader_justify")
        heading("Reading behavior")
        toggle("Full-screen reading", "reader_fullscreen",true)
        toggle("Keep screen on", "keep_screen")
        panel.addView(TextView(context).apply { text="Tap the chapter text to show or hide reader controls. Long-press to select text. Your choices are saved automatically."; textSize=14f; setPadding(0,dp(12),0,dp(8)) })
        return panel
    }
}

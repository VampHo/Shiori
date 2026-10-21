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
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    private fun color(attr: Int): Int = com.google.android.material.color.MaterialColors.getColor(context, attr, Color.GRAY)

    fun actionRow(title: String, summary: String, icon: Int, action: () -> Unit): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            minimumHeight = dp(72)
            setPadding(dp(20), dp(12), dp(20), dp(12))
            val value = android.util.TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)
            setBackgroundResource(value.resourceId)
            isFocusable = true
            setOnClickListener { action() }
        }
        row.addView(android.widget.ImageView(context).apply {
            setImageResource(icon)
            imageTintList = android.content.res.ColorStateList.valueOf(color(com.google.android.material.R.attr.colorOnSurfaceVariant))
            importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(24),dp(24)).apply { marginEnd=dp(24) })
        val labels = LinearLayout(context).apply { orientation=LinearLayout.VERTICAL }
        labels.addView(TextView(context).apply { text=title; textSize=16f; setTextColor(color(com.google.android.material.R.attr.colorOnSurface)) })
        labels.addView(TextView(context).apply { text=summary; textSize=14f; setPadding(0,dp(3),0,0); setTextColor(color(com.google.android.material.R.attr.colorOnSurfaceVariant)); tag="summary" })
        row.addView(labels,LinearLayout.LayoutParams(0,-2,1f))
        return row
    }

    fun build(changed: () -> Unit): LinearLayout {
        val panel = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        fun heading(value: String) { panel.addView(TextView(context).apply {
            text=value; textSize=14f; setTextColor(color(androidx.appcompat.R.attr.colorPrimary))
            setPadding(dp(20),dp(24),dp(20),dp(8))
        }) }
        fun choice(label: String, key: String, options: List<String>, fallback: String, icon: Int) {
            lateinit var row: LinearLayout
            row = actionRow(label,prefs.getString(key,fallback).orEmpty(),icon) {
                MaterialAlertDialogBuilder(context).setTitle(label).setSingleChoiceItems(options.toTypedArray(),options.indexOf(prefs.getString(key,fallback))) { dialog,index ->
                    prefs.edit { putString(key,options[index]) }
                    row.findViewWithTag<TextView>("summary").text=options[index]
                    changed(); dialog.dismiss()
                }.setNegativeButton("Cancel",null).show()
            }
            panel.addView(row)
        }
        fun slider(label: String,key: String,min: Int,max: Int,fallback: Int,icon: Int,floating: Boolean=false) {
            fun value() = (if(floating) prefs.getFloat(key,fallback.toFloat()).toInt() else prefs.getInt(key,fallback)).coerceIn(min,max)
            lateinit var row: LinearLayout
            row=actionRow(label,value().toString(),icon) {
                var selected=value()
                val content=LinearLayout(context).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(24),dp(16),dp(24),dp(8)) }
                val caption=TextView(context).apply { text=selected.toString(); textSize=22f; gravity=android.view.Gravity.CENTER }
                content.addView(caption)
                content.addView(SeekBar(context).apply {
                    this.max=max-min; progress=selected-min; contentDescription=label
                    setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(bar:SeekBar?,v:Int,user:Boolean) { selected=v+min; caption.text=selected.toString() }
                        override fun onStartTrackingTouch(bar:SeekBar?)=Unit
                        override fun onStopTrackingTouch(bar:SeekBar?)=Unit
                    })
                })
                MaterialAlertDialogBuilder(context).setTitle(label).setView(content).setNegativeButton("Cancel",null)
                    .setPositiveButton("Apply") { _,_ ->
                        prefs.edit { if(floating) putFloat(key,selected.toFloat()) else putInt(key,selected) }
                        row.findViewWithTag<TextView>("summary").text=selected.toString(); changed()
                    }.show()
            }
            panel.addView(row)
        }
        fun toggle(label:String,summary:String,key:String,icon:Int,fallback:Boolean=false) {
            lateinit var control:MaterialSwitch
            val row=actionRow(label,summary,icon) { control.isChecked=!control.isChecked }
            control=MaterialSwitch(context).apply {
                isChecked=prefs.getBoolean(key,fallback); contentDescription=label
                setOnCheckedChangeListener { _,checked -> prefs.edit { putBoolean(key,checked) }; changed() }
            }
            row.addView(control); panel.addView(row)
        }
        heading("Appearance")
        choice("Reading theme","reader_theme",listOf("Dark","Light","Sepia","AMOLED"),"Dark",org.koitharu.kotatsu.R.drawable.ic_appearance)
        choice("Font","reader_font",fonts.keys.toList(),"Serif",org.koitharu.kotatsu.R.drawable.ic_read)
        slider("Text size","font_size",14,32,20,org.koitharu.kotatsu.R.drawable.ic_zoom_in,true)
        panel.addView(actionRow("Reading preview","Preview your font and theme",org.koitharu.kotatsu.R.drawable.ic_eye) {
            val preview=TextView(context).apply { text="A quiet chapter\n\nThe morning light fell across the pages. Another story was waiting to be discovered."; setPadding(dp(24),dp(24),dp(24),dp(24)); style(this); setBackgroundColor(colors.first) }
            MaterialAlertDialogBuilder(context).setTitle("Reading preview").setView(preview).setPositiveButton("Done",null).show()
        })
        heading("Page layout")
        choice("Line spacing","reader_spacing",listOf("Compact","Comfortable","Spacious"),"Comfortable",org.koitharu.kotatsu.R.drawable.ic_reader_vertical)
        slider("Side margins","reader_margin",8,40,20,org.koitharu.kotatsu.R.drawable.ic_move_horizontal)
        slider("Letter spacing","reader_letter_spacing",0,8,0,org.koitharu.kotatsu.R.drawable.ic_read)
        toggle("Justify text","Align text to both edges","reader_justify",org.koitharu.kotatsu.R.drawable.ic_read)
        heading("Reading")
        toggle("Full-screen reading","Tap the page to show reader controls","reader_fullscreen",org.koitharu.kotatsu.R.drawable.ic_eye,true)
        toggle("Keep screen on","While reading a chapter","keep_screen",org.koitharu.kotatsu.R.drawable.ic_timelapse)
        return panel
    }
}

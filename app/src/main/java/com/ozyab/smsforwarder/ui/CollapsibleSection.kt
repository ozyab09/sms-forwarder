package com.ozyab.smsforwarder.ui

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import com.ozyab.smsforwarder.R

/**
 * Сворачиваемая секция настроек.
 * Используется как wrapper: задаётся заголовок, стрелка и содержимое через addView(content).
 */
class CollapsibleSection @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private var tvTitle: TextView
    private var tvChevron: TextView
    val contentContainer: LinearLayout

    /** Текущее состояние свёрнутости */
    var expanded: Boolean = true
        set(value) {
            field = value
            tvChevron.text = if (value) "\u25BC" else "\u25B6" // ▼ / ▶
            contentContainer.isVisible = value
        }

    /** Клик по шапке переключает сворачивание */
    var onHeaderClick: (() -> Unit)? = null
        set(value) {
            field = value
            findViewById<LinearLayout>(R.id.collapsible_header)
                .setOnClickListener { value?.invoke() }
        }

    init {
        orientation = VERTICAL
        LayoutInflater.from(context).inflate(R.layout.collapsible_section, this, true)
        tvTitle = findViewById(R.id.tv_section_title)
        tvChevron = findViewById(R.id.tv_section_chevron)
        contentContainer = findViewById(R.id.collapsible_content)

        onHeaderClick = {
            expanded = !expanded
        }
    }

    fun setTitle(textRes: Int) = setTitle(context.getString(textRes))
    fun setTitle(text: String) {
        tvTitle.text = text
    }
}

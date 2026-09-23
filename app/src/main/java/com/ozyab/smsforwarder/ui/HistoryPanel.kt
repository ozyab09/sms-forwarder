package com.ozyab.smsforwarder.ui

import android.text.TextUtils
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.google.android.material.chip.ChipGroup
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.ozyab.smsforwarder.R
import com.ozyab.smsforwarder.history.EventEntity
import com.ozyab.smsforwarder.history.EventHistory
import android.os.Handler
import android.os.Looper

/**
 * Панель «История»: поиск, фильтры по типу, список событий (клик — детали),
 * очистка. Выделена из MainActivity (декомпозиция, #143): Activity остаётся
 * тонкой склейкой панелей. Запросы идут через MainViewModel (MVVM), рендер —
 * по подписке на viewModel.history.
 */
class HistoryPanel(
    private val activity: MainActivity,
    private val viewModel: MainViewModel,
    container: LinearLayout,
) {
    private val historyList: LinearLayout = container.findViewById(R.id.history_list)
    private val etHistorySearch: TextInputEditText = container.findViewById(R.id.et_history_search)
    private val chipGroupHistory: ChipGroup = container.findViewById(R.id.chip_group_history)
    private val historySearchHandler = Handler(Looper.getMainLooper())
    private val historySearchRunnable = Runnable { renderHistory() }

    init {
        // N6 (аудит-2): снять незавершённый debounce-колбэк при уничтожении
        // Activity — иначе Runnable выстрелит в никуда после destroy.
        activity.lifecycle.addObserver(
            androidx.lifecycle.LifecycleEventObserver { _, event ->
                if (event == androidx.lifecycle.Lifecycle.Event.ON_DESTROY) {
                    historySearchHandler.removeCallbacks(historySearchRunnable)
                }
            }
        )
        container.findViewById<MaterialButton>(R.id.btn_clear_history).setOnClickListener {
            confirmClearHistory()
        }
        etHistorySearch.addTextChangedListener(textWatcher {
            // Debounce: запрос к Room не на каждый символ
            historySearchHandler.removeCallbacks(historySearchRunnable)
            historySearchHandler.postDelayed(historySearchRunnable, 300)
        })
        chipGroupHistory.setOnCheckedStateChangeListener { _, _ -> renderHistory() }
    }

    fun onResume() = renderHistory()

    /** Текущие фильтры -> ViewModel; рендер приходит по подписке на state. */
    fun renderHistory() {
        val type = when (chipGroupHistory.checkedChipId) {
            R.id.chip_history_sms -> EventHistory.TYPE_SMS
            else -> null
        }
        val callsOnly = chipGroupHistory.checkedChipId == R.id.chip_history_calls
        val query = etHistorySearch.text?.toString()?.trim().orEmpty()
        viewModel.setHistoryFilter(type, query, callsOnly)
        viewModel.loadHistory(type, query, callsOnly)
    }

    fun renderHistoryList(events: List<EventEntity>) {
        historyList.removeAllViews()
        if (events.isEmpty()) {
            historyList.addView(emptyHistoryView())
            return
        }
        for (e in events) historyList.addView(buildHistoryRow(e))
    }

    private fun emptyHistoryView(): TextView = TextView(activity).apply {
        text = activity.getString(R.string.history_empty)
        setTextColor(ContextCompat.getColor(activity, android.R.color.darker_gray))
        textSize = 14f
        setPadding(4, 24, 4, 8)
    }

    /**
     * Строит строку события: иконка типа/статуса, отправитель, время, превью текста.
     * Клик — диалог с полными деталями (showEventDetails).
     */
    private fun buildHistoryRow(e: EventEntity): View {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(4, 10, 4, 10)
        }
        row.isClickable = true
        row.setOnClickListener { showEventDetails(e) }
        val icon = when (e.type) {
            EventHistory.TYPE_SMS -> "📨"
            else -> "📵"
        }
        val statusIcon = when (e.status) {
            EventHistory.STATUS_SENT -> "✅"
            EventHistory.STATUS_FAILED -> "⚠️"
            EventHistory.STATUS_DROPPED -> "❌"
            else -> "⏳"
        }
        val sender = e.sender.ifBlank { "—" }
        val title = "$icon $sender  $statusIcon ${dateTime(e.timestamp)}"
        row.addView(
            TextView(activity).apply {
                text = title
                setTextSize(13f)
                setTypeface(null, android.graphics.Typeface.BOLD)
            }
        )
        val body = e.body.ifBlank { e.formattedText.ifBlank { "—" } }
        if (body.isNotBlank() && body != "—") {
            row.addView(
                TextView(activity).apply {
                    text = body
                    setTextSize(12f)
                    setTextColor(ContextCompat.getColor(activity, android.R.color.darker_gray))
                    maxLines = 3
                    ellipsize = TextUtils.TruncateAt.END
                }
            )
        }
        val status = when (e.status) {
            EventHistory.STATUS_SENT -> activity.getString(R.string.history_status_sent) + (e.channelName?.let { " · $it" } ?: "")
            EventHistory.STATUS_FAILED -> activity.getString(R.string.history_status_failed)
            EventHistory.STATUS_DROPPED -> activity.getString(R.string.history_status_dropped)
            else -> activity.getString(R.string.history_status_queued)
        }
        row.addView(
            TextView(activity).apply {
                text = status
                setTextSize(11f)
                setTextColor(
                    ContextCompat.getColor(
                        activity,
                        when (e.status) {
                            EventHistory.STATUS_SENT -> android.R.color.holo_green_dark
                            EventHistory.STATUS_DROPPED -> android.R.color.holo_red_dark
                            EventHistory.STATUS_FAILED -> android.R.color.holo_orange_dark
                            else -> android.R.color.darker_gray
                        }
                    )
                )
            }
        )
        return row
    }

    /**
     * Диалог с полной информацией о событии: получатель (Chat ID), бот, канал,
     * статус, попытки, исходный текст и полный текст отправленного сообщения.
     * Текст скроллируется — длинные SMS видны целиком.
     */
    private fun showEventDetails(e: EventEntity) {
        val statusText = when (e.status) {
            EventHistory.STATUS_SENT -> activity.getString(R.string.history_status_sent)
            EventHistory.STATUS_FAILED -> activity.getString(R.string.history_status_failed)
            EventHistory.STATUS_DROPPED -> activity.getString(R.string.history_status_dropped)
            else -> activity.getString(R.string.history_status_queued)
        }
        val typeText = when (e.type) {
            EventHistory.TYPE_SMS -> activity.getString(R.string.history_detail_type_sms)
            else -> activity.getString(R.string.history_detail_type_call)
        }
        val dash = activity.getString(R.string.history_detail_none)
        val sb = StringBuilder()
        sb.append(activity.getString(R.string.history_detail_type)).append(": ").append(typeText).append('\n')
        sb.append(activity.getString(R.string.history_detail_time)).append(": ")
            .append(dateTimeFull(e.timestamp)).append('\n')
        sb.append(activity.getString(R.string.history_detail_sender)).append(": ")
            .append(e.sender.ifBlank { dash }).append('\n')
        sb.append(activity.getString(R.string.history_detail_status)).append(": ").append(statusText).append('\n')
        sb.append(activity.getString(R.string.history_detail_chat)).append(": ")
            .append(e.chatId ?: dash).append('\n')
        sb.append(activity.getString(R.string.history_detail_bot)).append(": ")
            .append(e.botUsername?.let { "@$it" } ?: dash).append('\n')
        sb.append(activity.getString(R.string.history_detail_channel)).append(": ")
            .append(e.channelName ?: dash).append('\n')
        sb.append(activity.getString(R.string.history_detail_attempts)).append(": ").append(e.attempts).append('\n')
        if (e.body.isNotBlank() && e.body != e.formattedText) {
            sb.append('\n').append(activity.getString(R.string.history_detail_original)).append(":\n")
                .append(e.body).append('\n')
        }
        sb.append('\n').append(activity.getString(R.string.history_detail_message)).append(":\n")
            .append(e.formattedText.ifBlank { dash })

        val tv = TextView(activity).apply {
            text = sb.toString()
            setTextIsSelectable(true)
            textSize = 13f
            val pad = (16 * activity.resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        val scroll = ScrollView(activity).apply { addView(tv) }
        AlertDialog.Builder(activity)
            .setTitle(R.string.history_detail_title)
            .setView(scroll)
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    private fun dateTimeFull(ts: Long): String {
        val sdf = java.text.SimpleDateFormat("dd.MM.yyyy HH:mm:ss", java.util.Locale.getDefault())
        return sdf.format(java.util.Date(ts))
    }

    private fun dateTime(ts: Long): String {
        val sdf = java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.getDefault())
        return sdf.format(java.util.Date(ts))
    }

    private fun confirmClearHistory() {
        AlertDialog.Builder(activity)
            .setMessage(R.string.history_clear_confirm)
            .setPositiveButton(R.string.ok) { _, _ ->
                // Запрос и перезагрузка — во ViewModel (MVVM)
                viewModel.clearHistory()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun textWatcher(onChange: () -> Unit): android.text.TextWatcher =
        object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) = onChange()
        }
}

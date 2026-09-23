package com.ozyab.smsforwarder.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.ozyab.smsforwarder.R
import com.ozyab.smsforwarder.telegram.Channel
import com.ozyab.smsforwarder.telegram.ChannelStore
import com.ozyab.smsforwarder.util.LogStore
import java.util.UUID

/**
 * Панель «Каналы отправки»: список каналов, переключение, порядок, диалог
 * добавления/редактирования прокси. Выделена из MainActivity (декомпозиция,
 * #143): Activity остаётся тонкой склейкой панелей.
 *
 * Состояния не держит: читает ChannelStore и перерисовывается по требованию
 * (onResume / после правок) — как и до выделения.
 */
class ChannelsPanel(
    private val activity: androidx.appcompat.app.AppCompatActivity,
    private val container: LinearLayout,
) {
    fun renderChannels() {
        container.removeAllViews()
        val channels = ChannelStore.all()
        if (channels.size == 1) {
            val empty = TextView(activity).apply {
                text = activity.getString(R.string.channels_no_proxies)
                setTextColor(androidx.core.content.ContextCompat.getColor(activity, android.R.color.darker_gray))
                textSize = 14f
                setPadding(4, 8, 4, 8)
            }
            container.addView(empty)
        }
        for ((i, ch) in channels.withIndex()) {
            container.addView(buildChannelRow(ch, i, channels.size))
        }
    }

    /**
     * Строит строку канала (имя, детали, switch, up/down/edit/delete для прокси).
     * Порядок каналов = приоритет каскадной отправки, поэтому прокси можно
     * менять местами кнопками «выше/ниже».
     */
    fun buildChannelRow(ch: Channel, index: Int, total: Int): View {
        val row = LayoutInflater.from(activity).inflate(R.layout.item_channel, container, false)
        val sw = row.findViewById<SwitchMaterial>(R.id.ch_switch)
        val name = row.findViewById<TextView>(R.id.ch_name)
        val detail = row.findViewById<TextView>(R.id.ch_detail)
        val up = row.findViewById<ImageButton>(R.id.ch_up)
        val down = row.findViewById<ImageButton>(R.id.ch_down)
        val edit = row.findViewById<ImageButton>(R.id.ch_edit)
        val del = row.findViewById<ImageButton>(R.id.ch_delete)

        name.text = if (ch.isDirect) "🔒 ${activity.getString(R.string.channels_direct)}" else "${ch.host}:${ch.port}"
        detail.text = when {
            ch.isDirect -> activity.getString(R.string.channel_direct_detail)
            else -> ch.type
        }
        if (ch.isDirect) {
            // direct всегда включён и не изменяется (порядок двигается автоматически)
            sw.isChecked = true
            sw.isEnabled = false
        } else {
            sw.isChecked = ch.enabled
            sw.setOnCheckedChangeListener { _, checked ->
                ChannelStore.upsert(ch.copy(enabled = checked))
            }
        }
        // Кнопки порядка — для всех каналов: порядок динамический, «Без прокси»
        // тоже двигается (promote/demote по результату отправки)
        setEnabled(up, index > 0)
        setEnabled(down, index < total - 1)
        up.setOnClickListener {
            ChannelStore.move(ch.id, -1)
            renderChannels()
        }
        down.setOnClickListener {
            ChannelStore.move(ch.id, +1)
            renderChannels()
        }
        edit.setOnClickListener { showProxyDialog(ch) }
        if (ch.isDirect) {
            // edit для direct бессмыслен (нет настроек прокси)
            edit.visibility = View.GONE
        }
        del.setOnClickListener { confirmDelete(ch) }
        return row
    }

    fun setEnabled(btn: ImageButton, enabled: Boolean) {
        btn.isEnabled = enabled
        btn.alpha = if (enabled) 1f else 0.3f
    }

    fun confirmDelete(ch: Channel) {
        // «Без прокси» удалить нельзя (должен остаться хотя бы один канал) — no-op
        if (ch.isDirect) return
        AlertDialog.Builder(activity)
            .setTitle(R.string.channels_proxy_delete)
            .setMessage(activity.getString(R.string.channels_proxy_delete_confirm, ch.name))
            .setPositiveButton(R.string.ok) { _, _ ->
                ChannelStore.remove(ch.id)
                renderChannels()
                LogStore.info("Канал «${ch.name}» удалён")
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Диалог добавления/редактирования прокси-канала. */
    fun showProxyDialog(existing: Channel?) {
        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 24, 60, 0)
        }

        // Тип: выпадающий список — Без прокси / HTTP / SOCKS5
        val types = arrayOf(
            activity.getString(R.string.proxy_type_none),
            activity.getString(R.string.proxy_type_http),
            activity.getString(R.string.proxy_type_socks5)
        )
        val typeSpinner = Spinner(activity).apply {
            adapter = ArrayAdapter(
                activity,
                android.R.layout.simple_list_item_1,
                types
            )
            setSelection(
                when (existing?.type) {
                    Channel.TYPE_SOCKS5 -> 2
                    Channel.TYPE_HTTP -> 1
                    else -> 0 // Channel.TYPE_DIRECT
                }
            )
        }
        layout.addView(TextView(activity).apply { setPadding(0, 8, 0, 4); text = activity.getString(R.string.pref_proxy_type) })
        layout.addView(typeSpinner)

        fun field(hint: String, value: String, singleLine: Boolean = true) =
            EditText(activity).apply { this.hint = hint; setText(value); isSingleLine = singleLine }

        val etHost = field(activity.getString(R.string.pref_proxy_host), existing?.host ?: "")
        val etPort = field(activity.getString(R.string.pref_proxy_port), existing?.port?.toString() ?: "")
        val etUser = field(activity.getString(R.string.pref_proxy_user), existing?.user ?: "")
        etPort.inputType = android.text.InputType.TYPE_CLASS_NUMBER

        // Пароль — маскированный, с переключателем видимости (глазик)
        val passLayout = TextInputLayout(activity).apply {
            hint = activity.getString(R.string.pref_proxy_pass)
            // END_ICON_PASSWORD_TOGGLE включает глазик; deprecated
            // isPasswordVisibilityToggleEnabled больше не используется
            endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
        }
        val etPass = TextInputEditText(activity).apply {
            setText(existing?.pass ?: "")
            isSingleLine = true
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD or
                android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        passLayout.addView(etPass)

        // Контейнер для полей прокси (скрываем для "Без прокси")
        val proxyFields = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        for (v in listOf(etHost, etPort, etUser, passLayout)) proxyFields.addView(v)
        layout.addView(proxyFields)

        // Показываем/скрываем поля в зависимости от выбранного типа
        fun updateFieldsVisibility() {
            val isDirect = typeSpinner.selectedItemPosition == 0
            proxyFields.visibility = if (isDirect) View.GONE else View.VISIBLE
        }
        updateFieldsVisibility()
        typeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                updateFieldsVisibility()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        AlertDialog.Builder(activity)
            .setTitle(if (existing == null) R.string.channels_add_proxy else R.string.channels_proxy_edit)
            .setView(layout)
            .setPositiveButton(R.string.save) { _, _ ->
                val selectedType = typeSpinner.selectedItemPosition
                if (selectedType == 0) {
                    // «Без прокси» всегда есть отдельным каналом: выбор его при
                    // редактировании прокси означает удаление — но с явным
                    // подтверждением, а не молча (N7, аудит-2)
                    if (existing != null) {
                        AlertDialog.Builder(activity)
                            .setMessage(R.string.channels_proxy_delete_confirm)
                            .setPositiveButton(R.string.ok) { _, _ ->
                                ChannelStore.remove(existing.id)
                                renderChannels()
                                LogStore.info("Канал «${existing.name}» удалён")
                            }
                            .setNegativeButton(R.string.cancel, null)
                            .show()
                    } else {
                        Toast.makeText(activity, R.string.channel_direct_exists, Toast.LENGTH_SHORT).show()
                    }
                } else {
                    // HTTP или SOCKS5
                    val type = if (selectedType == 2) Channel.TYPE_SOCKS5 else Channel.TYPE_HTTP
                    val port = etPort.text.toString().trim().toIntOrNull() ?: 0
                    // N9 (аудит-2): валидный диапазон TCP-портов 1..65535
                    if (etHost.text.isNullOrBlank() || port !in 1..65535) {
                        Toast.makeText(activity, R.string.proxy_need_host_port, Toast.LENGTH_LONG).show()
                        return@setPositiveButton
                    }
                    val host = etHost.text.toString().trim()
                    val ch = Channel(
                        id = existing?.id ?: UUID.randomUUID().toString(),
                        type = type,
                        name = "$host:$port",
                        host = host,
                        port = port,
                        user = etUser.text.toString().trim(),
                        pass = etPass.text.toString(),
                        // N7: правка прокси не должна включать выключенный канал
                        enabled = existing?.enabled ?: true,
                    )
                    ChannelStore.upsert(ch)
                    renderChannels()
                    LogStore.info("Канал «${ch.name}» сохранён")
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}

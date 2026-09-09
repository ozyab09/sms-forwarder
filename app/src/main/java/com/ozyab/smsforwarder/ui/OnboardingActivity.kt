package com.ozyab.smsforwarder.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.ozyab.smsforwarder.R
import com.ozyab.smsforwarder.telegram.TelegramClient
import com.ozyab.smsforwarder.util.Prefs
import com.ozyab.smsforwarder.util.ThemeManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Пошаговый мастер при первом запуске:
 *  0. Приветствие
 *  1. Токен бота
 *  2. Chat ID
 *  3. Готово
 *
 * При последующих запусках пропускается (Prefs.onboardingComplete).
 * Свайп выключен — навигация только по кнопкам (защита от случайного пропуска шага).
 */
class OnboardingActivity : AppCompatActivity() {

    private lateinit var viewPager: ViewPager2
    private lateinit var btnNext: MaterialButton
    private lateinit var btnBack: MaterialButton
    private lateinit var dots: Array<View>

    // Ссылки на поля шагов (заполняются при привязке ViewHolder'а)
    private var etToken: TextInputEditText? = null
    private var etChatId: TextInputEditText? = null

    private val scope = CoroutineScope(Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        // Тема применяется и к онбордингу (пользователь мог выбрать её заранее)
        ThemeManager.apply(this)

        if (Prefs.onboardingComplete) {
            goToMain()
            return
        }

        setContentView(R.layout.activity_onboarding)

        viewPager = findViewById(R.id.view_pager)
        btnNext = findViewById(R.id.btn_onboarding_next)
        btnBack = findViewById(R.id.btn_onboarding_back)
        dots = arrayOf(
            findViewById(R.id.dot_0),
            findViewById(R.id.dot_1),
            findViewById(R.id.dot_2),
            findViewById(R.id.dot_3),
        )

        val adapter = OnboardingAdapter()
        viewPager.adapter = adapter
        viewPager.isUserInputEnabled = false

        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateUI(position)
            }
        })

        btnNext.setOnClickListener { onNextClicked() }
        btnBack.setOnClickListener {
            val pos = viewPager.currentItem
            if (pos > 0) viewPager.currentItem = pos - 1
        }

        updateUI(0)
    }

    private fun onNextClicked() {
        val pos = viewPager.currentItem
        when (pos) {
            STEP_TOKEN -> {
                val token = etToken?.text?.toString()?.trim().orEmpty()
                if (token.isBlank()) {
                    Toast.makeText(this, R.string.toast_enter_token, Toast.LENGTH_SHORT).show()
                    return
                }
                Prefs.botToken = token
            }
            STEP_CHAT_ID -> {
                val chatId = etChatId?.text?.toString()?.trim().orEmpty()
                if (chatId.isNotBlank()) Prefs.chatId = chatId
            }
            STEP_DONE -> {
                Prefs.onboardingComplete = true
                goToMain()
                return
            }
        }
        if (pos < STEP_DONE) {
            viewPager.currentItem = pos + 1
        }
    }

    private fun updateUI(pos: Int) {
        // Кнопки
        btnBack.visibility = if (pos == STEP_WELCOME) View.INVISIBLE else View.VISIBLE
        btnNext.text = when (pos) {
            STEP_DONE -> getString(R.string.btn_start)
            else -> getString(R.string.onboarding_next)
        }

        // Точки-индикатор
        dots.forEachIndexed { i, dot ->
            dot.background.setTint(
                ContextCompat.getColor(
                    this,
                    if (i == pos) R.color.onboarding_active_dot
                    else R.color.onboarding_inactive_dot
                )
            )
        }
    }

    private fun goToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    companion object {
        const val STEP_WELCOME = 0
        const val STEP_TOKEN = 1
        const val STEP_CHAT_ID = 2
        const val STEP_DONE = 3
    }

    // ===================== Adapter =====================

    inner class OnboardingAdapter : RecyclerView.Adapter<OnboardingAdapter.VH>() {

        private val layouts = intArrayOf(
            R.layout.onboarding_step_welcome,
            R.layout.onboarding_step_token,
            R.layout.onboarding_step_chatid,
            R.layout.onboarding_step_done,
        )

        inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(viewType, parent, false))

        override fun getItemCount() = layouts.size

        override fun getItemViewType(position: Int) = layouts[position]

        override fun onBindViewHolder(holder: VH, position: Int) {
            when (position) {
                STEP_WELCOME -> { /* статичный layout */ }
                STEP_TOKEN -> {
                    val et = holder.itemView.findViewById<TextInputEditText>(R.id.et_onb_token)
                    et?.setText(Prefs.botToken)
                    etToken = et
                }
                STEP_CHAT_ID -> {
                    val btnResolve = holder.itemView.findViewById<MaterialButton>(R.id.btn_onb_resolve)
                    val etChatId = holder.itemView.findViewById<TextInputEditText>(R.id.et_onb_chat_id)
                    val tvStatus = holder.itemView.findViewById<TextView>(R.id.tv_onb_chat_status)

                    etChatId?.setText(Prefs.chatId)
                    this@OnboardingActivity.etChatId = etChatId

                    btnResolve?.setOnClickListener {
                        if (Prefs.botToken.isBlank()) {
                            Toast.makeText(this@OnboardingActivity, R.string.toast_enter_token_first, Toast.LENGTH_SHORT).show()
                            return@setOnClickListener
                        }
                        btnResolve.isEnabled = false
                        btnResolve.text = getString(R.string.onboarding_resolving)
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                TelegramClient.resolveChatId()
                            }
                            btnResolve.isEnabled = true
                            btnResolve.text = getString(R.string.onboarding_resolve)
                            when (result) {
                                is TelegramClient.Result.Ok -> {
                                    etChatId?.setText(result.messageId.toString())
                                    tvStatus?.text = getString(R.string.onboarding_chat_ok)
                                }
                                is TelegramClient.Result.Err -> {
                                    tvStatus?.text = getString(R.string.onboarding_chat_try_again)
                                }
                            }
                        }
                    }
                }
                STEP_DONE -> { /* статичный layout */ }
            }
        }
    }
}

package com.ozyab.smsforwarder.update

import com.ozyab.smsforwarder.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject

/**
 * Проверка обновлений через GitHub Releases (публичный API, без токена).
 *
 * Репозиторий: ozyab09/sms-forwarder.
 * Сравнивает тег последнего релиза (vX.Y.Z) с текущей версией приложения.
 */
object UpdateChecker {

    private const val REPO = "ozyab09/sms-forwarder"
    private const val API = "https://api.github.com/repos/$REPO/releases/latest"

    data class UpdateInfo(
        val latestVersion: String,   // "0.2.1"
        val apkUrl: String,          // прямая ссылка на APK
        val releaseUrl: String,      // страница релиза
        val notes: String,           // описание релиза
    )

    /**
     * Результат проверки обновлений.
     *
     * [Unavailable] — API не ответил (нет сети / rate-limit GitHub): это НЕ
     * «обновлений нет», повторить стоит в следующий раз.
     */
    sealed class CheckResult {
        data class Update(val info: UpdateInfo) : CheckResult()
        object UpToDate : CheckResult()
        object Unavailable : CheckResult()
    }

    /** Проверяет наличие новой версии на GitHub. */
    suspend fun check(): CheckResult = withContext(Dispatchers.IO) {
        val client = okhttp3.OkHttpClient.Builder()
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        try {
            val req = Request.Builder()
                .url(API)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "SMSForwarder")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext CheckResult.Unavailable
                val json = JSONObject(resp.body?.string().orEmpty())
                val tag = json.optString("tag_name", "").removePrefix("v")
                val current = BuildConfig.VERSION_NAME.removePrefix("v")

                // Новая версия? (простое сравнение major.minor.patch)
                if (compareVersions(tag, current) <= 0) return@withContext CheckResult.UpToDate

                val assets = json.optJSONArray("assets") ?: return@withContext CheckResult.Unavailable
                var apkUrl: String? = null
                for (i in 0 until assets.length()) {
                    val a = assets.optJSONObject(i)
                    val name = a?.optString("name", "")
                    if (name?.endsWith(".apk") == true) {
                        apkUrl = a.optString("browser_download_url")
                        break
                    }
                }
                if (apkUrl == null) return@withContext CheckResult.Unavailable

                CheckResult.Update(
                    UpdateInfo(
                        latestVersion = tag,
                        apkUrl = apkUrl,
                        releaseUrl = json.optString("html_url", ""),
                        notes = json.optString("body", ""),
                    )
                )
            }
        } catch (e: Exception) {
            CheckResult.Unavailable // нет сети / GitHub недоступен — не мешаем запуску
        } finally {
            // Не даём утечь thread-pool OkHttp при каждом запуске приложения.
            client.dispatcher.executorService.shutdown()
        }
    }

    /** Сравнение семверсий. >0 если a новее b. */
    fun compareVersions(a: String, b: String): Int {
        val pa = a.split('.').mapNotNull { it.toIntOrNull() }
        val pb = b.split('.').mapNotNull { it.toIntOrNull() }
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return x - y
        }
        return 0
    }
}
// Версии плагинов заданы в gradle/libs.versions.toml (alias-подключение в app).
// Здесь ничего не объявляем — pluginManagement из settings.gradle.kts резолвит версии.
plugins {
    id("com.android.application") apply false
    id("org.jetbrains.kotlin.android") apply false
}
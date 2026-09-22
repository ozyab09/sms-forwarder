import java.util.Base64

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
}

// SemVer полностью производный от тегов (issue #128):
//  1. CI-релиз (запуск на теге): версия = GITHUB_REF_NAME (vX.Y.Z).
//  2. Локально/PR: последний тег в git-истории + patch+1 (dev-версия).
//  3. Fallback (нет git, например zip-архив): 0.0.0.
// Никаких статичных versionMajor/Minor/Patch в libs.versions.toml — тег
// создаётся CI при мерже в main, номер версии нигде не правится руками.
val semverRegex = Regex("^v?(\\d+)\\.(\\d+)\\.(\\d+)$")

fun parseSemver(tag: String): Triple<Int, Int, Int>? =
    semverRegex.find(tag.trim())?.destructured?.let { (a, b, c) -> Triple(a.toInt(), b.toInt(), c.toInt()) }

fun lastGitTag(): String? = runCatching {
    val out = java.io.ByteArrayOutputStream()
    providers.exec {
        commandLine("git", "describe", "--tags", "--abbrev=0")
    }.standardOutput.asText.get().trim().takeIf { it.isNotEmpty() }
}.getOrNull()

val (major, minor, patch) = System.getenv("GITHUB_REF_NAME")
    ?.takeIf { parseSemver(it) != null }
    ?.let { parseSemver(it)!! }
    ?: lastGitTag()?.let { parseSemver(it) }?.let { (a, b, c) -> Triple(a, b, c + 1) }
    ?: Triple(0, 0, 0)

android {
    namespace = "com.ozyab.smsforwarder"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ozyab.smsforwarder"
        minSdk = 29
        targetSdk = 34
        versionCode = major * 10000 + minor * 100 + patch
        versionName = "$major.$minor.$patch"
    }

    androidResources {
        // Вместо устаревшего resConfigs (AGP 8.9+)
        localeFilters += listOf("ru", "en")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Подпись из CI-переменных (релизные сборки — в GitHub Actions)
            signingConfig = if (System.getenv("KEYSTORE_BASE64") != null) {
                signingConfigs.create("ci") {
                    val keystoreFile = File(layout.buildDirectory.get().asFile, "ci-keystore.jks")
                    if (!keystoreFile.exists()) {
                        keystoreFile.parentFile?.mkdirs()
                        keystoreFile.writeBytes(
                            Base64.getDecoder().decode(System.getenv("KEYSTORE_BASE64"))
                        )
                    }
                    storeFile = keystoreFile
                    storePassword = System.getenv("KEYSTORE_PASSWORD")
                    keyAlias = System.getenv("KEY_ALIAS")
                    keyPassword = System.getenv("KEY_PASSWORD")
                }
            } else {
                null
            }
        }
    }

    testOptions {
        unitTests {
            // Robolectric: читаем настоящий AndroidManifest и ресурсы приложения,
            // иначе тесты видят дефолтный манифест (org.robolectric.default)
            // и Activity не резолвится в ActivityScenario.
            isIncludeAndroidResources = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    // Облегчение дистрибутива: только реальные ABI телефонов.
    // В release оба ABI собираются отдельными APK (app-arm64-v8a-release.apk, app-armeabi-v7a-release.apk).
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = false
        }
    }

    // Локально нет Android SDK — сборка только в CI. Выключаем локальную проверку AGP.
    // (Комментарий: чтобы собрать локально, нужен ANDROID_HOME со SDK 34.)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.viewpager2)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.timber)
    implementation(libs.androidx.datastore.preferences)

    // Тесты: JUnit + Room in-memory (Robolectric для Context в unit-тестах)
    testImplementation(libs.junit)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}


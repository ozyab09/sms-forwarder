import java.util.Base64

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.kapt)
}

kapt {
    correctErrorTypes = true
}

// SemVer из CI-тега (GITHUB_REF_NAME) или из gradle/libs.versions.toml (локально/debug)
val ciTag: String? = System.getenv("GITHUB_REF_NAME")?.takeIf { it.startsWith("v") }
val semverRegex = Regex("^v(\\d+)\\.(\\d+)\\.(\\d+)$")
val (major, minor, patch) = ciTag?.let { m ->
    semverRegex.find(m)?.destructured?.let { (a, b, c) -> Triple(a.toInt(), b.toInt(), c.toInt()) }
} ?: Triple(
    libs.versions.versionMajor.get().toInt(),
    libs.versions.versionMinor.get().toInt(),
    libs.versions.versionPatch.get().toInt(),
)

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
            // Подпись из CI-переменных (релизные сборки только в GitLab CI)
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
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.viewpager2)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    kapt(libs.androidx.room.compiler)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.timber)
    implementation(libs.androidx.datastore.preferences)

    // Тесты: JUnit + Room in-memory (Robolectric для Context в unit-тестах)
    testImplementation(libs.junit)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.robolectric)
}
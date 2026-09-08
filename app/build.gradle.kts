import java.util.Base64

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// SemVer из CI-тега: GitLab (CI_COMMIT_TAG) или GitHub Actions (GITHUB_REF_NAME)
val ciTag: String? = System.getenv("CI_COMMIT_TAG")
    ?: System.getenv("GITHUB_REF_NAME")?.takeIf { it.startsWith("v") }
val semverRegex = Regex("^v(\\d+)\\.(\\d+)\\.(\\d+)$")
val (major, minor, patch) = ciTag?.let { m ->
    semverRegex.find(m)?.destructured?.let { (a, b, c) -> Triple(a.toInt(), b.toInt(), c.toInt()) }
} ?: Triple(
    (project.findProperty("VERSION_MAJOR") as String? ?: "0").toInt(),
    (project.findProperty("VERSION_MINOR") as String? ?: "1").toInt(),
    (project.findProperty("VERSION_PATCH") as String? ?: "0").toInt(),
)

android {
    namespace = "com.ozyab.smsforwarder"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ozyab.smsforwarder"
        minSdk = 26
        targetSdk = 34
        versionCode = major * 10000 + minor * 100 + patch
        versionName = "$major.$minor.$patch"
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
                    val keystoreFile = File(buildDir, "ci-keystore.jks")
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
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    // TDLib (MTProto) — нативные .so идут в AAR (libtdjni.so, arm64-v8a/armeabi-v7a/x86/x86_64)
    implementation(libs.tdlib)

    testImplementation(libs.junit)
}
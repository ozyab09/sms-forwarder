# Keep line numbers for stack traces
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# TDLib — нативная библиотека (JNI), обфускация сломает вызовы
-keep class org.drinkless.tdlib.** { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# EncryptedSharedPreferences
-keep class androidx.security.crypto.** { *; }
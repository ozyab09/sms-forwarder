# Keep line numbers for stack traces
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# TDLib (добавится на этапе 4) — нативная библиотека, JNI
# -keep class org.drinkless.tdlib.** { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# EncryptedSharedPreferences
-keep class androidx.security.crypto.** { *; }
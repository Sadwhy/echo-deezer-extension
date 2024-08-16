# Preserve the main application package
-keep class dev.brahmkshatriya.echo.** { *; }

# Preserve classes that implement interfaces from common.clients and settings packages
-keep class dev.brahmkshatriya.echo.common.clients.** { *; }
-keep class dev.brahmkshatriya.echo.common.settings.** { *; }

# Keep models since they might be serialized/deserialized or used by reflection
-keep class dev.brahmkshatriya.echo.common.models.** { *; }

# Preserve Kotlin Coroutine related classes
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**

# Keep JSON serialization/deserialization classes
-keep class kotlinx.serialization.json.** { *; }
-dontwarn kotlinx.serialization.json.**

# OkHttpClient related classes
-keep class okhttp3.** { *; }
-dontwarn okhttp3.**

# GSON (if used in addition to kotlinx serialization, or if you plan to add it)
-keep class com.google.gson.** { *; }
-dontwarn com.google.gson.**

# Avoid obfuscating classes that might be accessed via reflection
-keepclassmembers class * {
    @kotlinx.serialization.Serializable <fields>;
    <methods>;
}

# Keep your custom utility classes and objects
-keep class dev.brahmkshatriya.echo.extension.Utils { *; }
-keep class dev.brahmkshatriya.echo.extension.DeezerUtils { *; }
-keep class dev.brahmkshatriya.echo.extension.DeezerCredentialsHolder { *; }
-keep class dev.brahmkshatriya.echo.extension.DeezerApi { *; }
-keep class dev.brahmkshatriya.echo.extension.DeezerCredentials { *; }

# Keep classes that extend or implement CoroutineScope
-keep class * implements kotlinx.coroutines.CoroutineScope { *; }

# Preserve classes that have specific method signatures (e.g., native methods, methods with certain annotations)
-keepclasseswithmembers class * {
    native <methods>;
}

# Keep classes with Companion objects
-keep class * {
    companion object;
}

# Prevent warnings related to missing classes in external libraries
-dontwarn okhttp3.**
-dontwarn kotlinx.serialization.**
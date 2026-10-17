# WorthAbite release shrinking rules.

# API models are created by kotlinx.serialization and passed through Retrofit; keep them whole.
-keep class app.foodfeed.data.** { *; }
-keepattributes Signature, InnerClasses, EnclosingMethod, *Annotation*, Exceptions

# Retrofit service methods are suspend functions: keep the generic types R8 would strip.
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
-if interface * { @retrofit2.http.* <methods>; }
-keep,allowobfuscation interface <1>

# kotlinx.serialization: keep generated serializers and companions.
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class * { kotlinx.serialization.KSerializer serializer(...); }
-keepclassmembers @kotlinx.serialization.Serializable class * {
    static ** Companion;
    *** Companion;
}

# MapLibre loads some classes from native code.
-keep class org.maplibre.android.** { *; }
-keep class org.maplibre.geojson.** { *; }
-dontwarn org.maplibre.**

-dontwarn org.slf4j.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

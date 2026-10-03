# BiliMusic 混淆规则（release 默认未开启 minify，这里保留规则以便随时打开）
-keepattributes *Annotation*, InnerClasses, Signature, Exceptions

# kotlinx.serialization
-keepattributes RuntimeVisibleAnnotations, AnnotationDefault
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.bilimusic.app.**$$serializer { *; }
-keepclassmembers class com.bilimusic.app.** {
    *** Companion;
}
-keepclasseswithmembers class com.bilimusic.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Retrofit / OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn retrofit2.**
-keepattributes Signature
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}

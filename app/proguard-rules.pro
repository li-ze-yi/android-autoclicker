# 项目混淆规则占位文件。
# 当前 release 构建未开启 minify（isMinifyEnabled = false），此文件用于后续扩展。

# ---- kotlinx.serialization 保留规则 ----
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-keep,includedescriptorclasses class com.autoclicker.**$$serializer { *; }
-keepclassmembers class com.autoclicker.** {
    *** Companion;
}
-keepclasseswithmembers class com.autoclicker.** {
    kotlinx.serialization.KSerializer serializer(...);
}
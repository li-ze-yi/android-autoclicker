// 根工程构建脚本：仅声明插件别名，具体的 apply 在各模块内完成。
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
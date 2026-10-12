plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

/**
 * 云端构建号：CI 通过 -PbuildNumber=${{ github.run_number }} 传入，本地无参构建为 0。
 * 用于版本名展示与「检查更新」的新旧比较。
 */
val buildNumber = (findProperty("buildNumber") as String?)?.toIntOrNull() ?: 0

android {
    namespace = "com.autoclicker"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.autoclicker"
        minSdk = 26
        targetSdk = 35
        // versionCode 固定：分发靠直接覆盖安装，避免本地无参构建版本号变小导致覆盖被拒。
        versionCode = 2
        // 版本名带上构建号（如 2.0.78），一眼看出新旧。
        versionName = if (buildNumber > 0) "2.0.$buildNumber" else "2.0"
        buildConfigField("int", "BUILD_NUMBER", buildNumber.toString())

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    /**
     * 固定签名：仓库内置 keystore（app/keystore/debug.p12），debug 与 release 共用，
     * 保证每次构建（本地与 CI）签名一致，可直接覆盖安装。
     * 公开调试密钥，仅用于个人调试分发，不适用于正式上架。
     */
    signingConfigs {
        create("fixed") {
            storeFile = file("keystore/debug.p12")
            storePassword = "android"
            keyAlias = "autoclicker"
            keyPassword = "android"
            storeType = "PKCS12"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("fixed")
        }
        release {
            signingConfig = signingConfigs.getByName("fixed")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    debugImplementation(libs.androidx.ui.tooling)
}

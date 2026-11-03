import java.time.ZoneOffset
import java.time.ZonedDateTime

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

/**
 * versionCode 取构建时间（UTC+8），保证每次打包安装都会被系统当成新版本：
 * 启动器缓存（图标、通知图标）按「包名 + versionCode」判断是否刷新。
 * 编码： (年-2026)*1e8 + MMDDhhmm ，例如 2026-11-01 23:45 -> 11012345。
 */
fun buildMinuteVersionCode(): Int {
    val now = ZonedDateTime.now(ZoneOffset.ofHours(8))
    return (now.year - 2026) * 100_000_000 +
        now.monthValue * 1_000_000 +
        now.dayOfMonth * 10_000 +
        now.hour * 100 +
        now.minute
}

android {
    namespace = "com.dshm.bot"
    compileSdk = 36

    defaultConfig {
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
        applicationId = "com.dshm.bot"
        minSdk = 26
        targetSdk = 28
        versionCode = buildMinuteVersionCode()
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += setOf(
                "META-INF/LICENSE.md",
                "META-INF/LICENSE-notice.md",
                "META-INF/*.version",
                "kotlin/**",
                "DebugProbesKt.bin",
            )
        }
    }

    lint {
        abortOnError = true
        checkDependencies = true
        warningsAsErrors = false
        // 版本更新提示不是代码缺陷；依赖版本在 gradle/libs.versions.toml 里手动钉住，
        // 升级是独立任务（见 docs/roadmap.md），不阻塞每次构建。
        disable += setOf("GradleDependency", "NewerVersionAvailable", "ExpiredTargetSdkVersion")
    }

    testOptions {
        unitTests.isIncludeAndroidResources = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.commons.compress)
    implementation(libs.xz)
    implementation(libs.coil.compose)
    implementation(libs.security.crypto)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.foundation)
    implementation(libs.compose.animation)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)

    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    // 单元测试用 org.json（Android 的 android.jar 里是 stub）；从本地 jar 提供，离线可用。
    testImplementation(files("libs/json-20231013.jar"))

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.compose.ui.test.junit4)
}






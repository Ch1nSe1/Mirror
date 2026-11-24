plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "memore.app"
    compileSdk = 35

    signingConfigs {
        // 公版 release 签名：keystore 放在 .signing/（已 gitignore，绝不入库）。
        // 密码从环境变量 MEMORE_KEYSTORE_PASS 读取（不硬编码进 git）。
        create("release") {
            storeFile = file("${rootDir}/.signing/public.keystore")
            storePassword = System.getenv("MEMORE_KEYSTORE_PASS") ?: ""
            keyAlias = "pub"
            keyPassword = System.getenv("MEMORE_KEYSTORE_PASS") ?: ""
        }

        // 统一 debug 签名：无论哪个工具（OpenCode/Codex/Android Studio）构建都强制使用同一个
        // ~/.android/debug.keystore，避免各自生成不同 keystore 导致 INSTALL_FAILED_UPDATE_INCOMPATIBLE
        getByName("debug") {
            storeFile = file("${System.getProperty("user.home")}/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    defaultConfig {
        applicationId = "app.memore"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "1.3.0"

        // 只留 arm64：ONNX Runtime 的 native 库每个 ABI 约 18MB，
        // 全打进去会白涨 60MB（这台机器是 arm64，x86 只用模拟器）。
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        encoding = "UTF-8"
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-Xutf8")
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    // 中文分词（2026-11-13 用户授权新增技术栈）：关键词不再用字符滑窗，改用真词
    implementation("com.huaban:jieba-analysis:1.0.2")
    implementation("androidx.media:media:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.foundation:foundation")

    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    // 2026-11-19：自带 SQLite（含 FTS5 + trigram 分词器）。
    // 框架 SQLite 在 minSdk 26 上不保证有 FTS5，中文又没有可用分词器；
    // 这个库把 SQLite 随 APK 打包，Room 通过 openHelperFactory 接管（同一个 .db 文件）。
    implementation("com.github.requery:sqlite-android:3.49.0")

    implementation("io.coil-kt:coil-compose:2.6.0")
    // GIF / 动图 WebP 解码。Coil 2 不会自动注册扩展解码器，只有显式用到的地方才生效
    // （目前唯一使用点是图文集放大查看器，见 utils/ImageDecoders.kt）。
    implementation("io.coil-kt:coil-gif:2.6.0")

    implementation("com.google.code.gson:gson:2.11.0")
    // 端侧嵌入（向量检索）：ONNX Runtime Android，模型在 assets/models/
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")

    implementation("androidx.work:work-runtime-ktx:2.9.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    // JVM 单测用桌面版 ORT（Android 包在单测环境没有 native 库），仅测试期存在
    testImplementation("com.microsoft.onnxruntime:onnxruntime:1.20.0")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    testImplementation("androidx.room:room-testing:$roomVersion")
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test:core-ktx:1.6.1")
}

// 2026-11-23：作者自用提示音放在 src/debug/assets/sounds/（不进公开 release）。
// 这样 debug 仍有声音，release 包天然不含 assets/sounds/**。


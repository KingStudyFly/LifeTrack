import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localProperties.load(localPropertiesFile.inputStream())
}

android {
    namespace = "com.example.lifetrack"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    // 安全改进：口令从 local.properties 读取，缺失时回退到 debug 签名，避免明文口令进入版本库。
    // 启用正式 release 签名需在 local.properties 中配置 KEYSTORE_PASSWORD 和 KEY_PASSWORD。
    val keystorePassword = localProperties.getProperty("KEYSTORE_PASSWORD")
    val keyPassword = localProperties.getProperty("KEY_PASSWORD")
    if (keystorePassword != null && keyPassword != null) {
        signingConfigs {
            create("release") {
                storeFile = rootProject.file("lifetrack-release.jks")
                storePassword = keystorePassword
                keyAlias = "Wizard091"
                this.keyPassword = keyPassword
            }
        }
    }

    defaultConfig {
        applicationId = "com.example.lifetrack"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 安全改进：AI 服务 API Key 从 local.properties 读取（不入库），缺失时为空字符串。
        buildConfigField("String", "DEEPSEEK_API_KEY", "\"${localProperties.getProperty("DEEPSEEK_API_KEY") ?: ""}\"")
        buildConfigField("String", "DOUBAO_API_KEY", "\"${localProperties.getProperty("DOUBAO_API_KEY") ?: ""}\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.activity.ktx)
    implementation(libs.appcompat)
    implementation(libs.constraintlayout)
    implementation(libs.material)
    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)
    implementation(libs.sqlcipher)
    implementation(libs.security.crypto)
    implementation(libs.androidx.sqlite)
    implementation(libs.swiperefreshlayout)
    implementation(libs.blurview)
    implementation(libs.recyclerview)
    testImplementation(libs.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.ext.junit)
}

// 修复第三方 .so 的 ELF LOAD 段对齐，兼容 16 KB 页面设备
tasks.register("alignElf16kb") {
    group = "build"
    description = "Align ELF LOAD segments to 16KB page size for Android 15+ compatibility"
    doLast {
        val jniDir = file("${layout.buildDirectory.get()}/intermediates/merged_native_libs")
        if (!jniDir.exists()) {
            logger.lifecycle("alignElf16kb: merged_native_libs not found, skipping")
            return@doLast
        }
        val script = rootProject.file("align_elf_16kb.py")
        val process = ProcessBuilder("python", script.absolutePath, jniDir.absolutePath)
            .redirectErrorStream(true)
            .start()
        process.inputStream.bufferedReader().forEachLine { logger.lifecycle(it) }
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            throw GradleException("alignElf16kb failed with exit code $exitCode")
        }
    }
}

tasks.matching { it.name.startsWith("merge") && it.name.endsWith("NativeLibs") }.configureEach {
    finalizedBy("alignElf16kb")
}

tasks.matching { it.name.startsWith("package") && !it.name.contains("UnitTest", ignoreCase = true) }.configureEach {
    dependsOn("alignElf16kb")
}
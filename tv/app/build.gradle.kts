import java.util.Properties

plugins {
    id("com.android.application")
}

// One version for the whole product (repo-root VERSION, e.g. "0.3.0" -> versionCode 300).
val jinkyVersion = rootProject.file("../VERSION").readText().trim()
val jinkyVersionCode = jinkyVersion.split('.').map { it.toInt() }.let { (a, b, c) -> a * 10000 + b * 100 + c }

// Release signing key lives outside the repo (see docs/RELEASING.md). Updates only install over an
// app signed with the same key, so this file must be kept and backed up.
val signingProps = Properties().apply {
    val f = file(System.getenv("JINKY_KEYSTORE_PROPERTIES")
        ?: (System.getProperty("user.home") + "/.jinky-signing/keystore.properties"))
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.tclstream.tv"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.jinky.tv"
        minSdk = 25 // Fire OS 6 (Fire TV Stick 4K 1st gen) and newer
        targetSdk = 35
        versionCode = jinkyVersionCode
        versionName = jinkyVersion

        ndk {
            // TCL 55P7K and Fire TV Sticks run ARM; x86_64 is only for the emulator.
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86_64", "x86")  // x86: the Google TV emulator
        }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_shared")
            }
        }
    }

    signingConfigs {
        create("jinky") {
            if (signingProps.isNotEmpty()) {
                storeFile = file(signingProps.getProperty("storeFile"))
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = signingProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (signingProps.isNotEmpty()) signingConfigs.getByName("jinky") else signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

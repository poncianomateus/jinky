plugins {
    id("com.android.application")
}

android {
    namespace = "com.tclstream.tv"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.jinky.tv"
        minSdk = 25 // Fire OS 6 (Fire TV Stick 4K 1st gen) and newer
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"

        ndk {
            // TCL 55P7K and Fire TV Sticks run ARM; x86_64 is only for the emulator.
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_shared")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Signed with the debug key for now so it can be sideloaded; real signing comes in Phase 7.
            signingConfig = signingConfigs.getByName("debug")
        }
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

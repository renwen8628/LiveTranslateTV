plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.livetranslate.tv"
    compileSdk = 36
    // Remote Play native code (chiaki-ng). See tools/setup-native-build.sh.
    ndkVersion = "27.1.12297006"

    defaultConfig {
        applicationId = "com.livetranslate.tv"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        ndk {
            // The target TCL TV runs a 32-bit-only userspace.
            abiFilters += "armeabi-v7a"
        }
        externalNativeBuild {
            cmake {
                // chiaki-ng generates protobuf code with Python + protobuf (from .venv-nanopb).
                arguments += "-DPYTHON_EXECUTABLE=${rootProject.file(".venv-nanopb/bin/python").absolutePath}"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.leanback)
    implementation(libs.glide)
    implementation(libs.okhttp)
}
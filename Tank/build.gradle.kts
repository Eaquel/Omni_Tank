plugins {
    id("com.android.application")
}

android {
    namespace = "com.omni.tank"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.omni.tank"
        minSdk = 30
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"
        ndk {
            abiFilters += "arm64-v8a"
        }
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++26"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("Source/Main/Native/CMakeLists.txt")
            version = "4.4.3"
        }
    }

    sourceSets {
        getByName("main") {
            manifest.srcFile("Source/Main/AndroidManifest.xml")
            java.srcDirs("Source/Main/Kotlin")
            kotlin.srcDirs("Source/Main/Kotlin")
            res.srcDirs("Source/Main/res")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        debug {
            isDebuggable = true
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_25
        targetCompatibility = JavaVersion.VERSION_25
    }

    kotlin {
        jvmToolchain(25)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-ktx:1.11.0")
}

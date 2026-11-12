plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.chesshelper"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.chesshelper"
        minSdk = 30
        targetSdk = 34
        // every build gets a bigger number (github's run number), so the phone installs it as an update
        versionCode = (System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 0) + 100
        versionName = "1.19"
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    // one fixed key for every build, taken from the repo's secrets (see the readme). without it each github build
    // signs with a new random key, and android refuses to install it over the old app (uninstall, settings lost)
    signingConfigs {
        getByName("debug") {
            val store = System.getenv("SIGNING_STORE")
            if (store != null && file(store).exists()) {
                storeFile = file(store)
                storePassword = System.getenv("SIGNING_PASSWORD")
                keyAlias = System.getenv("SIGNING_ALIAS") ?: "chesshelper"
                keyPassword = System.getenv("SIGNING_PASSWORD")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    // stockfish is shipped as libstockfish.so and run as a program, so it must be extracted to disk
    packaging { jniLibs { useLegacyPackaging = true } }
}

dependencies {
    implementation("com.github.bhlangonijr:chesslib:1.3.3")
}

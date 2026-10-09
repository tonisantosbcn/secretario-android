plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.tonisantos.secretario"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tonisantos.secretario"
        minSdk = 26
        targetSdk = 35
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.0." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
    }

    // Misma firma en cada compilación, para poder actualizar sin desinstalar.
    signingConfigs {
        create("fija") {
            storeFile = rootProject.file("firma/secretario.jks")
            storePassword = "secretario"
            keyAlias = "secretario"
            keyPassword = "secretario"
        }
    }
    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("fija") }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fija")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { abortOnError = false }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

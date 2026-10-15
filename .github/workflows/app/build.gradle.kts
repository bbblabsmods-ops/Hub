plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.bbblabs.hub"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.bbblabs.hub"
        minSdk = 24
        targetSdk = 32
        versionCode = 3
        versionName = "2.2"
    }
    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

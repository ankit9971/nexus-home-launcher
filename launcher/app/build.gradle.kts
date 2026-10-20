plugins {
    id("com.android.application")
}

android {
    namespace = "app.nexus.launcher"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.nexus.launcher"
        minSdk = 30   // Android TV 11 (XGIMI MoGo 2)
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

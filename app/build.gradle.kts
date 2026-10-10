plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.randy.fobologger"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.randy.fobologger"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "4.0"
    }
}

android {
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

plugins {
    id("com.android.application")
}

android {
    namespace = "ch.ninebot.f2lab"
    compileSdk = 35

   defaultConfig {
    applicationId = "ch.ninebot.f2lab.pixel"
    minSdk = 26
    targetSdk = 35
    versionCode = 2
    versionName = "1.0.1"
}

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

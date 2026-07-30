plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "org.scpc.r2.sample"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.scpc.r2.sample"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.1-local"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(files("libs/scpc-probe-starter-3.0.0-draft.aar"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}

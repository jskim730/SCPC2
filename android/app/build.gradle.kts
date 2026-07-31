import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/**
 * Release signing material, read from `android/keystore.properties`.
 *
 * That file and the keystore it points at are never committed: the official
 * rules require the participant to keep the signing private key, keystore and
 * password themselves, and forbid shipping any credential in the APK or source.
 * `keystore.properties.example` documents the four keys to fill in.
 *
 * Only the release build needs them, so an absent file leaves debug builds and
 * the JVM tests working exactly as before. `assembleRelease` then fails with an
 * explicit message rather than quietly producing an unsigned APK.
 */
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use(::load)
}
val releaseSigningKeys = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
val releaseSigningReady = releaseSigningKeys.all { key ->
    !keystoreProperties.getProperty(key).isNullOrBlank()
}

android {
    namespace = "com.scpc.deliveryagent"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.scpc.deliveryagent"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        ndk {
            // Official release requirement: arm64-v8a support. x86_64 is kept so the
            // same source builds for the emulator used in local rehearsal.
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (releaseSigningReady) {
            create("release") {
                // Resolved against the Gradle root (`android/`), so the keystore can
                // sit outside the repository entirely.
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Kept off so the submitted APK corresponds line for line with the
            // source bundle it ships with.
            isMinifyEnabled = false
            if (releaseSigningReady) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

/**
 * An unsigned release APK cannot be installed, and the official tooling reads the
 * certificate from the APK itself, so producing one by accident would waste a
 * rehearsal. Packaging is refused outright instead.
 */
tasks.matching { it.name == "packageRelease" || it.name == "packageReleaseBundle" }
    .configureEach {
        doFirst {
            check(releaseSigningReady) {
                "release signing is not configured. Copy keystore.properties.example to " +
                    "android/keystore.properties and fill in storeFile, storePassword, " +
                    "keyAlias and keyPassword. Neither that file nor the keystore is committed."
            }
        }
    }

dependencies {
    implementation(files("libs/scpc-probe-starter-3.0.0-draft.aar"))

    // The generic production core is pure Kotlin plus org.json, so every ASPR
    // rule is exercised by JVM unit tests without an emulator.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
}

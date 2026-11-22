plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.sahl.gallery"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.sahl.gallery"
        minSdk = 30
        targetSdk = 34
        val run = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionCode = run
        versionName = "1.$run"
    }

    val ks = System.getenv("KEYSTORE_FILE")
    signingConfigs {
        if (ks != null) create("sahl") {
            storeFile = file(ks)
            storePassword = System.getenv("KEYSTORE_PASS")
            keyAlias = "sahl"
            keyPassword = System.getenv("KEYSTORE_PASS")
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = if (ks != null) signingConfigs.getByName("sahl") else signingConfigs.getByName("debug")
        }
    }

    buildFeatures { buildConfig = true }
    lint { checkReleaseBuilds = false; abortOnError = false }
    testOptions { unitTests.isReturnDefaultValues = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.activity:activity-ktx:1.9.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("androidx.biometric:biometric:1.1.0")

    testImplementation("junit:junit:4.13.2")
}

import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "io.github.blublub.tvcal"
    compileSdk {
        version = release(37) { minorApiLevel = 1 }
    }

    defaultConfig {
        applicationId = "io.github.blublub.tvcal"
        minSdk = 29
        targetSdk = 36

        // The app ships English (the original) and German.
        androidResources.localeFilters += listOf("en", "de")
        versionCode = 1
        versionName = "0.1.0-alpha"
    }

    // Release signing key: kept OUTSIDE the repository, in ~/.tvcal-signing/signing.properties
    // (storeFile, storePassword, keyAlias, keyPassword). Without it, release builds are unsigned.
    val signingProps = File(System.getProperty("user.home"), ".tvcal-signing/signing.properties")
    signingConfigs {
        if (signingProps.exists()) {
            create("release") {
                val p = Properties().apply { signingProps.inputStream().use { load(it) } }
                storeFile = File(signingProps.parentFile, p.getProperty("storeFile"))
                storePassword = p.getProperty("storePassword")
                keyAlias = p.getProperty("keyAlias")
                keyPassword = p.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // Debug and release builds can be installed side by side.
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    // The iPhone app's resources that the Android app reuses as-is (no copies in the repo).
    sourceSets["main"].assets.directories.add("../../TV Calibrator/Resources")

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(project(":engine"))
    implementation(platform("androidx.compose:compose-bom:2026.11.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.1")
    implementation("androidx.core:core-ktx:1.19.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
}

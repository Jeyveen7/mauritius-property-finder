plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "mu.propertyfinder"
    compileSdk = 35

    defaultConfig {
        applicationId = "mu.propertyfinder"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        // OpenFreeMap: free, open-source hosted tiles from OpenStreetMap data, no key or
        // account. The map shows the required "© OpenStreetMap" credit itself.
        buildConfigField("String", "MAP_STYLE_URL", "\"https://tiles.openfreemap.org/styles/liberty\"")
        // Where the daily data snapshot is published (GitHub Pages, see .github/workflows/data.yml),
        // e.g. -PdataBaseUrl=https://<user>.github.io/<repo>/data . Empty: only the bundled copy.
        val dataBaseUrl = providers.gradleProperty("dataBaseUrl").getOrElse("")
        buildConfigField("String", "DATA_BASE_URL", "\"$dataBaseUrl\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // Installable straight from this machine (adb). For Google Play, sign with your own upload key.
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.maplibre)
    implementation(libs.mlkit.text)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.coil.compose)
    implementation(libs.androidx.work)
    debugImplementation(libs.androidx.ui.tooling)
    testImplementation(libs.junit)
}

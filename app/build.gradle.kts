plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "nz.co.chordalicious.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "nz.co.chordalicious.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 42
        versionName = "0.9.19"
        buildConfigField("String", "SERVER", "\"https://chordalicious.co.nz\"")
    }
    buildFeatures { compose = true; buildConfig = true }
    buildTypes { release { isMinifyEnabled = false } }   // F-Droid builds from source; keep the build simple and reproducible
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    dependenciesInfo { includeInApk = false; includeInBundle = false }   // F-Droid: no signed dependency blob
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}

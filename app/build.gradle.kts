plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.laststop.app"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.laststop.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 7
        versionName = "0.5.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    buildTypes { release { isMinifyEnabled = false } }
}

dependencies {
    constraints {
        // Play services brings an old Fragment transitively; Activity Result needs >= 1.3.
        implementation("androidx.fragment:fragment:1.8.6")
    }
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.datastore:datastore-preferences:1.1.7")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
}

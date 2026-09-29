plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "pro.ezboss.mobile"
    compileSdk = 36
    defaultConfig {
        applicationId = "pro.ezboss.mobile"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        fun prop(name: String) = providers.gradleProperty(name).orNull.orEmpty()
        buildConfigField("String", "AUTH0_DOMAIN", "\"${prop("auth0Domain")}\"")
        buildConfigField("String", "AUTH0_CLIENT_ID", "\"${prop("auth0ClientId")}\"")
        buildConfigField("String", "AUTH0_AUDIENCE", "\"${prop("auth0Audience")}\"")
        buildConfigField("String", "API_BASE_URL", "\"${prop("apiBaseUrl")}\"")
        manifestPlaceholders["auth0Domain"] = prop("auth0Domain")
        manifestPlaceholders["auth0Scheme"] = "https"
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.navigation:navigation-compose:2.9.0")
    implementation(platform("androidx.compose:compose-bom:2025.05.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("com.auth0.android:auth0:4.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
}

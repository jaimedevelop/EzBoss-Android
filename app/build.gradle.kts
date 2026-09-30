import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}


// Android Studio's local.properties is not automatically a Gradle property source.
val localAuthProperties = Properties().apply {
    val localFile = rootProject.file("local.properties")
    if (localFile.isFile) localFile.inputStream().use { load(it) }
}
val authPropertyNames = listOf("auth0Domain", "auth0ClientId", "auth0Audience", "apiBaseUrl")
val authProperties = authPropertyNames.associateWith { name ->
    (providers.gradleProperty(name).orNull ?: localAuthProperties.getProperty(name).orEmpty()).trim()
}
fun authProperty(name: String) = authProperties.getValue(name)
fun quoted(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

val validateAuthConfiguration = tasks.register("validateAuthConfiguration") {
    group = "verification"
    description = "Rejects builds missing the configuration required for sign-in."
    doLast {
        val missing = authProperties.filterValues { it.isBlank() || it.startsWith("YOUR_") }.keys
        check(missing.isEmpty()) {
            "EzBoss sign-in configuration is missing: ${missing.joinToString()}. " +
                "Set these in EzBoss-Android/local.properties or ~/.gradle/gradle.properties, " +
                "then rebuild and reinstall. See README.md. Never supply a client secret."
        }
    }
}
tasks.named("preBuild") { dependsOn(validateAuthConfiguration) }

android {
    namespace = "pro.ezboss.mobile"
    compileSdk = 36
    defaultConfig {
        applicationId = "pro.ezboss.mobile"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        buildConfigField("String", "AUTH0_DOMAIN", quoted(authProperty("auth0Domain")))
        buildConfigField("String", "AUTH0_CLIENT_ID", quoted(authProperty("auth0ClientId")))
        buildConfigField("String", "AUTH0_AUDIENCE", quoted(authProperty("auth0Audience")))
        buildConfigField("String", "API_BASE_URL", quoted(authProperty("apiBaseUrl")))
        manifestPlaceholders["auth0Domain"] = authProperty("auth0Domain")
        // Keep this in sync with the Native application's Auth0 callback URL.
        manifestPlaceholders["auth0Scheme"] = "pro.ezboss.mobile"
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

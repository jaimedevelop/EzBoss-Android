import java.util.Properties
import java.net.URI

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
val stagingBuild = providers.gradleProperty("stagingBuild").orNull == "true"
val authPropertyNames = listOf("auth0Domain", "auth0ClientId", "auth0Audience", "apiBaseUrl")
val authProperties = authPropertyNames.associateWith { name ->
    (if (stagingBuild) when (name) {
        "auth0ClientId" -> providers.gradleProperty("stagingAuth0ClientId").orNull.orEmpty()
        "auth0Audience" -> "https://api-staging.ezboss.pro"
        "apiBaseUrl" -> "https://api-staging-7b72.up.railway.app"
        else -> providers.gradleProperty(name).orNull ?: localAuthProperties.getProperty(name).orEmpty()
    } else providers.gradleProperty(name).orNull ?: localAuthProperties.getProperty(name).orEmpty()).trim()
}
fun authProperty(name: String) = authProperties.getValue(name)
fun quoted(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

val nativeApplicationId = if (stagingBuild) "pro.ezboss.mobile.staging" else "pro.ezboss.mobile"
val forbiddenAuthProperties = listOf("auth0ClientSecret", "AUTH0_CLIENT_SECRET", "auth0ManagementClientSecret", "AUTH0_MANAGEMENT_CLIENT_SECRET")

val validateAuthConfiguration = tasks.register("validateAuthConfiguration") {
    group = "verification"
    description = "Rejects builds missing the configuration required for sign-in."
    doLast {
        val forbidden = forbiddenAuthProperties.filter { name ->
            !(providers.gradleProperty(name).orNull ?: localAuthProperties.getProperty(name)).isNullOrBlank()
        }
        check(forbidden.isEmpty()) { "Server-only credential properties are forbidden in mobile configuration: ${forbidden.joinToString()}. Remove them; no values were logged." }
        fun publicValue(value: String) = value.isNotBlank() && value.none(Char::isWhitespace) &&
            !value.contains("YOUR_", ignoreCase = true) && !value.contains("CHANGE_ME", ignoreCase = true) && !value.contains("$(")
        val invalid = authProperties.filter { (name, value) ->
            !publicValue(value) || when (name) {
                "auth0Domain" -> !value.matches(Regex("(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\\.)+[a-z]{2,63}"))
                "auth0ClientId" -> !value.matches(Regex("[A-Za-z0-9_-]+"))
                "apiBaseUrl" -> !runCatching {
                    val uri = URI(value)
                    uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null &&
                        uri.rawQuery == null && uri.rawFragment == null && (uri.port == -1 || uri.port in 1..65535)
                }.getOrDefault(false)
                else -> false
            }
        }.keys
        check(invalid.isEmpty()) {
            "EzBoss sign-in configuration is missing or invalid: ${invalid.joinToString()}. " +
                "Use a domain host, Native client ID, audience and HTTPS API URL without credentials/query/fragment. " +
                "Set these in local.properties or ~/.gradle/gradle.properties, then rebuild. Never supply a client secret."
        }
    }
}
tasks.named("preBuild") { dependsOn(validateAuthConfiguration) }

android {
    namespace = "pro.ezboss.mobile"
    compileSdk = 36
    defaultConfig {
        applicationId = nativeApplicationId
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        buildConfigField("String", "AUTH0_DOMAIN", quoted(authProperty("auth0Domain")))
        buildConfigField("String", "AUTH0_CLIENT_ID", quoted(authProperty("auth0ClientId")))
        buildConfigField("String", "AUTH0_AUDIENCE", quoted(authProperty("auth0Audience")))
        buildConfigField("String", "API_BASE_URL", quoted(authProperty("apiBaseUrl")))
        manifestPlaceholders["appLabel"] = if (stagingBuild) "EzBoss Staging" else "EzBoss"
        manifestPlaceholders["auth0Domain"] = authProperty("auth0Domain")
        manifestPlaceholders["releaseAuth0Audience"] = authProperty("auth0Audience")
        manifestPlaceholders["releaseApiBaseUrl"] = authProperty("apiBaseUrl")
        manifestPlaceholders["releaseAuth0ClientId"] = authProperty("auth0ClientId")
        // Keep this in sync with the Native application's Auth0 callback URL.
        manifestPlaceholders["auth0Scheme"] = nativeApplicationId
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
    // JVM tests need real JSON behavior; Android's android.jar implementation throws.
    testImplementation("org.json:json:20250517")
}

// Every release APK packaging path must pass the signed artifact gate. Debug and
// staging debug builds remain available without a distribution signing identity.
tasks.matching { it.name == "packageRelease" }.configureEach {
    doFirst {
        check(!stagingBuild) { "Distribution release cannot use staging identity." }
        val releasePolicyProperties = listOf("releaseSigningCertificateSha256", "releaseExpectedAuth0Domain", "releaseExpectedAudience", "releaseExpectedApiOrigin", "releaseExpectedClientId")
        check(releasePolicyProperties.all { !providers.gradleProperty(it).orNull.isNullOrBlank() }) {
            "Set the independently approved release signing/identity policy properties: ${releasePolicyProperties.joinToString()}."
        }
    }
    doLast {
        val apks = layout.buildDirectory.dir("outputs/apk/release").get().asFile.walkTopDown().filter { it.extension == "apk" }.toList()
        check(apks.isNotEmpty()) { "No release APK was produced for security validation." }
        apks.forEach { apk ->
            project.exec {
                commandLine("python3", rootProject.file("scripts/check-release.py"), apk,
                    "--package", nativeApplicationId, "--auth0-domain", providers.gradleProperty("releaseExpectedAuth0Domain").get(),
                    "--certificate-sha256", providers.gradleProperty("releaseSigningCertificateSha256").get(),
                    "--audience", providers.gradleProperty("releaseExpectedAudience").get(), "--api-origin", providers.gradleProperty("releaseExpectedApiOrigin").get(),
                    "--client-id", providers.gradleProperty("releaseExpectedClientId").get(),
                    "--apksigner", android.sdkDirectory.resolve("build-tools/${android.buildToolsVersion}/apksigner"),
                    "--apkanalyzer", android.sdkDirectory.resolve("cmdline-tools/latest/bin/apkanalyzer"))
            }
        }
    }
    // Cached/up-to-date package tasks must not bypass validation on a later release.
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
}
tasks.matching { it.name == "bundleRelease" }.configureEach {
    doFirst { error("AAB distribution is not accepted by the signed APK security gate. Use the verified release APK pipeline.") }
}

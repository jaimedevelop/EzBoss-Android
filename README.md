# EzBoss Android

Native Kotlin and Jetpack Compose application for existing EzBoss accounts.

## Local configuration

Put environment-specific configuration in `~/.gradle/gradle.properties` (or another ignored local Gradle properties file):

```properties
auth0Domain=YOUR_TENANT.us.auth0.com
auth0ClientId=YOUR_ANDROID_NATIVE_APPLICATION_CLIENT_ID
auth0Audience=YOUR_EXISTING_API_AUDIENCE
apiBaseUrl=https://YOUR_EZBOSS_API_ORIGIN
```

No Auth0 client secret belongs in the app. Create an Auth0 **Native** application for package `pro.ezboss.mobile`, enable Authorization Code Grant with PKCE, and configure:

```text
Allowed Callback URL: https://YOUR_TENANT.us.auth0.com/android/pro.ezboss.mobile/callback
Allowed Logout URL:   https://YOUR_TENANT.us.auth0.com/android/pro.ezboss.mobile/callback
```

Use its native client ID (not the web SPA client ID). Request the existing API audience; `ezboss-api` validates RS256 issuer, audience, expiry, and active user status. Login requests `openid profile email offline_access`; SDK credential storage encrypts tokens using Android Keystore. The local Sign Out operation clears that credential store. No live Auth0 tenant values have been supplied.

Build with Java 17 and Android SDK 36 using `./gradlew :app:assembleDebug` and `./gradlew :app:testDebugUnitTest`.

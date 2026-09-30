# EzBoss Android

Native Kotlin and Jetpack Compose application for existing EzBoss accounts.

## Local configuration

Open the `EzBoss-Android` directory in Android Studio. Put environment-specific configuration in its ignored `local.properties` alongside `sdk.dir`, or in `~/.gradle/gradle.properties`. Gradle properties (including `-P` overrides) take precedence over `local.properties`:

```properties
auth0Domain=YOUR_TENANT.us.auth0.com
auth0ClientId=YOUR_ANDROID_NATIVE_APPLICATION_CLIENT_ID
auth0Audience=YOUR_EXISTING_API_AUDIENCE
apiBaseUrl=https://YOUR_EZBOSS_API_ORIGIN
```

No Auth0 client secret belongs in the app. Create an Auth0 **Native** application for package `pro.ezboss.mobile`, enable Authorization Code Grant with PKCE, and configure:

```text
Allowed Callback URL: pro.ezboss.mobile://YOUR_TENANT.us.auth0.com/android/pro.ezboss.mobile/callback
Allowed Logout URL:   pro.ezboss.mobile://YOUR_TENANT.us.auth0.com/android/pro.ezboss.mobile/callback
```

Use its native client ID (not the web SPA client ID). Request the existing API audience; `ezboss-api` validates RS256 issuer, audience, expiry, and active user status. Login requests `openid profile email offline_access`; SDK credential storage encrypts tokens using Android Keystore. The local Sign Out operation clears that credential store. The Android Native client ID must be supplied separately from the web configuration.

Build with Java 17 and Android SDK 36 using `./gradlew :app:assembleDebug` and `./gradlew :app:testDebugUnitTest`.

## Troubleshooting sign-in configuration

“Sign in is not configured” means the installed APK contains a blank Auth0 domain,
Native client ID, API audience, or API base URL. Retry cannot change values compiled
into an APK. The build now validates these fields before building the app and lists
missing property names. After updating configuration, rebuild and reinstall the app.
Use raw property values without surrounding quotes. Never put a client secret here.
The API base URL is the reachable HTTPS backend origin, not the Auth0 audience
identifier unless both actually match. Android's localhost refers to the device,
not the development computer.

If Logcat (`EzBossAuth`) reports `invalid_request` and “Client is not authorized to
access resource server”, open Auth0 **Applications > APIs**, select the API whose
Identifier matches `auth0Audience`, and authorize the Android Native application
under **Application Access > Edit > User-Delegated Access**. With per-app
authorization, this needs a user-access grant; a Machine-to-Machine client-access
grant does not enable the browser Authorization Code/PKCE flow. Allow the API
permissions this application needs. Save and retry sign-in.

The current callback uses the `pro.ezboss.mobile` custom scheme, so its manifest
filter must not use `android:autoVerify="true"`. SHA-256 certificate associations
are for HTTPS Android App Links and do not authorize access to the API. Moving to
HTTPS requires changing the manifest scheme, both login/logout `withScheme` calls,
and allowed callback/logout URLs together, plus verifying the domain association
for the installed build's signing certificate.

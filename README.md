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

## Native foundation checks (October 27)

Use a compatible Gradle JDK (17 or 21; this workspace was verified with cached
Adoptium 21). Android Studio's Java 25 is not the verified Gradle runtime.

```sh
./gradlew --no-daemon --max-workers=2 :app:assembleDebug :app:testDebugUnitTest
python3 scripts/check-config.py
```

The wrapper JAR is present. `--offline` can be added when dependencies are cached.
These commands do not launch an emulator. A Gradle single-use build daemon exits
at the end; do not leave simulators or watch servers running after tests.

The build and runtime now reject placeholders, a URL in place of the Auth0 domain,
and HTTP API URLs or URLs with user info/query/fragment/invalid port. Diagnostics
list field names only. The build also rejects `auth0ClientSecret`,
`AUTH0_CLIENT_SECRET`, `auth0ManagementClientSecret` and
`AUTH0_MANAGEMENT_CLIENT_SECRET` in local or Gradle properties. Only the four
allowlisted public identity/API properties are emitted to BuildConfig. Never copy
server `.env` files into mobile configuration. A native client ID is public;
validation cannot determine whether an opaque value was copied from a secret
field. Confirm the application type and ID directly in Auth0.

The SDK login/logout scheme derives from `BuildConfig.APPLICATION_ID`; the merged
manifest uses the same application ID and exact `/android/APP_ID/callback` path.
The checker compares the merged manifest with the generated BuildConfig without
printing tenant values. Auth0 errors are no longer dumped to Logcat/UI. API
redirects are disabled. `/users/me` rejects inactive or malformed identities;
401/403 identity failures clear stored credentials. Business endpoint 401 removes
the protected shell; business endpoint 403 remains a resource permission denial.
Requests cannot grant business permissions locally.

Live callback, renewal, logout and owner/employee acceptance remain pending tenant
allowlists/user-delegated access and device checks. Compilation and mocked JVM
checks do not prove live Auth0 behavior. The previously observed tenant-side
resource-server authorization error must still be resolved in Auth0; no provider
settings were changed by this work.


### Native session lifecycle checks

Credential operations share one manager and a serialized gate. Logout waits for an
in-flight renewal to finish saving before deleting its rotated credentials, even
if the original request is cancelled. Requests require 30 seconds of token validity.
Application backup is disabled. Returning to the foreground revalidates API identity;
old resource responses cannot change a newer session. Provider payloads and credential
values are not shown in errors; credential descriptions are redacted.

Run `./gradlew --offline --no-daemon --max-workers=2 :app:assembleDebug :app:testDebugUnitTest`
with the documented compatible JDK, then `python3 scripts/check-config.py`.
See `../docs/launch/OCTOBER_28_NATIVE_SESSION.md` for evidence and outstanding live acceptance.

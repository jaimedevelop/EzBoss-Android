# EzBoss Android State

Updated: 2026-09-29

## Current state

A single-module native Android app uses Kotlin, Jetpack Compose, Auth0 Android SDK, a reusable API client, secure credential persistence, branded login, and an authenticated shell. Estimates now has API-backed list and detail browsing plus a read-only client-facing preview. Inventory, Collections, Work Orders, Purchasing, People, and Settings remain placeholders. Mobile sign-up remains TODO.

## Architecture and file locations

- `app/src/main/java/pro/ezboss/mobile/MainActivity.kt`: Compose app entry, branded Sign In screen, `AuthViewModel`, and authenticated shell. Estimates and `estimates/{estimateId}` are authenticated routes; the other six destinations remain title/Coming soon placeholders. The shell profile, drawer, and Sign Out wrap the protected host. The former persistent "EzBoss" top bar has been removed; the drawer control is now a white elevated floating button at the safe top-left above both page content and the open drawer.
- `app/src/main/res/values/styles.xml`: native Android system accent uses the web app's orange-600 (`#EA580C`); Compose branding, buttons, navigation drawer, selected destinations, progress, and estimate accents use the same primary orange palette.
- `app/src/main/java/pro/ezboss/mobile/Estimates.kt`: estimate browsing, square two-column cards, debounced customer search, document/client state filters, retry/empty/access states, load-more, detail content, dashboard tabs, and read-only client preview. Search/filter values, loaded cards, and grid scroll state use saveable navigation state on detail return.
- `app/src/main/java/pro/ezboss/mobile/EstimateContracts.kt`: explicit mapping for estimateState/clientState filter values and dashboard tab order/availability.
- `app/src/main/java/pro/ezboss/mobile/auth/AuthRepository.kt`: Auth0 browser Universal Login through `WebAuthProvider`, audience and `openid profile email offline_access` scopes, encrypted credentials manager, restoration/refresh, credential clearing, and Auth0 browser logout.
- `app/src/main/java/pro/ezboss/mobile/auth/AuthState.kt`: small authentication state model.
- `app/src/main/java/pro/ezboss/mobile/data/ApiClient.kt`: reusable authenticated OkHttp client; Bearer token calls to `GET /users/me`, `GET /estimates`, and `GET /estimates/:id`; maps estimate list/detail DTOs, owner detail line items/groups, and client display settings.
- `app/src/main/AndroidManifest.xml`: Internet access and Auth0 callback activity for `pro.ezboss.mobile`.
- `app/build.gradle.kts`, root Gradle files, `gradlew`, and `gradlew.bat`: one app module, Compose, Material icons, Navigation Compose 2.9.0, Java 17, min SDK 26, compile SDK 36, Gradle 8.13 wrapper configuration. The wrapper bootstrap JAR is currently missing because it could not be downloaded in the restricted network environment.
- `app/src/test/java/pro/ezboss/mobile/auth/AuthConfigurationTest.kt`: focused checks that required runtime config is present.
- `app/src/test/java/pro/ezboss/mobile/EstimateContractsTest.kt` and `app/src/test/java/pro/ezboss/mobile/data/EstimateMappingTest.kt`: focused checks for filter field separation, invoice/list mapping, and tab order/enabled state.
- `README.md`: local properties and Auth0 setup instructions.

## Estimates API contract and behavior

- The owner list is `GET /estimates` with Bearer auth and `read:estimates`. Existing calls that omit pagination still return the same JSON array. Optional `limit` (1–100, default 25 when pagination is requested) and `offset` (0 or greater) are validated; malformed/repeated/non-numeric values fail with 400. Results use deterministic `createdAt DESC, id DESC` ordering. Existing owner and non-archived constraints remain in the query.
- Search uses the established `customerName` parameter (`ILIKE` contains search). Document filtering uses `estimateState` (`draft`, `estimate`, `change-order`, `invoice`); client response filtering uses `clientState` (`sent`, `viewed`, `accepted`, `denied`, `on-hold`, `expired`). `clientState=none` selects rows whose clientState is null. These filters are SQL predicates before LIMIT/OFFSET.
- Detail is `GET /estimates/:id`, also requiring `read:estimates` and scoped by the authenticated owner. The Android preview only reads this owner detail and applies `showEstimateTab`, hidden line items, display mode, item/group price rules, and subtotal/tax/total visibility. It does not request public token DTOs, mint a token, call opened/view tracking, or expose customer email/phone, audit history, internal notes, payment info, or actions.
- The shared web TabBar order is Estimate, Client View, Change Orders, Payments, Timeline, Communication, History; Change Orders is omitted for a change-order estimate. Android enables Estimate and Client View; remaining applicable tabs show Coming soon. Creation/editing, sending, payments, and mutations are explicit TODOs.
- Backend change in sibling `../ezboss-api`: `src/routes/estimates.ts`, pagination validation helper `src/services/estimateListPagination.ts`, focused `tests/estimate-list-pagination.test.ts`. Unpaginated response shape is preserved.

## Authentication and API contract

The Auth0 Native application is expected to use Authorization Code with PKCE and browser Universal Login. The Android client ID is independently configured and must not reuse the SPA client ID. SDK `SecureCredentialsManager` stores credentials encrypted using Android Keystore-backed encryption and renews through `offline_access` refresh tokens. Sign-out clears stored credentials before attempting Auth0 browser logout. The app currently has no user-scoped application cache to clear.

After login or session restoration, the app calls `GET {API_BASE_URL}/users/me` with `Authorization: Bearer <access token>`. This is the API account/role identity endpoint, not the contractor profile endpoint. API 401 clears the saved session; 404 indicates that no API user record exists. The app does not provision users, call onboarding/profile endpoints, or use the web-only Firebase token bridge. The Account contract exposes display name/email but no avatar URL, so the square sidebar profile shows those fields and initials fallback (name initials or first email character).

## Configuration and external setup

Provide these non-secret values in `~/.gradle/gradle.properties` or another local Gradle properties source:

```properties
auth0Domain=YOUR_TENANT.us.auth0.com
auth0ClientId=YOUR_ANDROID_NATIVE_APPLICATION_CLIENT_ID
auth0Audience=YOUR_EXISTING_API_AUDIENCE
apiBaseUrl=https://YOUR_EZBOSS_API_ORIGIN
```

Auth0 dashboard setup: create a **Native** application with package `pro.ezboss.mobile`; enable Authorization Code Grant/PKCE; allow callback `pro.ezboss.mobile://YOUR_TENANT.us.auth0.com/android/pro.ezboss.mobile/callback`; add the same SDK return URL to Allowed Logout URLs if remote Auth0 browser logout is later enabled; configure the existing API audience and appropriate API scopes. No client secret belongs in Android. No real tenant values were available in this task.

## Verification performed

- Sign-in issue investigation (2026-09-28): The checked-in/default Android build configuration resolves missing Gradle properties to empty strings. `AuthConfiguration.ready` requires Auth0 domain, native client ID, API audience, and API base URL, so the screenshot's exact error means at least one is blank in the installed build. No user-local `~/.gradle/gradle.properties` was present in the accessible environment; no live Auth0 tenant values are available to safely configure here. Fixed the follow-up action for this error so tapping the button launches Auth0 login once configuration is provided, instead of retrying session restore. Source change is not device/build verified.

- Compile-fix verification (2026-09-28): `:app:compileDebugKotlin` passed using cached Gradle 8.13 and cached Adoptium Java 21 outside the sandbox. Auth0 4.0.1 constructor was verified against the installed SDK source. `:app:testDebugUnitTest` ran: 3 passed, 1 failed (`EstimateMappingTest`, Android stub `JSONObject.opt` is not mocked). Existing deprecation/nullability warnings remain. Android Studio's bundled Java 25 failed Gradle configuration; use a compatible Gradle JDK such as the cached Java 21. Device login and UI behavior remain unverified.
- Inspected `NewEzBoss/src/services/apiAuth.ts`, `AuthContext.tsx`, `ezboss-api/src/routes/users.ts`, `middleware/tokenCache.ts`, and API route mounting. Confirmed issuer/audience RS256 JWT validation and live active-user check.
- Verified current Auth0 Android official SDK setup guidance: Auth0.Android 4.0.1, browser login, audience/scope request, secure credential manager, Android API 26+, Java 17.
- Added a focused unit test for missing required configuration, but it has not run. Reviewed SDK 3.4.0 cached signatures for cancellation and secure credential manager patterns while current official setup guidance identified SDK 4.0.1.
- `ezboss-api`: focused pagination test run passed (79 passed, 8 skipped across the test glob); `npm run build` / TypeScript compilation passed; `git diff --check` passed.
- Android `:app:assembleDebug :app:testDebugUnitTest` remains unverified. The wrapper JAR is absent (`GradleWrapperMain` not found). Running cached Gradle 9.2.1 directly with Android Studio's bundled JBR fails before project configuration loading `libnative-platform.dylib` for Mac OS X aarch64. Source changes and focused Kotlin tests are present, but could not run. No emulator/previews were available; narrow layout, long-name truncation, navigation state, and tab behavior were source-inspected only.
- `git diff --check` passes in both repositories.

## TODOs

- Provide a working JSON implementation in the JVM estimate-mapping test environment; its current Android stub throws `Method opt in org.json.JSONObject not mocked`.
- Supply Auth0 Native application domain/client ID, API audience and API base URL through local Gradle properties and validate callback setup.
- Run Gradle assemble and focused unit tests in an Android/Java 17 environment with a working wrapper installation, then resolve any compiler/dependency issues.
- Confirm with product whether an existing Auth0 identity missing from `users` should be directed to support or receive an explicitly designed provisioning/onboarding flow. Current behavior shows a clear initialization error.
- Confirm the Native app’s Allowed Logout URLs include the SDK logout URI and exercise browser logout on-device. Sign Out now clears credentials and starts Auth0 browser logout.
- Estimate creation/editing, sending/sharing, payments, mutations, and unsupported dashboard tabs remain TODO. The Client View preview is read-only.
- Replace the six remaining intentional placeholders with destination features in later tasks; keep estimate routes behind authentication.
- Run the Android build and focused Kotlin tests in an environment with a working Gradle 8.13 wrapper and Java 17, then inspect a narrow emulator layout and long customer names.
- Mobile sign-up remains TODO.

## Change log

- 2026-09-29: Removed the authenticated-shell top app bar and its "EzBoss" label. The hamburger/close control is now a 56dp elevated floating action button at the safe top-left, layered over the active content and the drawer. Source inspection completed; Android build/emulator verification remains pending.

- 2026-09-29: Read emulator `EzBossAuth` logs and confirmed current login blocker: Auth0 returns `invalid_request`, saying the Android client is not authorized for resource server `https://api.ezboss.pro`. Callback delivery succeeds. Tenant-side User-Delegated Access authorization for the Native client remains required; no tenant settings were changed. Updated README with the remediation. Replaced the SDK RedirectActivity manifest declaration to remove HTTPS `autoVerify` from the existing custom-scheme callback without inheriting a duplicate SDK filter. `:app:processDebugManifest --offline` passed with cached Java 21; parsed merged manifest confirms exactly one custom-scheme filter and no autoVerify. `git diff --check` passed. End-to-end login remains pending the Auth0 configuration fix.

- 2026-09-29: Login failures now keep the original Auth0 error, log its diagnostic details (Auth0 code, status, and description) under `EzBossAuth`, and make Retry initiate login again rather than attempting to restore credentials that do not exist. Build verification remains pending because the Gradle wrapper bootstrap JAR is missing.
- 2026-09-29: Aligned the Android manifest, browser login/logout calls, and Auth0 setup documentation on the registered `pro.ezboss.mobile` custom-scheme callback. Build verification remains pending because the Gradle wrapper bootstrap JAR is missing. The local Auth0 client ID must be the Native application's Client ID, never its client secret.

- 2026-09-28: Updated the Android app theme to match web orange-600 (`#EA580C`), including Compose primary/secondary colors, login and shell branding, drawer states/profile, estimate accents, and native system accent. `git diff --check` passes; Android build and on-device visual verification remain pending because the Gradle wrapper bootstrap JAR is unavailable.
- 2026-09-28: Fixed reported Kotlin compile errors in `Estimates.kt` (named `clickable` callback) and `auth/AuthRepository.kt` (Auth0 4.0.1 public credentials-manager constructor, typed login callback with cancellation guards, and generated `BuildConfig` import). No dependency/configuration changes. Kotlin compilation passes; unit-test limitation is recorded above.
- 2026-09-28: Bootstrapped Android app/auth/API foundation and documented setup plus unverified build prerequisites.
- 2026-09-28: Added the shared authenticated shell, single root drawer state, fixed-position header/menu-X, profile initials fallback, flexible drawer navigation area, Back and scrim dismissal, and Auth0 sign-out. Build/runtime verification remains pending as described above.
- 2026-09-28: Added centralized authenticated navigation for Estimates, Inventory, Collections, Work Orders, Purchasing, People, and Settings; Estimates is the default. Drawer selection navigates, highlights the current item, and dismisses the drawer. System Back closes the drawer first and then follows destination history; sign-out removes the protected host immediately. Reserved the `estimates/{estimateId}` route shape without implementing it. `git diff --check` passes; Gradle build and on-device route/drawer/Back/sign-out checks remain pending because the wrapper JAR and device environment are unavailable.
- 2026-09-28: Replaced only Estimates placeholder with API-backed paged browsing, debounced name search, estimateState/clientState filters, detail tabs, and a visibility-aware read-only client preview. Added optional, bounded list pagination and null clientState filtering in sibling API while preserving unpaginated array response behavior. API focused tests and TypeScript build pass; Android compile/tests and emulator visual inspection remain unavailable due wrapper/native Gradle issues.

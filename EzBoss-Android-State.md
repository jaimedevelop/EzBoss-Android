# EzBoss Android State

Updated: 2026-10-27

## Current state

A single-module native Android app uses Kotlin, Jetpack Compose, Auth0 Android SDK, a reusable API client, secure credential persistence, branded login, and an authenticated shell. Dashboard is now the default authenticated page, with native recent documents, work orders, accounting, and personnel sections. Estimates has API-backed list and detail browsing plus a read-only client-facing preview. Inventory, Collections, Work Orders, Purchasing, People, and Settings remain placeholders. Mobile sign-up remains TODO.

## Dashboard (2026-10-05)

- `app/src/main/java/pro/ezboss/mobile/Dashboard.kt`: native Compose adaptation of the current web dashboard. Orange welcome header, recent estimates/invoices with shared 7/30/90-day sent-date filter, up to five documents per kind, status and amount, service/billing addresses, expiration, and detail links. View all opens the corresponding estimate-list filter. Invoice balance uses the source estimate ledger when linked, approved payments only, and shows unavailable when fields are absent.
- Uses authenticated GET `/estimates`, `/clients`, `/profile`, `/work-orders`, `/dashboard-accounting/{pending,accumulated}`, and `/dashboard-personnel`. Profile timezone/currency apply to recent documents; accounting uses its response currency and device timezone, matching the web accounting request. Unavailable profile preferences are disclosed as device timezone/USD fallback. Each main data section has loading/error/retry; refresh reloads all sections. Filters are saveable, and requests are canceled with their composition.
- Daily/weekly/monthly accounting and personnel filters use existing API contracts. Personnel uses vertical worker cards for narrow screens and includes metric explanations; unavailable metrics stay unavailable. Work-order scheduling remains unavailable under the current contract, with expandable unscheduled open orders. Work Orders destination itself remains a placeholder.
- `MainActivity.kt`: Dashboard is the first drawer item and authenticated start route. Estimates now accepts optional `type` query input to initialize its filter; list/detail navigation and authentication remain protected.
- `app/src/test/java/pro/ezboss/mobile/DashboardDateTest.kt`: verifies company-timezone timestamp conversion, unchanged date-only values, and invalid/missing dates.
- Verification: Kotlin compilation and debug APK packaging passed. Initial full unit suite: 5 passed, 1 failed in the pre-existing `EstimateMappingTest` (`JSONObject` Android JVM stub). Final billing-address build/focused test rerun pending. `git diff --check` passed. Emulator/device checks unavailable because ADB permission was declined; layout, live data, login, and route interactions have not been verified on-device.

## Architecture and file locations

- `app/src/main/java/pro/ezboss/mobile/MainActivity.kt`: Compose app entry, branded Sign In screen, `AuthViewModel`, and authenticated shell. Estimates and `estimates/{estimateId}` are authenticated routes; the other six destinations remain title/Coming soon placeholders. The shell profile, drawer, and Sign Out wrap the protected host. The former persistent "EzBoss" top bar and floating hamburger/close button have been removed. A rightward swipe starting within the leftmost 24dp of the safe drawing area opens the sidebar after 48dp of drag; this edge region excludes Android system gestures. Scrim taps, system Back, and destination selection dismiss it. A custom accessibility action on the shell also opens/closes navigation.
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

- 2026-10-25: Launch planning source review confirmed `MainActivity.kt` still routes destinations other than Dashboard/Estimates to `PlaceholderDestination`. No Android behavior/configuration changed and no fresh Android build/device tests ran. Proposed weekly cross-platform scope, native companion milestones, verification gates and January schedule are recorded in `../docs/JANUARY_2027_LAUNCH_PLAN.md`; proposed native work is not implemented or accepted scope. Existing Android TODOs remain open.

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

- 2026-10-05: Replaced the floating hamburger/close button with left-edge swipe opening in `app/src/main/java/pro/ezboss/mobile/MainActivity.kt`. Added an accessibility open/close action; retained scrim, Back, destination selection, and sign-out behavior. No configuration changes. `git diff --check` passed. Kotlin compilation was blocked by sandbox access to the Gradle cache; requested escalation was declined. Compilation and on-device swipe/system-back/accessibility checks remain pending.

- 2026-10-07: Dashboard greeting now uses `/profile.firstName`, matching web, then the account first name or User. Added `data/AccountNames.kt` normalization and applied it to account displayName/roleName in `data/ApiClient.kt`, preventing JSON null/legacy null strings in the sidebar. Added `data/AccountNamesTest.kt` for Joaquin/Jaime, profile precedence, contractor account fallback, and missing/null names. No configuration changes. Whitespace/source checks passed; compile and focused tests remain unverified because Gradle cache escalation was declined. Live account/device verification remains pending.


## October 26 launch verification follow-up

- Canonical project confirmed by Joaquin. Added `org.json:json:20250517` as a JVM-only test dependency in `app/build.gradle.kts`; fixes the reproduced Android stub `JSONObject.opt` failure without changing app runtime behavior.
- Fresh `:app:assembleDebug :app:testDebugUnitTest` succeeds with cached Adoptium Java 21.0.7 and Gradle 8.13. Eight unit tests pass. Debug APK: `app/build/outputs/apk/debug/app-debug.apk`. Logs: `../docs/launch/evidence/2026-10-26/android-build-tests-retry.log`.
- `adb devices -l` reports no connected device; install, live Auth0, list/detail and logout remain unverified. Initial assembly succeeded while unit tests failed; final combined rerun succeeds after the dependency fix.
- Scope, four platform checklists, local TEST fixtures and outstanding cases: `../docs/launch/`. Selected cases remain unaccepted; native placeholders, SaaS/store billing, push and broader native implementation are still TODO. Auth0 TEST identity provisioning was blocked by connection-list API HTTP 403; no production data/configuration changed.
- Preserved the pre-existing state-document edits. No physical-device acceptance or signing/distribution is claimed.


## October 27 native auth/configuration increment

- `auth/AuthConfiguration.kt`: fail-closed validation for domain-only Auth0 hosts,
  public Native client IDs, audience and HTTPS API URLs without user info, query,
  fragment or invalid port. Callback construction follows the application ID.
- `app/build.gradle.kts`: retains the existing local-properties precedence and
  JVM JSON fix; validates URL formats/placeholders and rejects named client-secret
  and management-secret properties without logging values. Only four public
  Auth0/API fields are generated. `APPLICATION_ID` supplies the SDK scheme.
- `AndroidManifest.xml`: one exact `/android/${applicationId}/callback` path,
  replacing the old prefix match; custom scheme has no HTTPS autoVerify.
- `AuthRepository.kt` / `MainActivity.kt`: preserve coroutine cancellation,
  serialize restore/login/retry/logout, remove raw Auth0 exception/description logs
  and UI output, clear stale credentials before switching accounts, clear identity
  credentials for 401/403, and remove protected navigation on business API 401.
  Business API 403 remains an endpoint permission denial; no server guards changed.
- `data/ApiClient.kt`: HTTPS configuration/token checks before transport, disabled
  redirects, same-origin resource requests, and rejection of inactive/malformed
  `/users/me` identities. API errors use fixed messages, not response bodies.
- `scripts/check-config.py`: verifies the actual merged callback against generated
  BuildConfig (one exact path/scheme/host, no autoVerify/cleartext, public fields).
  README records setup, verification and remaining tenant delegated-access action.
- Verification: Gradle 8.13 + cached Adoptium Java 21; offline debug assemble and
  **17 JVM tests pass**. Expected failures verified for HTTP API URL, blank client
  ID and server-secret property; secret sentinel absent from logs. Actual merged
  callback/configuration checker passes. Evidence:
  `../docs/launch/evidence/2026-10-27/native-foundation/`.
- No emulator started or device installed in this task; build daemon exited.
  Tenant allowlists, Native app identity/user-delegated access, live callback,
  owner/employee login, refresh rotation and logout still require live verification.
  Existing business screens/placeholders and server authorization were preserved.


## October 28 AM native session task (implemented October 27)

- `auth/AuthRepository.kt` now serializes secure credential reads/rotation, login persistence and deletion through `auth/CredentialGate.kt`. SDK callback completion is awaited even after caller cancellation, so logout deletes credentials after any pending rotation finishes. Deletion runs despite caller cancellation; cancelled login cannot save credentials. Invalid stored sessions are cleared. Requests ask for 30 seconds of token validity before use. Credential descriptions redact the access token.
- `MainActivity.kt` revalidates `/users/me` when an authenticated app returns to foreground. `auth/SessionEpoch.kt` rejects obsolete resource results and 401s after revalidation/logout/account switch, preventing old calls from delivering data or clearing a newer session. Protected UI disappears on session failure. Resource 403 remains a business denial.
- `AndroidManifest.xml` disables application backup; `scripts/check-config.py` verifies the merged setting as well as existing callback/config boundaries.
- Focused JVM tests cover credential renewal/deletion ordering, cancellation while waiting for SDK completion, token description redaction and stale-session results. Verification and live prerequisites: `../docs/launch/OCTOBER_28_NATIVE_SESSION.md`. No emulator/device launched. Live PKCE callback, Keystore persistence and refresh rotation still require authorized staging/device acceptance; unit tests do not establish provider behavior.

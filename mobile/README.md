# Recharge mobile app (Android and iOS)

One Kotlin Multiplatform project. All logic and all screens are written once in the `shared` module and run on
Android and iOS. The UI is Compose Multiplatform. It talks to the Recharge backend.

> **Status:** this project has not been built or run yet (tasks T-107 and T-108 in `recharge-specs/13-tasks.md`).
> The code was checked for syntax and for unresolved project imports only. Expect to fix some compile errors on the
> first build; the most likely places are listed at the end of this file.

## What is shared and what is not
| Part | Where | Android | iOS |
|---|---|---|---|
| Screens, navigation, view models | `shared/commonMain` | same code | same code |
| Use cases, models, phone number check | `shared/commonMain` | same code | same code |
| API client, repository, sign-in | `shared/commonMain` (Ktor) | OkHttp engine | Darwin engine |
| Telemetry recorder, queue, uploader | `shared/commonMain` (SQLDelight) | Android SQLite driver | native SQLite driver |
| Token storage | `PlatformServices.secureSettings` | encrypted preferences | Keychain |
| Consent storage | `PlatformServices.plainSettings` | SharedPreferences | NSUserDefaults |
| Network monitoring | `NetworkMonitor` | ConnectivityManager | NWPathMonitor |
| Money format, language | `PlatformServices` | java.text | NSNumberFormatter |
| Push notifications | app shell | FCM (`RechargeMessagingService`) | not built yet (T-110) |
| App entry point | app shell | `MainActivity` | `iOSApp.swift`, `ContentView.swift` |

Texts are in `shared/src/commonMain/composeResources/values` (English) and `values-de` (German).

## Structure
```
shared/src/commonMain/kotlin/com/app/recharge
  AppGraph.kt            builds and connects all objects (replaces Hilt)
  App.kt                 RechargeApp(): root composable, consent dialog, lifecycle events
  platform/              PlatformServices: the only interface each platform implements
  core/network           ApiClient, ApiResult, AdaptiveTimeouts, NetworkState, EndpointTemplates, Traceparent
  core/telemetry         recorder, queue, uploader, flusher, consent store, session and journey ids
  core/security          TokenStore
  core/ui                theme, loading/error states, network banner
  feature/auth           demo sign-in (replace with the company identity provider)
  feature/recharge       data (API, repository), domain (models, validator, use cases), ui (screens, view models)
shared/src/androidMain   AndroidPlatform, AndroidNetworkMonitor
shared/src/iosMain       IosPlatform, IosNetworkMonitor, MainViewController
shared/src/commonTest    tests that run on both platforms
androidApp/              Application, Activity, FCM service, manifest, icons
iosApp/                  SwiftUI shell and project.yml (the Xcode project is generated from it)
```

## Run on Android
1. Start the backend with the `demo` profile (see its README). The emulator reaches it at `http://10.0.2.2:8080`.
2. Open this folder in Android Studio and run `androidApp` on an emulator (API 26 or newer).
   Command line: `./gradlew :androidApp:installDebug` (needs the Android SDK; set `sdk.dir` in `local.properties`).
3. A real phone: `adb reverse tcp:8080 tcp:8080`, then
   `./gradlew :androidApp:installDebug -PapiBaseUrl=http://localhost:8080/`.
4. Optional push: put your Firebase `google-services.json` in `androidApp/`. Without it the app runs and skips FCM.

## Run on iOS (needs a Mac with Xcode 15 or newer)
1. Start the backend with the `demo` profile on the same Mac. The simulator reaches it at `http://localhost:8080`.
2. Create the Xcode project (once, and again after changing `project.yml`):
   ```bash
   brew install xcodegen
   cd iosApp && xcodegen
   ```
3. Open `iosApp/iosApp.xcodeproj`, choose an iPhone simulator and press Run. The first build is slow: Xcode calls
   `./gradlew :shared:embedAndSignAppleFrameworkForXcode` to build the Kotlin code into the `Shared` framework.
4. A real iPhone: change `backendUrl` in `iosApp/iosApp/ContentView.swift` to the Mac's address on the local
   network, and select your development team under Signing in Xcode.

Java 17 must be installed for Gradle on both platforms.

## Tests
```bash
./gradlew :shared:testDebugUnitTest        # shared tests on the JVM, plus the Android money format test
./gradlew :shared:iosX64Test               # the same shared tests on an iOS simulator, Intel Mac
./gradlew :shared:iosSimulatorArm64Test    # the same, Apple silicon Mac
./gradlew :shared:allTests                 # all of the above
```
158 tests: view models, use cases, the API client and repository against a fake HTTP engine, sign-in, telemetry
recorder and uploader, and the small helpers.

## What changed from the Android-only app
| Before | Now | Effect |
|---|---|---|
| Hilt | `AppGraph`, written by hand | no code generation; one file shows how everything is connected |
| Retrofit + OkHttp interceptors | `ApiClient` on Ktor | same headers, timeouts, 401 refresh and call timing; DNS/connect/TLS times are no longer measured |
| Room + WorkManager | SQLDelight + coroutine flusher | telemetry is sent while the app runs, not while it is closed |
| DataStore, EncryptedSharedPreferences | multiplatform-settings | Keychain on iOS |
| `SavedStateHandle` for the idempotency key | `rememberSaveable` in `PaymentRoute` | same rule: one key per attempt, reused after rotation and process death |
| Push opens the order | Push opens the app | deep link to be added again (T-111) |
| `strings.xml` | Compose resources | `Res.string.<name>`; placeholders are written `%1$d` |

## Where the first build is most likely to need fixes
1. Generated names: `Res.string.*` (package `com.app.recharge.resources`) and the SQLDelight classes
   (`com.app.recharge.db.TelemetryDatabase`, `telemetryQueueQueries`).
2. Multiplatform navigation and lifecycle (`navigation-compose 2.8.0-alpha10`, `lifecycle 2.8.3`): type-safe routes,
   `viewModel { }`, `LifecycleEventEffect`.
3. iOS interop in `shared/src/iosMain`: `NWPathMonitor` in `IosNetworkMonitor`, `KeychainSettings` and
   `NSNumberFormatter` in `IosPlatform`.
4. The XcodeGen project: framework search path and linker flags in `iosApp/project.yml`.
5. Library versions in `gradle/libs.versions.toml` must match each other (Kotlin 2.0.20, Compose Multiplatform 1.7.0).

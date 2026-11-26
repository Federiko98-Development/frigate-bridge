# Frigate Bridge

Android (and later iOS) client for Frigate 0.18+ servers. Kotlin Multiplatform: shared logic, native interfaces.

## Public repository

The code is open source. Never commit: passwords, tokens, signing keys or certificates, `google-services.json`,
addresses or domains of real servers, real camera or group names, camera images or recordings, or Frigate
responses that have not been anonymized. Use made-up values in tests and examples
(e.g. `https://frigate.example.com`).

## Language and commits

- Everything in the repository is in English: code, comments, documentation, CI and commit messages.
  Other languages appear only in the app's translation resources (`values-<language>/`).
- No co-author or tool attribution lines in commit messages or pull requests.

## Priority

**Android only** for now. The SwiftUI interface is postponed: `iosApp/` holds a placeholder.
`shared` stays multiplatform: no Android or Java APIs in `commonMain`.

## Modules

- `shared/`: models, Frigate client, repositories, ViewModels with `StateFlow`. No UI.
  - `commonMain` for logic; `androidMain` and `iosMain` only for platform parts (`expect`/`actual`).
  - `commonTest` for tests (kotlin.test, coroutines-test, Turbine).
- `androidApp/`: Jetpack Compose, Material 3 Expressive. Screens in `ui/<feature>/`, theme in `ui/theme/`.
- `iosApp/`: Xcode project.

## Conventions

- Every screen has a contract: a state (`data class XxxState`) and actions as functions of `XxxViewModel`.
- In Compose: `XxxRoute` gets the ViewModel with `koinViewModel()` and collects the state with
  `collectAsStateWithLifecycle()`; `XxxScreen` is stateless, takes state and callbacks, and has a `@Preview`.
- Register ViewModels in `shared/.../di/SharedModule.kt`. For iOS also add a function to
  `iosMain/.../SharedViewModelStore.kt`.
- Only the client in `shared` calls Frigate; screens never call the API.
- Every object that belongs to a server carries its `serverId` (e.g. `CameraRef(serverId, cameraName)`).
- Within a feature: `shared` with tests first, then the Compose screen.
- UI text is never hardcoded: English in `res/values/strings.xml`, Italian in `res/values-it/strings.xml`.
- Library versions live only in `gradle/libs.versions.toml`.

## Commands

Gradle needs a JDK; the one bundled with Android Studio works (set `JAVA_HOME` to it).

```bash
./gradlew :androidApp:assembleDebug
```

```bash
./gradlew :shared:testAndroidHostTest
```

```bash
./gradlew ktlintCheck :androidApp:lintDebug
```

`./gradlew ktlintFormat` fixes formatting. `./gradlew :androidApp:installDebug` installs on a connected phone
or emulator.

Check that `commonMain` stays portable (compiles the iOS code without needing Xcode; linking the framework,
which is where SKIE runs, does need it):

```bash
./gradlew :shared:compileKotlinIosSimulatorArm64
```

## Resuming iOS

- Connect the `Shared` framework to SwiftUI screens: one `@Observable` store per ViewModel, which uses
  `SharedViewModelStore` and calls `clear()` on destruction; `startSharedKoin()` at launch.
- Linking the framework and SKIE have not been tried yet: verify them first.
- The Xcode build phase does not read shell profiles: set `JAVA_HOME` in the "Compile Kotlin Framework" script.
- The wizard's Xcode project has deployment target 18.2 (the target is iOS 17).

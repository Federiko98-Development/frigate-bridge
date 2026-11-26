# Frigate Bridge

An unofficial mobile client for [Frigate](https://frigate.video) 0.18+ servers, built with Kotlin Multiplatform.
Logic is shared in `shared`; the interface is native, in `androidApp` (Jetpack Compose, Material 3 Expressive).
The iOS interface (`iosApp`, SwiftUI) will come after the Android one.

> Early stage: the project skeleton is in place, and no Frigate features are implemented yet.

## Requirements

- Android Studio (its bundled JDK is enough for Gradle)
- Android SDK 37; the app runs on Android 8 (API 26) and later

## Build and test

```bash
./gradlew :androidApp:installDebug
```

```bash
./gradlew :shared:testAndroidHostTest
```

Project structure, conventions and more commands: [CLAUDE.md](CLAUDE.md).

## Languages

English (default) and Italian. On Android 13 and later the app language can be chosen in the system's app settings.

## License

[Apache 2.0](LICENSE). This is an independent project, not affiliated with Frigate.

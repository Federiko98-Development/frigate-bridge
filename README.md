# Frigate Bridge

An unofficial mobile client for [Frigate](https://frigate.video) 0.18+ servers, built with Kotlin Multiplatform.
Logic is shared in `shared`; the interface is native, in `androidApp` (Jetpack Compose, Material 3 Expressive).
The iOS interface (`iosApp`, SwiftUI) will come after the Android one.

> Early stage. Live view and recordings are a first technical version; the timeline, events and notifications
> are not implemented yet.

## What works

- Several Frigate servers, kept apart, with a default one and quick switching
- Cameras as a grid or a list, a recordings tab, and settings for theme, language and previews
- Sign-in with automatic renewal of the session, and a local plus an alternative address per server
- Self-signed certificates, accepted once per server after checking the fingerprint
- Cameras and camera groups of the selected server
- Camera previews in the grid, live view (over HTTPS, or WebRTC), and playback of the last recorded minute
- Full screen in landscape, PTZ with saved positions, and switches for detection, recording, snapshots and motion

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

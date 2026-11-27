# Watchdeck

An unofficial mobile client for [Frigate](https://frigate.video) 0.18+ servers, built with Kotlin Multiplatform.
Logic is shared in `shared`; the interface is native, in `androidApp` (Jetpack Compose, Material 3 Expressive).
The iOS interface (`iosApp`, SwiftUI) will come after the Android one.

> Early stage. Push notifications are not implemented yet.

## What works

- Several Frigate servers, kept apart, with a default one and quick switching
- Cameras as a grid or a list, and settings for theme, language and previews
- Sign-in with automatic renewal of the session, and a local plus an alternative address per server
- Self-signed certificates, accepted once per server after checking the fingerprint
- Cameras and camera groups of the selected server
- Camera previews in the grid, live view (over HTTPS, or WebRTC), and playback of the last recorded minute
- Recordings on a timeline with a date picker, alerts and detections filtered by date, time, camera and object, export and sharing of clips
- A page for each alert or detection with its video, reachable from a link of another app
- Keeps what it shows without a network, and reconnects when the phone changes network
- Pinch to magnify the picture of the live view and of recordings
- Photos from the live view and from recordings, and clips recorded from the live view, saved to the gallery
- For administrators: server status, exported clips to play, download and delete, and events saved on the server
- An optional app lock with fingerprint, face or screen lock
- Camera groups, where each camera can be switched on or off
- Your own password can be changed from the app
- Hold an event to choose several: share their videos or mark them seen; administrators can also delete them with their recordings
- One switch leaves a camera with its live view alone, without recording or detection (administrators)
- For administrators: users, roles with the cameras each can see, camera groups (create, edit, reorder, delete) and restarting Frigate
- Full screen in landscape, PTZ with saved positions, and switches for detection, recording, snapshots and motion

## Links to an event

`watchdeck://event?id=<review id>&server=<address>` opens the app on that alert or detection. The address is one
of those saved for the server in the app (same host and port); without it the default server is used. Another app
can send such a link, for example a notification of the Home Assistant companion app (not tried yet):

```yaml
triggers:
  - trigger: mqtt
    topic: frigate/reviews
conditions:
  - condition: template
    value_template: >-
      {{ trigger.payload_json['type'] == 'new' and trigger.payload_json['after']['severity'] == 'alert' }}
actions:
  - action: notify.mobile_app_your_phone
    data:
      title: Frigate
      message: "{{ trigger.payload_json['after']['camera'] }}"
      data:
        clickAction: >-
          deep-link://watchdeck://event?id={{ trigger.payload_json['after']['id'] }}&server=https://frigate.example.com
```

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

## Release build

A release is shrunk with R8. To sign it, create a key once and keep it safe: updates must be signed with the
same one.

```bash
keytool -genkeypair -keystore release.jks -alias watchdeck -keyalg RSA -keysize 4096 -validity 10000
```

Copy `keystore.properties.example` to `keystore.properties`, fill in the passwords, then build the APK
(`androidApp/build/outputs/apk/release/`). Without that file the APK is left unsigned. Both the key and the file
are ignored by git.

```bash
./gradlew :androidApp:assembleRelease
```

## Languages

English (default) and Italian. On Android 13 and later the app language can be chosen in the system's app settings.

## License

[Apache 2.0](LICENSE). Third-party material is listed in [NOTICE](NOTICE).

## Trademarks

Watchdeck is an independent project. It is not affiliated with, endorsed or sponsored by Frigate, Inc.
"Frigate" and "Frigate NVR" are trademarks of Frigate, Inc.; they are used here only to say which servers the app
works with. See Frigate's [trademark policy](https://github.com/blakeblackshear/frigate/blob/dev/TRADEMARK.md).

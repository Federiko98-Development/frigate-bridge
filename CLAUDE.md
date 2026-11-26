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
  - Packages: `frigate/` (the only code that calls Frigate), `server/` (saved servers, secrets, sessions),
    `camera/` (cameras and groups), `ui/` (ViewModels).
- `androidApp/`: Jetpack Compose. Screens in `ui/<feature>/`, shared components in `ui/designsystem/`,
  theme in `ui/theme/`.
- `iosApp/`: Xcode project.

## Design: Material 3 Expressive everywhere

Every screen, forms included, uses Material 3 Expressive. A themed classic Material component is not enough.

- Container-first layout: content sits in tonal, generously rounded containers; no shadows or dividers.
  At most one hero card (`primaryContainer`) per screen.
- Lists and form fields are segmented groups (`SegmentedListItem`, `ListItemDefaults.segmentedShapes`).
- Buttons and icon buttons use the shape-morphing `shapes` overloads; accents use `MaterialShapes`.
- Choices between a few options are connected `ToggleButton` groups; menus use `ExpressiveMenu`.
- `ContainedLoadingIndicator`, bottom sheets for decisions that need explanation.
- Inside a server a bottom bar (`MainTabBar`) switches tabs, with a plain fade (never scale a screen); each tab screen places it in
  its own `Scaffold`, so it moves with the page when something opens on top. Back from a tab returns to cameras.
- Screens that read a server refresh by dragging down (`RefreshableContent`); there is no refresh button.
- Controls depend on the camera: hide what it cannot do (no audio, no recording in the configuration, no PTZ
  feature, not an administrator) instead of disabling it. The app is portrait except for full screen video.
- Compact sizes: standard top app bars, default or small buttons and FAB, titles no larger than `titleLarge`.
  Medium and large variants looked oversized on a real phone (384dp wide).
- Every `NavHost` sets all transitions, including `predictivePopEnterTransition` and
  `predictivePopExitTransition`: their default shrinks the screen during the back gesture.
- Check each screen on a device or emulator, in light and dark, before calling it done, back gesture included.

## Frigate facts the client relies on (verified against 0.18.3)

- `POST /api/login` answers with an empty body; the token is in a cookie whose name is configurable.
- The server renews cookie sessions only, never `Authorization: Bearer` tokens: log in again on 401.
- `/api/version` requires authentication behind the proxy, so it doubles as the login probe.
- Login returns 404 when authentication is disabled, and 429 when rate limited.
- A camera group's `cameras` is a list or a comma-separated string.
- `PUT /api/camera/<name>/set/<feature>` with `{"value": "ON"|"OFF"}` needs the admin role and lasts until
  Frigate restarts. Motion cannot be turned off while detection is on; recording cannot be turned on if the
  configuration disables it. `/api/config` is cached by the proxy for 5 s: send `X-Cache-Bypass`.
- Movement goes over the WebSocket `/ws` as `{"topic": "<camera>/ptz", "payload": "MOVE_LEFT"}`, ended by `STOP`;
  `GET /api/<camera>/ptz/info` lists features (`pt`, `zoom`) and presets.
- The self-signed certificate does not name the server's address.
- WebRTC signalling is one request: `POST /api/go2rtc/webrtc?src=<stream>` with a JSON offer, answered with a
  JSON answer. Media then flows to go2rtc's candidates (port 8555). No STUN server is used by the app.
- Recordings play as HLS from `/vod/<camera>/start/<s>/end/<e>/master.m3u8` (not under `/api`), with the same
  Bearer header on every request.

## Android platform notes

- Keep `androidx.navigationevent` at 1.2.0 or later: with 1.1.2 the app crashed when the back gesture was made
  with a menu open.
- Android 17 (target 37) blocks the local network until `ACCESS_LOCAL_NETWORK` is granted; connections time
  out without it.
- `NetworkMonitor` follows the default network (one without internet counts): on a new one the server is read
  again, which also picks between its two addresses; offline, screens keep what they show and say so.
- The app lock (`LockViewModel`, `security/AppLock.kt`) does not cover the app: while locked, `AppNavigation` is not
  composed, so no video, preview or link runs behind it. It locks on start and after 10 s out of sight.
  It opens without a prompt only when the phone has no screen lock (`isDeviceSecure`): never on another error.
- Plain HTTP is allowed on purpose (Frigate on a home network); the server form warns about it.
- Passwords and tokens are encrypted with an Android Keystore key; backups and device transfer are disabled.

## Conventions

- Every screen has a contract: a state (`data class XxxState`) and actions as functions of `XxxViewModel`.
- In Compose: `XxxRoute` gets the ViewModel with `koinViewModel()` and collects the state with
  `collectAsStateWithLifecycle()`; `XxxScreen` is stateless, takes state and callbacks, and has a `@Preview`.
- Register ViewModels in `shared/.../di/SharedModule.kt`. For iOS also add a function to
  `iosMain/.../SharedViewModelStore.kt`.
- Only the client in `shared` calls Frigate; screens never call the API.
- Every object that belongs to a server carries its `serverId` (e.g. `CameraRef(serverId, cameraName)`).
- Within a feature: `shared` with tests first, then the Compose screen.
- Do not run tests unless asked: compiling is enough to check a change. CI runs them on every push to main.
- Tests use fake Frigate servers (`FakeFrigate`, `FakeNetwork`) and made-up data.
- UI text is never hardcoded: English in `res/values/strings.xml`, Italian in `res/values-it/strings.xml`.
- Library versions live only in `gradle/libs.versions.toml`.

## Video

- `shared` handles signalling and URLs; the platform provides `LivePeer` (Android: `video/WebRtcPeer`) and the
  player (Media3 ExoPlayer, with the server's accepted certificate).
- G.711 camera audio arrives from go2rtc as uncompressed FLAC that Android's decoders cannot play (sample
  numbers are coded wrongly). the renderer in `video/LiveRenderersFactory.kt` reads it.
- The video composable is `movableContentOf`, and the activity handles rotation itself, so entering full screen
  does not restart the stream.
- A live connection exists only while its video is on screen and the app is in the foreground.
- Live view order, as in Frigate's web interface: go2rtc's fragmented MP4 over HTTPS first (about 1 s to the
  first frame), WebRTC only if that fails (it needs port 8555 reachable). The protocol on `/live/mse/api/ws`: send `{"type":"mse","value":"<codecs>"}`, receive one text message with
  the content type, then the file as binary messages.

- The camera grid shows `latest.jpg` at low resolution, reloaded only for visible cards on the front screen.

## Recordings and events

- Camera groups are chosen with `CameraGroupBar`: tap selects, holding opens the group's cameras. No extra button.
  In full screen the timeline sits in a bottom sheet that can be pulled down to its handle.
- The timeline holds on to the starts of recordings and moments. Left in a pause, the video stops and says so;
  only jumps (skip buttons, previous and next) pass over pauses, in their direction of travel.
- `TimelineViewModel` loads one day of segments and plays it one hour at a time: a whole day does not fit in one
  VOD playlist. Frigate leaves the pauses out of the video, so `PlaybackWindow` translates positions into times.
- Review moments (`/api/review`, always with `after`) mark the timeline and fill the events tab. Their pictures
  and the exports are files served outside `/api` (`/clips/…`, `/exports/…`) that need the same Bearer header.
- A review moment has its own page: `/api/review/{id}` (which does not say whether it was viewed), played as
  recordings from 4 s before it to 4 s after it, as Frigate's web interface does.
- `frigatebridge://event?id=…&server=…` opens a review moment, only on a saved server (`server/EventLink.kt`).
  Anything on the phone may send such a link: keep it read only, apart from marking the moment viewed.
- Phase 7 (push notifications) is waiting on a decision: own bridge with Firebase, or Home Assistant.

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

`./gradlew :androidApp:assembleRelease` builds the shrunk release, signed only if `keystore.properties` exists
(see README). R8 problems only show when the app runs: try a release after adding a library that uses native code
or reflection.

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
- Not implemented on iOS yet: Keychain storage (`MemorySecretStore` loses passwords when the app closes),
  accepting a self-signed certificate, following the network (`NWPathMonitor`), and the local network usage description in `Info.plist`.
- The Xcode build phase does not read shell profiles: set `JAVA_HOME` in the "Compile Kotlin Framework" script.
- The wizard's Xcode project has deployment target 18.2 (the target is iOS 17).

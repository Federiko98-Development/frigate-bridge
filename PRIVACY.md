# Watchdeck privacy policy

Last updated: 28 November 2026

Watchdeck is a client for Frigate NVR servers that you, or someone who gave you access, run. It is an independent,
open source project and is not affiliated with Frigate, Inc.

## What the developer collects

Nothing. Watchdeck has no accounts of its own, no analytics, no advertising and no crash reporting, and the
developer runs no server that the app talks to. The developer receives none of your data.

## What stays on your phone

- The addresses and names of the servers you add, and your user names.
- Your passwords and session tokens, encrypted with a key kept in the Android Keystore. For a server that signs you
  in through an external provider, the tokens that provider gives the app, encrypted the same way.
- Your settings, such as the theme and which cameras you hide.
- Videos you share are first downloaded to the app's temporary storage.

Android backup and device-to-device transfer are turned off for the app. Uninstalling it removes this data.
Photos and clips that you choose to save go to your phone's gallery and stay there until you delete them.

## What is sent, and to whom

The app connects to the servers you add, to sign you in, show live and recorded video and make the changes you ask
for. What those servers store and log is decided by whoever runs them.

For a server that uses an external sign-in, the app also talks to the sign-in provider whose address you enter.
You type your password in your phone's browser, on the provider's page: the app never receives it. The app gets
tokens from the provider and sends them to that server, at the addresses you saved for it. What the provider stores
and logs is decided by whoever runs it.

An address that starts with `http://` is not encrypted: others on the same network could read the password and the
video. The app warns you about it. Use `https://` whenever you can.

Your data leaves the app in other ways only when you ask for it: when you share a video or a picture with another
app, or open the link to the source code in your browser.

## Permissions

- Internet and network state: to reach your servers and to load pictures less often on metered connections.
- Local network (Android 17 and later): to reach a server on your home network.
- Biometrics: for the optional app lock. Android checks your fingerprint or face; the app never receives them.
- Keeping the phone awake while a video is shown.

The app does not use your phone's camera, microphone, location or contacts.

## Children

Watchdeck is not directed at children.

## Changes and contact

Changes to this policy are published in this file, with a new date. For questions, open an issue at
<https://github.com/Federiko98-Development/watchdeck/issues>.

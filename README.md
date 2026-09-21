# BlazeMessenger

BlazeMessenger is an Android messenger for one-to-one chats, groups, voice and video calls, stories, and account controls. The interface is an original warm, ember-on-paper design with English and Romanian strings. It is not a copy of any existing product's layout.

Package: `ro.blazemessenger.app`

## What is implemented

- Kotlin, Jetpack Compose, Material 3, Navigation, Hilt, DataStore, Work-style foreground transfers
- Email and password accounts, email verification, password reset, sign-out, and account deletion
- Google sign-in through Credential Manager when a Web client ID is configured
- Profiles with photo, username, display name, bio, and a Firebase user id
- User search, block, unblock, and reports
- Direct and group chats with text, emoji, images, video, audio, and documents
- Upload progress, download-on-open, reply, forward, edit, delete, reactions, pin, mute, typing, presence, last seen, read receipts, and paged history
- WebRTC voice and video calls with Firestore signaling, STUN, optional TURN, mute, speaker, camera toggle, camera switch, duration, and call history
- Incoming calls through a high-priority FCM data message, a call foreground service, a full-screen notification where Android allows it, a lock-screen activity, ringtone, vibration, and a 45 second timeout
- A self-managed Telecom `ConnectionService` so the system can associate the call. If Telecom rejects the registration, the notification path still runs
- Original ringtone and notification chimes, a second softer built-in tone, preview, in-app volume, vibration, and a persistent custom audio file picked with the system document picker
- Notification channels, message grouping, previews, deep links, and Android 13 notification permission
- Theme, language, privacy, blocked people, cache clearing, and about screens
- Firestore, Storage, and Realtime Database rules, plus Cloud Functions for fan-out notifications, rate limiting, missed calls, and account deletion
- GitHub Actions that builds a release APK, an App Bundle, and a ZIP on GitHub-hosted runners

## What is not pretended

- The committed `app/google-services.json` is a placeholder. Until you replace it, account, chat, and call buttons cannot reach a backend. The setup screen says so.
- Google sign-in stays disabled, with an explanation, until `GOOGLE_WEB_CLIENT_ID` is set and the OAuth client exists in Firebase.
- Calls include Google's public STUN server. That is enough for many networks and not for every NAT. A TURN server is optional configuration. This repository has not certified calls on every carrier, and it does not claim to override Do Not Disturb, battery restrictions, or locked-down OEM policies.
- Without the Cloud Functions deployed, messages still sync through Firestore for open clients, but push notifications, server-side rate limiting, and the full account cleanup do not run. The client still deletes the Auth user and profile when it can.
- A release APK from CI is signed with the debug keystore unless the keystore secrets below are set. That is installable and is not a Play production signature.

## Backend

Firebase is the backend: Authentication, Firestore, Storage, Realtime Database for presence, Cloud Messaging, and Cloud Functions in `europe-west1`. Passwords stay in Firebase Auth. The APK does not contain a service account. Chat files are downloaded with the signed-in Storage SDK from a path, not from a public URL.

Setup steps: [docs/FIREBASE.md](docs/FIREBASE.md)

## Build and release

- Local commands and signing: [docs/BUILD.md](docs/BUILD.md)
- GitHub Actions artifacts and secrets: [docs/RELEASE.md](docs/RELEASE.md)

Trigger **Android CI** with a push to `main`, a pull request, or **Run workflow**. Artifacts are `BlazeMessenger-apk`, `BlazeMessenger-aab`, and `BlazeMessenger-release-zip` (`BlazeMessenger-release.zip`).

### Secrets

| Secret | When |
| --- | --- |
| `GOOGLE_SERVICES_JSON` | Build against your Firebase project |
| `GOOGLE_WEB_CLIENT_ID` | Enable Google sign-in in that build |
| `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` | Sign a Play release |
| `TURN_URL`, `TURN_USERNAME`, `TURN_PASSWORD` | Relay calls on restrictive networks |

## Project map

- `app/` Android application
- `firebase/firestore.rules`, `firebase/storage.rules`, `firebase/database.rules.json` security rules
- `firebase/functions/` notification, missed-call, and delete-account functions
- `.github/workflows/android.yml` CI

## Verification in this repository

Unit tests cover the policies and the call state machine. A release compile is the check that the Android project builds. Device checks for FCM delivery, WebRTC media, and lock-screen UI need two phones and a configured Firebase project, so they are not claimed as completed here.

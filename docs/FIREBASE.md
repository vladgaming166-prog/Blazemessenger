# Firebase setup

BlazeMessenger talks to Firebase Authentication, Cloud Firestore, Cloud Storage, Realtime Database, Cloud Messaging, and Cloud Functions. The repository ships with a placeholder `app/google-services.json` whose project id is `blazemessenger-unconfigured`. With that file the app installs and opens, then explains that the backend is not connected. It does not invent users, messages, or calls.

## 1. Create the project

1. Create a Firebase project in the [Firebase console](https://console.firebase.google.com/).
2. Add an Android app with package name `ro.blazemessenger.app`.
3. Download the real `google-services.json` and replace `app/google-services.json`.
4. Enable Email/Password authentication.
5. Enable Google as a sign-in provider and copy the **Web client ID**.
6. Create a Cloud Firestore database.
7. Create a Cloud Storage bucket.
8. Create a Realtime Database. Presence uses the path `status/{uid}`.
9. Upgrade the project to the Blaze plan. Storage security rules in this repo read Firestore membership with `firestore.get()`, which requires Blaze.

## 2. Android signing fingerprints

Google Sign-In needs the SHA-1 and SHA-256 of every keystore that installs the app.

Debug keystore, created automatically by the Android SDK:

```bash
keytool -list -v -alias androiddebugkey -keystore ~/.android/debug.keystore -storepass android
```

Add both fingerprints in Firebase Project settings for the Android app, download a fresh `google-services.json`, and put the Web client ID in `GOOGLE_WEB_CLIENT_ID` (see below). If the Web client ID is empty, the Google button reports that it is not configured instead of failing silently.

## 3. Deploy rules and functions

From the repository root, with the Firebase CLI logged in:

```bash
firebase deploy --only firestore:rules,firestore:indexes,storage,database,functions
```

Functions run in `europe-west1`. The Android client calls `deleteAccount` in that region. `onCallCreated` waits about 45 seconds and marks a still-ringing call as missed, so a missed call is recorded even if the caller process is gone. The function timeout is 70 seconds.

`deleteAccount` removes the Auth user, profile, username reservation, tokens, blocks, read cursors, stories, and profile images. Messages already delivered to other people are not rewritten; the app shows “Deleted account” when the profile is gone.

## 4. Local and CI configuration

Do not commit the real `google-services.json` if the repository is public. For GitHub Actions, store the file contents in the secret `GOOGLE_SERVICES_JSON`. The workflow writes it over the placeholder before building.

Optional values are read from the environment or `local.properties`:

| Name | Purpose |
| --- | --- |
| `GOOGLE_WEB_CLIENT_ID` | Web OAuth client ID for Google sign-in. Not a server secret, but keep production values out of git if you prefer. |
| `TURN_URL` | TURN URI, for example `turn:turn.example.com:3478` |
| `TURN_USERNAME` | TURN username |
| `TURN_PASSWORD` | TURN password |

Copy `local.properties.example` to `local.properties` for local builds. `local.properties` is gitignored.

## 5. What the rules enforce

- A user can write only their own profile, blocks, tokens, and read cursors.
- Usernames are reserved in `usernames/{name}` and can be created only by the signed-in owner.
- Chat documents and messages are readable only by `memberIds`.
- A message can be created only with `senderId` equal to the signed-in user, and text is limited to 4000 characters.
- Another member can change only the `reactions` map. The sender can edit or soft-delete their own message.
- Call documents and ICE candidates are readable only by the caller and callee.
- Reports can be created but not read back by clients.
- Stories are readable only before `expiresAt`.
- Chat files require Storage rules to confirm Firestore membership. Profile photos are readable by any signed-in user and writable only by the owner.
- The app stores storage paths, not public download URLs, and downloads bytes through the signed-in Storage SDK.

Client checks are not the security boundary. The rules are.

## 6. Push messages

Cloud Functions send data-only FCM messages:

- `type=message` with `chatId`, `title`, `body`, `mention`
- `type=incoming_call` with `callId`, `callerId`, `callerName`, `callerPhoto`, `callType`
- `type=missed_call` with `callerName`

The app creates notification channels and decides whether to show a preview from the on-device settings. Muted chats are skipped in the function when `members/{uid}.mutedUntil` is in the future.

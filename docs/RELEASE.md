# Release

## GitHub Actions artifacts

Every push to `main`, every pull request, and every manual run uploads:

| Artifact | Contents |
| --- | --- |
| `BlazeMessenger-apk` | Release APK |
| `BlazeMessenger-aab` | Release Android App Bundle |
| `BlazeMessenger-release-zip` | `BlazeMessenger-release.zip` with the APK, the bundle, and these docs |

Download them from the workflow run page. No local Android Studio install is required.

## Manual run

1. Open the **Actions** tab.
2. Choose **Android CI**.
3. Click **Run workflow**.
4. Leave **Create a GitHub Release** off to only build artifacts.
5. To publish a release, set **Create a GitHub Release** to true and enter a tag such as `v1.0.0`.

The release step attaches the APK, the AAB, and the ZIP. It fails if the tag is empty.

## Repository secrets

None of these are required for a debug-signed CI build. Set them when you want a real backend or a production signature.

| Secret | Required for | Format |
| --- | --- | --- |
| `GOOGLE_SERVICES_JSON` | A build that talks to your Firebase project | Full JSON file contents |
| `GOOGLE_WEB_CLIENT_ID` | Google sign-in inside that build | Web client ID |
| `KEYSTORE_BASE64` | Play-signed release | Base64 of the `.jks` file |
| `KEYSTORE_PASSWORD` | Play-signed release | Keystore password |
| `KEY_ALIAS` | Play-signed release | Key alias |
| `KEY_PASSWORD` | Play-signed release | Key password |
| `TURN_URL` | Calls across restrictive NAT | `turn:host:3478` |
| `TURN_USERNAME` | TURN auth | Username |
| `TURN_PASSWORD` | TURN auth | Password |

Create the keystore secret with:

```bash
base64 -w 0 blaze-release.jks
```

Paste the output into `KEYSTORE_BASE64`. Never commit the keystore or the base64 text.

## Play Console

Upload the AAB from the artifact, not the debug-signed APK, once `KEYSTORE_BASE64` is configured. Target SDK is 36. Minimum SDK is 26 (Android 8.0).

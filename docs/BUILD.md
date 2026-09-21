# Build

You do not need Android Studio to produce an installable APK. GitHub Actions on `ubuntu-latest` checks out the code, installs a JDK and the Android SDK, runs unit tests and lint, then builds a release APK, an Android App Bundle, and a ZIP.

## Local prerequisites

- JDK 21
- Android SDK platform 37 and build-tools 36
- A `local.properties` file with `sdk.dir=/path/to/Android/Sdk`

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
./gradlew :app:assembleRelease :app:bundleRelease
```

Outputs:

- `app/build/outputs/apk/release/`
- `app/build/outputs/bundle/release/`

## Signing

`assembleRelease` is minified with R8.

- If `KEYSTORE_PATH`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, and `KEY_PASSWORD` are set, the APK is signed with that keystore.
- If they are absent, the release build is signed with the debug keystore so the artifact can be installed on a device. That signature is not a Play production signature. The repository does not contain a keystore.

Generate a real upload key only on a machine you control:

```bash
keytool -genkeypair -v -keystore blaze-release.jks -alias blaze -keyalg RSA -keysize 2048 -validity 10000
```

Do not commit the `.jks` file.

## Tests that run without a device

`app/src/test` covers username rules, message length and edit window, send rate limiting, file type and size policy, call state transitions, stable direct-chat ids, and mentions. These tests do not need Firebase or an emulator.

Instrumented UI tests and calls on physical devices are not part of the default CI job.

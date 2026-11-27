# Building and publishing an Android release

## Production package

The public APK uses the package ID `com.magichandy.mobile` and appears as **MagicHandy** on the phone. Version 0.1.6 is the first production release. The SHA-256 fingerprint of the production signing certificate is `fef4b482e2826ee6b0ea4b2984910a7c36a320972d2a352acdfb2ea5ad23f036`. Use the same signing key for updates. [Public releases](https://github.com/MagicHandy/MagicHandy-Mobile/releases)

## APK for testing on a phone

Run `./gradlew :app:assembleDeviceTest :app:checkDeviceTestContents`. The installable APK is `app/build/outputs/apk/deviceTest/app-deviceTest.apk`. It uses the production core, is minified, and is signed with this workstation’s Android development key. Its app ID is `com.magichandy.mobile.devicetest` and its launcher name is **MagicHandy Test**. It has its own storage and can be installed alongside the public app. Use the same workstation key for subsequent test updates.

Install a newer phone-test APK over the existing MagicHandy Test app to preserve its local data. The APK requires Android 12 or later on a 64-bit ARM device. Chat and the separate Voice test download their own speech models. Settings › Voice › Advanced shows the test controls. [Voice design](voice-design.md) · [Voice test](voice-test.md#a-test-session)

The release script builds the minified APK, checks its packaged contents, verifies its signature with Android SDK `apksigner verify --print-certs`, and writes the APK and its SHA-256 checksum. Publishing is a separate owner action.

## Signing identity

The first production key has already been created. To restore it on a new signing machine, use the owner's encrypted backup and verify the certificate fingerprint above. For reference, the initial key ceremony uses the JDK’s `keytool` and a directory outside the checkout. This Windows example prompts for a password.

```powershell
& "$env:JAVA_HOME/bin/keytool" -genkeypair -storetype PKCS12 -keystore "D:/OwnerKeys/MagicHandy.p12" -alias magichandy -keyalg RSA -keysize 4096 -validity 10000
```

Keep an encrypted backup of the keystore and retain its password in the owner’s password manager. Record the public signing-certificate SHA-256 fingerprint separately. Android expects updates to use the same signing identity. Set the signing process’s password environment variables using your credential manager.

Create an untracked `keystore.properties` in the checkout root. Use forward slashes for a Windows keystore path. For a PKCS12 store the two password variables can reference the same value.

```properties
storeFile=D:/OwnerKeys/MagicHandy.p12
keyAlias=magichandy
storePasswordEnv=MAGICHANDY_STORE_PASSWORD
keyPasswordEnv=MAGICHANDY_KEY_PASSWORD
```

The signing configuration also accepts the conventional `storePassword` and `keyPassword` properties. The environment-backed configuration keeps password values out of the properties file. The root ignore rules cover the properties file and keystores. In its absence, `:app:assembleRelease` produces `app-release-unsigned.apk`.

## Build and verify

Set `versionName` to the intended numeric `X.Y.Z` release and increment `versionCode` for each Android update. From the checkout root, with the Android SDK and JDK configured, run:

```powershell
./tools/release/build-release.ps1
```

The script uses Gradle’s selected SDK build-tools and disables the configuration and build caches for signing. It verifies the staged APK before exposing the final directory. A different existing artifact for the same version causes the script to fail. The outputs are:

```text
build/releases/<versionName>/MagicHandy-<versionName>.apk
build/releases/<versionName>/SHA256SUMS
```

Compare the printed signer certificate fingerprint with the owner’s recorded fingerprint. The checksum file uses lowercase SHA-256 hex, two spaces and the APK basename. The checksum identifies the published bytes. The signature identifies the Android signing key.

`:app:checkReleaseContents` checks the Room implementation’s reflection name and a generated settings serializer against the R8 mapping and DEX, plus the packaged prompt resource and Go numerics licence. For voice input it checks that the native libraries are there for `arm64-v8a` only, without sherpa-onnx's unused C and C++ APIs, that sherpa-onnx's recognizer and speech detector classes keep their names in DEX, and that the sherpa-onnx and ONNX Runtime licences are packaged. Room migration and Kotlin serialization also run under JVM tests. The UI owner performs the signed-APK install and upgrade check after merge.

## Exercise the pipeline with a disposable key

In this workspace, set `JAVA_HOME` and `GRADLE_USER_HOME` to the supplied directories under `.work`. In a checkout without owner signing properties, run:

```powershell
./tools/release/test-release.ps1 -Offline
```

The script creates a one-day test identity under `.work`, verifies the real signed artifact, independently checks the checksum and deletes the disposable key, temporary signing properties and staged test artifacts. It then rebuilds and checks the ordinary unsigned release output. Offline mode uses cached build dependencies.

## Publish the owner-approved artifacts

Tag the source used to build the APK with `v` followed by its exact `versionName`. Run the notes check, attach the verified production APK and `SHA256SUMS`, and use the versioned notes file as the GitHub release body. This example prepares the 0.1.6 draft.

```powershell
./tools/release/check-notes.ps1 -Tag v0.1.6
gh release create v0.1.6 build/releases/0.1.6/MagicHandy-0.1.6.apk build/releases/0.1.6/SHA256SUMS --verify-tag --draft --title "MagicHandy Mobile v0.1.6" --notes-file docs/releases/v0.1.6.md
```

Download both draft assets into a fresh directory and compare their bytes with the local release artifacts. Publish the verified draft as the latest stable release.

Verify the public `/repos/MagicHandy/MagicHandy-Mobile/releases/latest` endpoint and download the assets without credentials. The in-app update check reads that endpoint when requested in App settings. It compares the three numeric fields of `tag_name` with the installed version and displays the result. A missing stable release, unreadable tag, rate limit or network failure produces **Failed**.

## Changelog rules

- Keep one public note per tag at `docs/releases/<tag>.md`. Publish that file verbatim with `--notes-file`. GitHub Releases provides the public index, and the README links to the latest download.
- Start with `# MagicHandy Mobile <tag>`, followed by one `## Changelog` heading. Use 1–12 top-level bullets, up to 60 lines and 350 whitespace-delimited words. Indent a wrapped bullet by two spaces. The notes checker enforces these limits from v0.1.6 onward.
- The first release describes what a new user can install and do. Each later entry covers changes since the preceding public release. Lead with the change a user will notice and any action they need to take.
- Include changes to Stop behavior, device caveats, installation or data migration when relevant. Identify the hardware, provider and build actually tested when making a verification claim. Keep measurements, diagnosis and implementation details in the engineering review.

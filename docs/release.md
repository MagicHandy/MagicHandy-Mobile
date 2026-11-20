# Building and publishing an Android release

The release script builds the minified APK, checks its packaged contents, verifies its signature with Android SDK `apksigner verify --print-certs`, and writes the APK and its SHA-256 checksum. Publishing is a separate owner action.

## Create the signing identity once

Use the JDK’s `keytool` on the signing machine. Choose a directory outside the checkout for the keystore. This Windows example prompts interactively for its password.

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

`:app:checkReleaseContents` checks the Room implementation’s reflection name and a generated settings serializer against the R8 mapping and DEX, plus the packaged prompt resource and Go numerics licence. Room migration and Kotlin serialization also run under JVM tests. The UI owner performs the signed-APK install and upgrade check after merge.

## Exercise the pipeline with a disposable key

In this workspace, set `JAVA_HOME` and `GRADLE_USER_HOME` to the supplied directories under `.work`. In a checkout without owner signing properties, run:

```powershell
./tools/release/test-release.ps1 -Offline
```

The script creates a one-day test identity under `.work`, verifies the real signed artifact, independently checks the checksum and deletes the disposable key, temporary signing properties and staged test artifacts. It then rebuilds and checks the ordinary unsigned release output. Offline mode uses cached build dependencies.

## Publish the owner-approved artifacts

Create a GitHub release for the matching mobile tag, for example `v0.1.1` for an APK with versionName `0.1.1`. Attach the verified `MagicHandy-0.1.1.apk` and `SHA256SUMS`. Check the uploaded names and bytes before publishing the stable release.

The in-app manual update check requests the mobile repository’s unauthenticated `/repos/MagicHandy/MagicHandy-Mobile/releases/latest` endpoint. It compares the three numeric fields of `tag_name` with the installed version and displays the result. It requires a publicly readable latest published release. The repository is currently private. With private metadata, no published stable release, rate limiting, an unreadable tag or a network failure, the check reports **Failed**. It reports **Up to date** only after reading and comparing a valid release. A future private distribution would need an explicitly designed authenticated feed or a separate public release feed. The APK contains no GitHub credentials.

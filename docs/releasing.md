# Releasing

Releases are automated with [release-please](https://github.com/googleapis/release-please) and `.github/workflows/release.yml`.

## Release flow

1. Merge pull requests into `main` using [Conventional Commits](https://www.conventionalcommits.org/) (`feat:`, `fix:`, ...).
2. A bot keeps a release PR open with the next version and changelog.
3. Merging the release PR creates the tag (for example `v1.2.0`) and a GitHub Release.
4. The workflow builds the APK and AAB and attaches them, with `SHA256SUMS.txt`.

The version lives in one place, `appVersion` in `app/build.gradle.kts`. `versionCode` is derived from it (1.2.3 gives 10203).

## Signing

Release builds read `keystore.properties` in the project root (git-ignored, as is `*.jks`):

```
storeFile=inkwell-upload.jks
storePassword=...
keyAlias=inkwell
keyPassword=...
```

Create a key with:

```
keytool -genkeypair -v -keystore inkwell-upload.jks -alias inkwell -keyalg RSA -keysize 2048 -validity 10000
```

CI signs with four repository secrets: `KEYSTORE_BASE64` (the key file, base64-encoded on one line), `KEYSTORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD`.
Without them the workflow still runs but attaches unsigned files.

The workflow needs Settings > Actions > General > "Allow GitHub Actions to create and approve pull requests".

## Local builds

```
./gradlew :app:assembleRelease   # APK
./gradlew :app:bundleRelease     # AAB for Google Play
```

Listing text for Google Play is in [store-listing.md](store-listing.md).

## F-Droid

F-Droid builds from the tagged source and signs with its own key. It reads:

- `fastlane/metadata/android/en-US/`: title, descriptions, images and `changelogs/<versionCode>.txt`.
  Phone screenshots go in `images/phoneScreenshots/`.
- The release tags created by the workflow.

The build recipe for the [fdroiddata](https://gitlab.com/fdroid/fdroiddata) repository is kept in
[fdroid/com.kodalibhargav.inkwell.yml](fdroid/com.kodalibhargav.inkwell.yml). Tags use `UpdateCheckMode: Tags`, so new releases are picked up automatically.

Notes:

- The build sets `dependenciesInfo { includeInApk = false; includeInBundle = false }`, because F-Droid rejects the encrypted dependency metadata block AGP adds by default.
- F-Droid builds carry F-Droid's signature, so they cannot update over a Play or GitHub install.
- `versionCode` is computed in Gradle. If F-Droid's update checker cannot evaluate it, the recipe needs literal values per release.

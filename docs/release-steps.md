# Releasing Inkwell

1. Create the upload key once (keep the .jks safe and backed up):

   keytool -genkeypair -v -keystore inkwell-upload.jks -alias inkwell -keyalg RSA -keysize 2048 -validity 10000

2. Create keystore.properties in the project root with these keys (git-ignored, as is *.jks):

   storeFile=inkwell-upload.jks
   storePassword=...
   keyAlias=inkwell
   keyPassword=...

3. Build the bundle:

   gradlew.bat :app:bundleRelease

   Output: app/build/outputs/bundle/release/app-release.aab

4. In Play Console: create the app, upload the .aab to an Internal or Closed test track,
   complete the store listing (docs/store-listing.md), then promote to Production.
   New personal accounts must run a closed test with 12+ testers for 14 days first.

5. For each update, raise versionCode in app/build.gradle.kts.

## Automated GitHub releases

`.github/workflows/release.yml` builds and attaches the signed files for you.

How it works:
1. Merge pull requests into `main` using `feat:` and `fix:` commit messages.
2. A bot keeps a "release PR" open with the next version and a changelog.
3. Merging the release PR creates the tag (for example `v1.0.1`) and a GitHub Release.
4. The workflow then builds the APK and AAB with your key and attaches them, plus `SHA256SUMS.txt`.

One-time setup:
- Repo Settings > Actions > General > Workflow permissions: tick "Allow GitHub Actions to create and approve pull requests".
- Repo Settings > Secrets and variables > Actions > New repository secret, four secrets:
  - `KEYSTORE_BASE64`: the key file encoded as one line of text.
    Git Bash: `base64 -w0 inkwell-upload.jks`   PowerShell: `[Convert]::ToBase64String([IO.File]::ReadAllBytes("inkwell-upload.jks"))`
  - `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`: the same values as in `keystore.properties`.
- After the first release (v1.0.0) is published, delete the `"release-as": "1.0.0"` line from
  `release-please-config.json` so later versions follow the commit messages.

Notes:
- Without the secrets, the workflow still runs but attaches unsigned files, which cannot be installed.
- The version lives in one place, `appVersion` in `app/build.gradle.kts`; the bot updates it and
  `versionCode` is derived from it (1.2.3 gives 10203).
- Uploading to Google Play stays manual: download the `.aab` from the release and upload it in the Play Console.

## Submitting to F-Droid

F-Droid builds the app itself from the tagged source and signs it with its own key, so it needs no APK from you.
What it reads from this repository:
- `fastlane/metadata/android/en-US/`: title, descriptions, icon, feature graphic and the changelog named after the versionCode
  (for 1.0.0 that is `changelogs/10000.txt`). Add phone screenshots as `images/phoneScreenshots/1.png`, `2.png`, ...
- The tag for each release (`v1.0.0`), created by the release workflow.

Steps:
1. Make the repository public (F-Droid must be able to clone it). Submit only after the release that contains the F-Droid
   build settings exists, so the tag `v1.0.1` (or later) must be on GitHub; `v1.0.0` was created before them.
   Update the version, versionCode and commit in the recipe to match the tag you submit.
2. Create an account on gitlab.com and fork `https://gitlab.com/fdroid/fdroiddata`.
3. In your fork, add `metadata/com.kodalibhargav.inkwell.yml`, copied from `docs/fdroid/com.kodalibhargav.inkwell.yml`.
4. Open a merge request to `fdroid/fdroiddata` using their checklist (they run an automated build and a lint pass). Answer reviewer comments.
5. After it is merged, the app appears in the F-Droid repository after their next build and publish cycle.

Notes:
- F-Droid-built installs carry F-Droid's signature, so they cannot update over a Play or GitHub install. Users must uninstall first.
- The versionCode is computed from the version in `app/build.gradle.kts` (1.2.3 gives 10203). If their update checker cannot read that,
  reviewers may ask for a literal value in the recipe for each release.
- F-Droid does not support in-app purchases, so a paid unlock would have to stay out of the F-Droid build.

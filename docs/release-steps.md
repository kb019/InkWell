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

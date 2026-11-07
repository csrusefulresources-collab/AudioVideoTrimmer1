Audio Video Trimmer — corrected project package
Build configuration updated: 2026-11-07

PROJECT LAYOUT
- .github/workflows/build-apk.yml is at the repository root so GitHub Actions can discover it.
- The Android Gradle project is inside AudioVideoTrimmer/.
- The workflow explicitly builds from AudioVideoTrimmer/ to match the path shown in the supplied GitHub Actions log.

FIXES INCLUDED
- MainActivity navigation tags are declared at file scope, including TAG_AUDIO, so references are unambiguous.
- Removed the obsolete android.defaults.buildfeatures.buildconfig Gradle property.
- Explicit module buildFeatures.buildConfig = true is retained.
- Updated Android Gradle Plugin to 8.9.1 and Gradle wrapper to 8.11.1 for Android API 36.
- compileSdk/targetSdk are 36; minSdk remains 28 (Android 9).
- Incremented versionCode to 8 and versionName to 4.3.0.
- Updated GitHub Actions to Node.js 24-compatible action generations and corrected nested project paths.
- Workflow runs unit tests, Debug build, Release build, then uploads both APKs.

IMPORTANT SIGNING-KEY WARNING
The existing app/signing/trimmer.p12 file has been preserved so builds can keep the same app signature and install as updates over the previously installed app. Do not delete or replace this file.
The ZIP also contains the signing key and the Gradle file contains its password. If this repository is PUBLIC, anyone who can access it may be able to sign an app update with your app's identity. Make the repository PRIVATE and move signing credentials into GitHub Actions Secrets before sharing the source publicly. Changing the signing key will prevent in-place updates of apps signed with the old key.

BUILD
After uploading/extracting the ZIP contents into the GitHub repository root:
1. Open the Actions tab.
2. Select "Build Audio Video Trimmer APK".
3. Run workflow, or push a commit.
4. Download the artifacts named AudioVideoTrimmer-debug and AudioVideoTrimmer-release.

The build could not be executed in this offline inspection environment because Gradle 8.11.1 could not be downloaded. Source structure, XML parsing, wrapper/keystore presence, and keystore password were checked here. GitHub Actions will perform the full dependency resolution, unit tests, and APK compilation.

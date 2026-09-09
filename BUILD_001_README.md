# RVH Camera — BUILD 001

This project now contains the GitHub Actions build workflow used for BUILD 001.

## Build behavior

GitHub Actions bootstraps the Gradle 9.6.0 wrapper during the workflow, so the repository does not require a pre-generated Gradle wrapper to be committed.

The workflow:

1. Checks out the repository.
2. Sets up JDK 17.
3. Sets up the Android SDK.
4. Accepts Android SDK licenses.
5. Installs/configures Gradle 9.6.0.
6. Generates the Gradle wrapper.
7. Verifies the wrapper.
8. Builds the debug APK with `assembleDebug`.
9. Uploads the resulting APK as a GitHub Actions artifact.

## Current stage

BUILD 001 is the first real CI build gate after the Stage 50 pre-build audit. A successful CI run is required before moving into real-device photographic validation.

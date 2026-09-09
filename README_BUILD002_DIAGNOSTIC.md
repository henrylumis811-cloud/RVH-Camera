# RVH Camera — BUILD 002 Diagnostic

Purpose: separate build/toolchain problems from camera callback, processing, and MediaStore save failures.

## Build contract
- AGP 9.4.0
- Gradle 9.6.0
- JDK 17
- compileSdk/targetSdk 37
- Android Build Tools 36.0.0
- CI explicitly provisions Android platform 37 instead of relying on runner defaults.

## Runtime diagnostics
Logcat tag: `RVH_DIAG`

Expected shutter sequence:
1. `SHUTTER_PRESS`
2. `TAKE_PICTURE ...`
3. `CAPTURE_COMPLETED ...`
4. `JPEG_IMAGE_AVAILABLE ...`
5. `JPEG_MATCHED_COMPUTATIONAL ...`
6. `HAL_JPEG_CALLBACK ...` / `YUV_CALLBACK ...`
7. `PIPELINE_START` / `PIPELINE_DONE` (computational path)
8. `MEDIASTORE_INSERT` → `MEDIASTORE_URI` → `MEDIASTORE_FINALIZED`
9. `SAVE_ATTEMPT ... success=true`

If step 3 occurs but step 4 does not, investigate ImageReader/JPEG output.
If steps 3–4 occur but no callback occurs, investigate timestamp correlation/sequence bookkeeping.
If callback occurs but MediaStore fails, investigate storage layer.
If reference save succeeds but computational save fails, the build/camera path is proven and the remaining issue is RVH processing/encoding.


## Phone-only diagnostic use
No PC or ADB is required. Install the debug APK, take a photo, then tap **DIAGNOSTICS**. The report is stored inside the app and can be refreshed, copied to the clipboard, or shared as `RVH_DIAGNOSTIC.txt`. The report is capped to the most recent 120,000 characters so repeated tests do not grow without bound.

### Test protocol
1. Open RVH Camera.
2. Tap **DIAGNOSTICS → CLEAR** before the test.
3. Close the dialog.
4. Press **SHUTTER** once and wait up to 30 seconds.
5. Open **DIAGNOSTICS → REFRESH**.
6. Use **SHARE** to send the report to the chat, or **COPY** and paste it into the chat.
7. If an image was saved, keep both the `RVH_REFERENCE_*.jpg` and `RVH_COMPUTATIONAL_*.jpg` files for the quality comparison.


## BUILD 002 Diagnostic — build infrastructure correction

The CI runner reported `Failed to find package platforms;android-37`. This is an SDK repository availability issue, not an application compile failure. The project now uses compileSdk/targetSdk 36 and explicitly provisions `platforms;android-36` with build-tools 36.0.0. This remains fully compatible with the Android 14/API 34 test device while avoiding dependence on an unavailable API 37 platform package.

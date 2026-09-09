# RVH Camera — Stage 45

## Custom RAW Ultra HDR output boundary

Stage 45 removes the final 8-bit JPEG bottleneck from the custom RAW computational path.

### Changes
- `RawComputationalCapture.process()` now returns the final scene-rendered linear RGB result instead of immediately compressing it to ordinary JPEG.
- `RawToneMapper` no longer clamps every channel to SDR white at the end of the computational pipeline; useful highlight headroom remains available to the output encoder.
- Added `RawUltraHdrEncoder` for Android 14+.
  - Builds an SDR sRGB YUV_420_888 base image.
  - Builds a BT.2020/PQ YCBCR_P010 HDR representation.
  - Uses `YuvImage.compressToJpegR()` to create JPEG/R when the platform supports it.
  - Falls back cleanly when JPEG/R encoding is unavailable or rejected by the device.
- MainActivity now attempts custom RAW JPEG/R output first, then falls back to SDR JPEG.

### Why this matters
Android's JPEG/R path is the public platform route for a JPEG-compatible Ultra HDR image. The Android API requires an SDR YUV_420_888 image plus an HDR YCBCR_P010 image in BT.2020 HLG/PQ color space. Stage 45 provides the first custom RVH rendering path that reaches that encoder rather than discarding the RAW renderer's highlight headroom into ARGB_8888/JPEG.

### Validation
- Structural brace/parenthesis validation passed for all modified Kotlin files.
- Parser-level compilation was attempted; the container has no Android SDK, so Android framework symbols cannot resolve here.
- No device build/test was performed.

### Important device-validation targets for the eventual build
1. JPEG/R accepted by the target Android 14+ device.
2. SDR rendition matches the normal RVH JPEG appearance.
3. HDR rendition does not show hue shifts, lifted blacks, or crushed highlights.
4. Gallery/Photos reports the file as Ultra HDR and `Bitmap.hasGainMap()` is true after decode.
5. Non-Ultra-HDR devices still receive a normal JPEG fallback.

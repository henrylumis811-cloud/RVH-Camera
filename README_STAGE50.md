# RVH Camera — Stage 50

## Imaging pipeline audit and consolidation

Stage 50 is the pre-build audit/consolidation pass. The goal is to stop adding major algorithms and remove integration hazards before the first serious device build.

### Audit findings and fixes

1. **RAW headroom preservation**
   - The previous final RAW tone mapper applied an SDR-style luminance shoulder before output encoding.
   - That undermined the custom JPEG/R path because HDR headroom could be compressed before the HDR encoder saw it.
   - The final RAW tone boundary now preserves scene-linear luminance headroom up to the established working range and limits only excessive chroma pressure. SDR shoulder mapping is performed only by the SDR encoder.

2. **Ultra HDR encoder memory pressure**
   - The encoder previously allocated several full-resolution temporary FloatArrays for RGB/Y/CB/CR conversion.
   - Stage 50 removes those full-frame scratch arrays and performs conversion directly into the YUV buffers, reducing peak encoder memory pressure.

3. **JPEG/R platform contract**
   - Verified against current Android documentation: `YuvImage.compressToJpegR()` requires an SDR `YUV_420_888` image and an HDR `YCBCR_P010` image, with supported SDR colour spaces sRGB/Display P3 and HDR colour spaces BT2020 HLG/PQ.
   - RVH remains capability/API gated and retains ordinary JPEG fallback.

4. **RAW coordinate/calibration path**
   - Active-array-aware lens shading registration from Stage 43 retained.
   - Optical-black calibration, dynamic black/white levels, sensor colour calibration, distortion/lens correction, and OIS/rolling-shutter metadata remain ordered before final rendering.

5. **Motion/fusion path**
   - Global alignment → local motion → confidence smoothing → temporal consensus → anchor protection → edge stabilization remains the intended order.
   - No additional major fusion algorithm was added in this stage.

6. **Output routing**
   - RAW computational captures attempt custom JPEG/R on API 34+ and fall back to SDR JPEG.
   - Vendor JPEG/R is still capability-gated in CameraController.
   - No duplicate RAW success callback is introduced.

### Validation

- Kotlin source structural brace/parenthesis validation: PASS.
- Static audit of RAW processing order: PASS.
- Android framework build/device test: NOT RUN in the current environment because the Android SDK/Gradle installation is unavailable here.

### Build readiness

Stage 50 marks the end of the planned computational-photography pre-build stages. The next phase is **BUILD 001 + real-device photographic validation**.

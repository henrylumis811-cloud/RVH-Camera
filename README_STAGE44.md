# RVH Camera — Flagship Imaging Stage 44

## HDR output routing

Stage 44 fixes an important end-to-end dynamic-range bottleneck.

The custom RAW computational pipeline currently finishes as a conventional 8-bit JPEG. Android 14+ supports Ultra HDR JPEG_R, which is backward-compatible with JPEG while retaining a gain map for HDR-capable displays.

When an HDR capture is requested, RVH Camera now attempts the device's Camera2 HDR extension **before** entering the custom RAW capture branch. If JPEG_R is advertised and the extension succeeds, the resulting Ultra HDR image is delivered directly. If the extension is unavailable or fails, the established RAW computational path remains available as fallback.

Night captures remain on the custom RAW path first so RVH's sensor-domain multi-frame processing is not displaced by an OEM extension merely because an extension exists.

## Validation

- Structural source validation performed.
- Brace and parenthesis balance checked.
- No device build or hardware capture was performed in this stage.

## Next major target

The next major imaging task is a true custom Ultra HDR encoder/output path for the RAW computational pipeline, so RVH's own sensor fusion and rendering can retain HDR headroom rather than depending on the OEM extension for HDR output.

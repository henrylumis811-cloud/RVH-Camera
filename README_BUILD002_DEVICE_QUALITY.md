# RVH Camera — BUILD 002: Device Quality + Save Validation

BUILD 002 changes the device-validation capture priority after the Spark 30C Camera2 probe.

## Confirmed device signals used

- Camera2 hardware support: FULL
- MANUAL_SENSOR
- MANUAL_POST_PROCESSING
- BURST_CAPTURE
- Maximum listed rear still size: 4064x3048
- The probe's RAW capture test was not treated as a reliable primary-path gate.

## Capture priority

For PHOTO and NIGHT, when the full-resolution YUV burst topology is available:

1. RVH computational YUV burst is attempted first.
2. Each computational frame also requests a same-shutter full-resolution JPEG companion.
3. RVH processes the burst and saves the processed result.
4. If processing, encoding, or delivery fails, the same-shutter JPEG companion is saved.
5. If the companion is unavailable, the proven direct JPEG path is used.
6. RAW remains available as a secondary sensor-domain path rather than blocking this validation.

HDR vendor-extension capture remains capability-gated for the HDR intent.

## Test objective

This build is specifically for the first combined device test:

- shutter press must save an image;
- the saved image must come from the RVH computational quality path when available;
- fallback must still save a photo rather than leaving the shutter apparently dead.

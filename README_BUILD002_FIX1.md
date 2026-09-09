# RVH Camera — BUILD 002 FIX1

## Shutter-save hardening

This revision keeps the RVH computational burst path intact, but makes the ordinary JPEG delivery path failure-tolerant.

A successfully delivered camera JPEG is now treated as the hard persistence fallback even if RVH JPEG processing throws. The processed RVH result is still preferred when processing succeeds; if its save fails, the original same-shutter JPEG is saved.

## Device validation goal

On TECNO KL5 / Spark 30C, this build tests:

1. physical shutter capture,
2. successful JPEG delivery,
3. RVH processing when available,
4. guaranteed fallback persistence of the camera JPEG.

This is intentionally a small corrective build rather than another large imaging change.

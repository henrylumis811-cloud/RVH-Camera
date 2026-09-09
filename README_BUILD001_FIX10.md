# RVH Camera — BUILD 001 FIX10

## Integrated capture + save hardening

FIX10 keeps the RVH computational capture path intact and hardens the final persistence contract.

### What changed

- `PhotoSaver.saveJpeg()` now owns the complete MediaStore transaction in one guarded operation.
- `MediaStore.insert()` is now inside the failure boundary; a background-thread exception cannot strand the shutter without an error being surfaced.
- Empty JPEG payloads are rejected before creating gallery entries.
- Output streams are explicitly flushed and closed before the MediaStore item is finalized.
- Partial MediaStore rows are deleted automatically when writing/finalization fails.
- Computationally processed JPEGs are still the preferred output.
- If saving the processed computational result fails, the same-shutter JPEG companion is saved as the fallback.
- If RVH computational processing itself fails, the existing same-shutter JPEG fallback remains available.

## Validation target

This build is intended to test **both image saving and image quality in the same device run**. The expected successful path is:

1. Press shutter.
2. Capture the RVH computational frames.
3. Produce the RVH processed JPEG.
4. Save it to `Pictures/RVH Camera`.
5. If processing or persistence fails, save the same-shutter JPEG companion instead.

No Android build is claimed from this environment. Push this package to GitHub and let BUILD 001 CI be the compile gate before installing it on the phone.

# RVH Camera — BUILD 001 Fix 9

## Purpose
Fix 9 is the integrated device-validation capture path. It does **not** disable computational photography to make saving work.

For any multi-frame computational shutter, the same physical capture requests now target both:

- full-resolution YUV for the RVH computational pipeline
- full-resolution JPEG as a same-shutter safety/save companion

The intended result is:

`Shutter -> YUV multi-frame -> RVH processing -> processed JPEG -> MediaStore/Gallery`

with a same-shutter JPEG fallback if processing fails or the YUV sequence cannot complete.

## Fallback hierarchy
1. Save the RVH computationally processed JPEG when processing succeeds.
2. If processing/encoding fails, save the JPEG companion captured in the same shutter burst.
3. If the companion JPEG was not delivered, fall back to a fresh direct JPEG capture.

This preserves the goal of testing **saving and image quality together**.

## Important
BUILD 001 CI success still must be verified on GitHub. Real-device validation must confirm both:
- a photo appears in Gallery after shutter
- the saved computational result visibly improves the scene where the pipeline is accepted

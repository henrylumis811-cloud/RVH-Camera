# RVH Camera — Flagship Imaging Stage 47

## Local motion-field refinement for RAW multi-frame fusion

Stage 47 adds a bounded coarse local-motion model on top of the existing global RAW alignment and Stage 46 temporal deghosting.

### What changed
- Added `RawLocalMotionModel`.
- Estimates sparse residual displacement around the global frame transform using local gradient correlation.
- Keeps residual motion bounded to ±2 pixels so it cannot destabilize the global registration.
- Interpolates the sparse motion field per output sample.
- Secondary-frame weights are reduced when local residual motion is both confident and spatially significant.
- Anchor protection remains dominant in moving regions.
- Static regions retain the existing multi-frame SNR benefit.
- Existing OIS rolling-shutter weighting and temporal robust gating remain intact.

### Why
A global translation can align a static background while a moving subject has a different local displacement. Stage 46 detected disagreement but did not explicitly distinguish a locally moving region from a globally misregistered frame. Stage 47 adds that distinction without attempting full dense optical flow.

Android Camera2 exposes OIS samples and rolling-shutter skew in compatible captures, which can be combined with image motion information for post-processing. The current implementation continues to use the existing OIS/rolling-shutter model and adds local image evidence on top.

### Validation
- Kotlin compilation of the modified fusion subsystem was performed with minimal imaging stubs.
- Synthetic local-motion test passed:
  `stage47-tests: PASS localDx=1.5 localDy=0.0 confidence=0.75`
- Full Android/device build was intentionally not performed because the container has no Android SDK/Gradle installation and the project is still in the computational-quality development phase.

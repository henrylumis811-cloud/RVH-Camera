# RVH Camera — Stage 48

## Confidence-aware local motion-field regularization

Stage 48 refines the RAW multi-frame local motion field introduced in Stage 47.

### Changes
- Added two-pass confidence-aware spatial regularization to `RawLocalMotionModel`.
- Neighboring motion vectors contribute only when their vectors are compatible.
- Strong motion boundaries are protected from being smeared into adjacent static regions.
- Weak-texture/local-correlation noise is reduced before pixel-level fusion weights consume the field.
- Confidence is propagated conservatively rather than treating weak neighboring estimates as equally reliable.
- Motion vectors remain bounded to the existing +/-2 pixel residual range.

### Validation
- Pure Kotlin compilation of the modified local-motion subsystem: PASS.
- No Android SDK/device build performed yet, by project plan.

### Next
Stage 49 will focus on final fusion edge stabilization and ensuring the transition between multi-frame fusion and anchor protection is visually seamless.

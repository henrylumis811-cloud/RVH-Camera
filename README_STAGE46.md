# RVH Camera — Stage 46

## Motion-aware RAW multi-frame fusion / deghosting

Stage 46 refines `RawMultiFrameFusion` so multi-frame noise/detail gains are retained in static regions while moving regions preferentially fall back toward the selected anchor frame.

### What changed
- Computes a robust temporal median/consensus per RAW sample after exposure normalization and geometric/OIS-aware alignment.
- Computes a temporal median absolute deviation (MAD) as a local motion-confidence signal.
- Ties the motion threshold to the calibrated RAW sensor-noise estimate so ordinary shot/read noise is not treated as motion.
- Applies a soft anchor-protection gate: moving areas increase the anchor contribution and suppress secondary-frame contributions rather than simply averaging them into ghosts.
- Static areas retain the existing sensor-noise-aware multi-frame fusion path.
- Keeps existing rolling-shutter/OIS row weighting, gradient agreement, robust residual rejection, and exposure normalization.
- Explicitly marks the chosen anchor in `AlignedFrame` to avoid per-pixel anchor lookup.

### Intended behavior
- Static background: maximum useful temporal averaging for lower noise and improved detail.
- Moving subject: converge toward the best anchor capture, reducing double edges and temporal ghosting.
- Transition zones: soft rather than binary rejection, preserving useful secondary samples where they still agree.

### Validation
- Pure Kotlin compilation of the modified fusion unit and its supporting model/OIS classes succeeded using local stubs for the Android-independent `RawLinearMosaic` type.
- No Android SDK/device build was available in the environment, so this stage has not been device-tested.

# RVH Camera — Stage 49

## Fusion edge stabilization

Stage 49 consolidates the motion-aware RAW fusion path by stabilizing the transition between the anchor frame and secondary fused frames.

### Changes
- Tracks anchor and secondary fusion weight separately at every RAW mosaic sample.
- Applies a bounded secondary-contribution cap based on temporal motion confidence.
- Tightens the cap at strong local sensor gradients, where small registration errors can otherwise become visible halos or double contours.
- Keeps the transition continuous rather than using a hard moving/static mask.
- Preserves the existing OIS, rolling-shutter, local-motion, temporal-consensus, noise and robust-outlier weighting.
- Does not alter the recovered sensor headroom or Ultra HDR path.

### Validation
- Structural brace/parenthesis validation performed.
- Pure Kotlin imaging source remains suitable for isolated compilation; Android framework build is intentionally deferred until the planned build phase.
- Device capture validation has not yet been performed.

Stage 49 is intended as the final fusion-polish stage before the Stage 50 full imaging-pipeline audit.

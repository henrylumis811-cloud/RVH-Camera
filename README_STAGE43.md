# RVH Camera — Stage 42

## Demosaic false-colour suppression

Stage 42 adds `RawFalseColorReducer`, a conservative post-demosaic stage that suppresses narrow chromatic oscillations introduced by Bayer interpolation along strong luminance edges.

It is deliberately distinct from Stage 37 chromatic-fringe correction: Stage 37 targets optical colour fringing, while Stage 42 targets colour zipper/false-colour artifacts produced by sparse CFA reconstruction.

The reducer requires a strong green-channel edge plus an isolated R-G or B-G residual that is substantially stronger than its neighbouring chroma. It avoids clipped highlights and broad, stable scene chroma. Corrections are bounded and preserve green/luminance structure.

No device build/test performed.

# RVH Camera — Stage 43

## Geometry-aware lens-shading registration

Stage 43 corrects a RAW geometry issue in the lens-shading-map application. Camera2 defines the shading map over the pre-correction active array coordinate system. RAW buffers may include pixels outside that active region, so normalizing shading coordinates against the entire RAW buffer can shift the correction field spatially.

The RAW development path now uses `sensorPreCorrectionActiveArray` when available, preserving a safe fallback to the RAW dimensions when the metadata is absent or invalid. This keeps the existing bilinear map interpolation and channel-specific gains, while registering the map against the coordinate system Android specifies for RAW processing.

Validation target: pure-Kotlin structural checks; device build remains intentionally deferred.

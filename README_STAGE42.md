# RVH Camera — Stage 42

## Demosaic false-colour suppression

Stage 42 adds `RawFalseColorReducer`, a conservative post-demosaic stage that suppresses narrow chromatic oscillations introduced by Bayer interpolation along strong luminance edges.

It is deliberately distinct from Stage 37 chromatic-fringe correction: Stage 37 targets optical colour fringing, while Stage 42 targets colour zipper/false-colour artifacts produced by sparse CFA reconstruction.

The reducer requires a strong green-channel edge plus an isolated R-G or B-G residual that is substantially stronger than its neighbouring chroma. It avoids clipped highlights and broad, stable scene chroma. Corrections are bounded and preserve green/luminance structure.

No device build/test performed.

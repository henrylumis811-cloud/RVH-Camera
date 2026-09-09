# RVH Camera — BUILD 001 Fix 8

## Capture reliability gate

Fix 8 disables application-operated ZSL still reprocessing for ordinary shutter presses during initial real-device validation.

The ZSL session remains initialized for later computational-photography work, but the physical shutter is routed through the direct JPEG ImageReader path. This prevents the ZSL reprocess completion callback from clearing `captureInFlight` without delivering a JPEG to the application save pipeline.

Once direct JPEG capture/save is confirmed on the Tecno Spark 30C, ZSL output delivery will be re-enabled and tested separately with an explicit JPEG result hand-off.

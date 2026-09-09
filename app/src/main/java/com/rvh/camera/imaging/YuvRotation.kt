package com.rvh.camera.imaging

/**
 * Rotates packed YUV420 data at the final processing boundary.
 *
 * Capture/fusion stay in the sensor orientation so all frames share identical geometry.
 * Rotation happens once, after all expensive image processing, avoiding repeated memory copies.
 */
object YuvRotation {
    fun apply(frame: Yuv420Frame, degrees: Int): Yuv420Frame {
        return when (((degrees % 360) + 360) % 360) {
            0 -> frame
            90 -> rotate90(frame)
            180 -> rotate180(frame)
            270 -> rotate270(frame)
            else -> error("Unsupported rotation: $degrees")
        }
    }

    private fun rotate90(src: Yuv420Frame): Yuv420Frame {
        val w = src.width
        val h = src.height
        val outW = h
        val outH = w
        val y = ByteArray(outW * outH)
        for (row in 0 until h) {
            for (col in 0 until w) {
                val dstX = h - 1 - row
                val dstY = col
                y[dstY * outW + dstX] = src.y[row * w + col]
            }
        }
        val cw = (w + 1) / 2
        val ch = (h + 1) / 2
        val outCw = (outW + 1) / 2
        val outCh = (outH + 1) / 2
        val u = ByteArray(outCw * outCh)
        val v = ByteArray(outCw * outCh)
        for (row in 0 until ch) {
            for (col in 0 until cw) {
                val dstX = ch - 1 - row
                val dstY = col
                val srcIndex = row * cw + col
                u[dstY * outCw + dstX] = src.u[srcIndex]
                v[dstY * outCw + dstX] = src.v[srcIndex]
            }
        }
        return Yuv420Frame(src.metadata.copy(rotationDegrees = 0), outW, outH, y, u, v)
    }

    private fun rotate180(src: Yuv420Frame): Yuv420Frame {
        val w = src.width
        val h = src.height
        val y = ByteArray(w * h)
        for (row in 0 until h) {
            for (col in 0 until w) {
                y[(h - 1 - row) * w + (w - 1 - col)] = src.y[row * w + col]
            }
        }
        val cw = (w + 1) / 2
        val ch = (h + 1) / 2
        val u = ByteArray(cw * ch)
        val v = ByteArray(cw * ch)
        for (row in 0 until ch) {
            for (col in 0 until cw) {
                val dst = (ch - 1 - row) * cw + (cw - 1 - col)
                val srcIndex = row * cw + col
                u[dst] = src.u[srcIndex]
                v[dst] = src.v[srcIndex]
            }
        }
        return Yuv420Frame(src.metadata.copy(rotationDegrees = 0), w, h, y, u, v)
    }

    private fun rotate270(src: Yuv420Frame): Yuv420Frame {
        val w = src.width
        val h = src.height
        val outW = h
        val outH = w
        val y = ByteArray(outW * outH)
        for (row in 0 until h) {
            for (col in 0 until w) {
                val dstX = row
                val dstY = w - 1 - col
                y[dstY * outW + dstX] = src.y[row * w + col]
            }
        }
        val cw = (w + 1) / 2
        val ch = (h + 1) / 2
        val outCw = (outW + 1) / 2
        val outCh = (outH + 1) / 2
        val u = ByteArray(outCw * outCh)
        val v = ByteArray(outCw * outCh)
        for (row in 0 until ch) {
            for (col in 0 until cw) {
                val dstX = row
                val dstY = cw - 1 - col
                val srcIndex = row * cw + col
                u[dstY * outCw + dstX] = src.u[srcIndex]
                v[dstY * outCw + dstX] = src.v[srcIndex]
            }
        }
        return Yuv420Frame(src.metadata.copy(rotationDegrees = 0), outW, outH, y, u, v)
    }
}

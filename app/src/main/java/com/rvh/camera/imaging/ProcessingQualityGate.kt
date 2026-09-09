package com.rvh.camera.imaging

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Final guardrail for computational processing.
 *
 * It does not try to judge aesthetics. It only detects regressions that are strong enough to
 * justify falling back to the original reference frame: excessive clipping, implausible global
 * luminance shifts, or a severe loss of stable edge energy. Sampling keeps this inexpensive even
 * for multi-megapixel frames.
 */
class ProcessingQualityGate(
    private val sampleStep: Int = 4,
    private val maxHighlightIncrease: Float = 0.025f,
    private val maxShadowCrushIncrease: Float = 0.035f,
    private val maxMeanShift: Float = 22f,
    private val minimumEdgeEnergyRatio: Float = 0.72f,
) {
    init {
        require(sampleStep >= 1)
        require(maxHighlightIncrease >= 0f)
        require(maxShadowCrushIncrease >= 0f)
        require(maxMeanShift >= 0f)
        require(minimumEdgeEnergyRatio in 0f..1f)
    }

    data class Decision(
        val acceptProcessed: Boolean,
        val highlightIncrease: Float,
        val shadowCrushIncrease: Float,
        val meanShift: Float,
        val edgeEnergyRatio: Float,
    )

    fun evaluate(reference: Yuv420Frame, processed: Yuv420Frame, intent: CaptureIntent = CaptureIntent.PHOTO): Decision {
        require(reference.width == processed.width && reference.height == processed.height) {
            "Quality gate requires identical dimensions"
        }

        val ref = measure(reference)
        val out = measure(processed)
        val highlightIncrease = out.highlightRatio - ref.highlightRatio
        val shadowCrushIncrease = out.shadowRatio - ref.shadowRatio
        val meanShift = abs(out.mean - ref.mean)
        val edgeEnergyRatio = if (ref.edgeEnergy <= 0.001f) {
            1f
        } else {
            out.edgeEnergy / ref.edgeEnergy
        }

        val intentMaxHighlight = when (intent) {
            CaptureIntent.HDR -> maxHighlightIncrease * 2.5f
            CaptureIntent.NIGHT -> maxHighlightIncrease * 1.5f
            else -> maxHighlightIncrease
        }
        val intentMaxShadow = when (intent) {
            CaptureIntent.HDR, CaptureIntent.NIGHT -> maxShadowCrushIncrease * 1.8f
            else -> maxShadowCrushIncrease
        }
        val intentMeanShift = when (intent) {
            CaptureIntent.HDR -> maxMeanShift * 1.75f
            CaptureIntent.NIGHT -> maxMeanShift * 1.30f
            else -> maxMeanShift
        }
        val intentEdgeRatio = when (intent) {
            CaptureIntent.HDR, CaptureIntent.NIGHT -> minimumEdgeEnergyRatio * 0.88f
            else -> minimumEdgeEnergyRatio
        }

        val accepted = highlightIncrease <= intentMaxHighlight &&
            shadowCrushIncrease <= intentMaxShadow &&
            meanShift <= intentMeanShift &&
            edgeEnergyRatio >= intentEdgeRatio

        return Decision(
            acceptProcessed = accepted,
            highlightIncrease = highlightIncrease,
            shadowCrushIncrease = shadowCrushIncrease,
            meanShift = meanShift,
            edgeEnergyRatio = edgeEnergyRatio,
        )
    }

    private data class Metrics(
        val mean: Float,
        val highlightRatio: Float,
        val shadowRatio: Float,
        val edgeEnergy: Float,
    )

    private fun measure(frame: Yuv420Frame): Metrics {
        val width = frame.width
        val height = frame.height
        var sum = 0L
        var count = 0
        var highlights = 0
        var shadows = 0
        var edgeSum = 0f
        var edgeCount = 0

        for (y in 0 until height step sampleStep) {
            val row = y * width
            for (x in 0 until width step sampleStep) {
                val value = frame.y[row + x].toInt() and 0xFF
                sum += value
                count++
                if (value >= 250) highlights++
                if (value <= 5) shadows++

                if (x + sampleStep < width && y + sampleStep < height) {
                    val right = frame.y[row + x + sampleStep].toInt() and 0xFF
                    val down = frame.y[(y + sampleStep) * width + x].toInt() and 0xFF
                    edgeSum += (abs(right - value) + abs(down - value)) * 0.5f
                    edgeCount++
                }
            }
        }

        val safeCount = max(1, count)
        return Metrics(
            mean = sum.toFloat() / safeCount,
            highlightRatio = highlights.toFloat() / safeCount,
            shadowRatio = shadows.toFloat() / safeCount,
            edgeEnergy = edgeSum / max(1, edgeCount),
        )
    }
}

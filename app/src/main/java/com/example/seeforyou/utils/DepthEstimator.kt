package com.example.seeforyou.utils

import android.graphics.RectF

/**
 * Estimates object distance from camera using bounding box size.
 * Since we have a single camera (no stereo yet), we use relative
 * bounding box height as a proxy for distance.
 *
 * When Raspberry Pi stereo camera is added later, replace this
 * with real depth values from the stereo system.
 */
object DepthEstimator {

    /**
     * Distance zones based on bounding box coverage of frame height
     */
    enum class DistanceZone {
        IMMEDIATE,  // < 0.8m  — STOP, danger
        NEAR,       // 0.8-1.5m — warning
        MEDIUM,     // 1.5-3.0m — caution
        FAR         // > 3.0m  — notice only
    }

    data class DepthResult(
        val estimatedMeters: Float,
        val zone: DistanceZone,
        val description: String   // human readable e.g. "half a meter"
    )

    /**
     * Estimate distance using bounding box height relative to image height.
     * Larger box = closer object.
     *
     * @param box         bounding box in image coordinates
     * @param imageHeight full image height in pixels
     * @return DepthResult with estimated distance and zone
     */
    fun estimate(box: RectF, imageHeight: Int): DepthResult {
        val boxHeightRatio = (box.bottom - box.top) / imageHeight.toFloat()

        return when {
            boxHeightRatio > 0.60f -> DepthResult(
                estimatedMeters = 0.5f,
                zone = DistanceZone.IMMEDIATE,
                description = "half a meter"
            )
            boxHeightRatio > 0.40f -> DepthResult(
                estimatedMeters = 0.8f,
                zone = DistanceZone.IMMEDIATE,
                description = "less than a meter"
            )
            boxHeightRatio > 0.25f -> DepthResult(
                estimatedMeters = 1.2f,
                zone = DistanceZone.NEAR,
                description = "about 1 meter"
            )
            boxHeightRatio > 0.15f -> DepthResult(
                estimatedMeters = 2.0f,
                zone = DistanceZone.MEDIUM,
                description = "2 meters"
            )
            boxHeightRatio > 0.08f -> DepthResult(
                estimatedMeters = 3.0f,
                zone = DistanceZone.MEDIUM,
                description = "3 meters"
            )
            else -> DepthResult(
                estimatedMeters = 5.0f,
                zone = DistanceZone.FAR,
                description = "ahead"
            )
        }
    }

    /**
     * Check if object is approaching (getting closer between frames).
     * Call this every few frames with the new box size.
     */
    fun isApproaching(
        previousBoxHeight: Float,
        currentBoxHeight: Float,
        threshold: Float = 0.05f
    ): Boolean {
        return (currentBoxHeight - previousBoxHeight) > threshold
    }
}
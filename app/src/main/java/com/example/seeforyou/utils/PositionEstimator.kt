package com.example.seeforyou.utils

import android.graphics.RectF

/**
 * Estimates horizontal position of detected object relative to camera center.
 * Divides frame into 5 zones for precise directional audio guidance.
 */
object PositionEstimator {

    enum class Position {
        FAR_LEFT,       // object in leftmost 20% of frame
        SLIGHTLY_LEFT,  // object in left-center 20-40%
        CENTER,         // object in center 40-60%
        SLIGHTLY_RIGHT, // object in right-center 60-80%
        FAR_RIGHT       // object in rightmost 80-100%
    }

    data class PositionResult(
        val position: Position,
        val description: String,        // e.g. "on your left"
        val shortDescription: String,   // e.g. "left"
        val vibrationSide: VibrationSide
    )

    enum class VibrationSide {
        LEFT, RIGHT, BOTH, NONE
    }

    /**
     * Estimate horizontal position of object from its bounding box.
     *
     * @param box        bounding box in image coordinates
     * @param imageWidth full image width in pixels
     * @return PositionResult with position enum and human-readable description
     */
    fun estimate(box: RectF, imageWidth: Int): PositionResult {
        val boxCenterX = (box.left + box.right) / 2f
        val ratio = boxCenterX / imageWidth.toFloat()

        return when {
            ratio < 0.20f -> PositionResult(
                position = Position.FAR_LEFT,
                description = "far to your left",
                shortDescription = "far left",
                vibrationSide = VibrationSide.LEFT
            )
            ratio < 0.40f -> PositionResult(
                position = Position.SLIGHTLY_LEFT,
                description = "slightly to your left",
                shortDescription = "left",
                vibrationSide = VibrationSide.LEFT
            )
            ratio < 0.60f -> PositionResult(
                position = Position.CENTER,
                description = "directly ahead",
                shortDescription = "ahead",
                vibrationSide = VibrationSide.BOTH
            )
            ratio < 0.80f -> PositionResult(
                position = Position.SLIGHTLY_RIGHT,
                description = "slightly to your right",
                shortDescription = "right",
                vibrationSide = VibrationSide.RIGHT
            )
            else -> PositionResult(
                position = Position.FAR_RIGHT,
                description = "far to your right",
                shortDescription = "far right",
                vibrationSide = VibrationSide.RIGHT
            )
        }
    }

    /**
     * Generate full spoken alert combining object, position and distance.
     * Examples:
     *   "Car directly ahead, less than a meter"
     *   "Table slightly to your left, 2 meters"
     *   "STOP. Person directly ahead, half a meter"
     */
    fun buildAlert(
        label: String,
        position: PositionResult,
        depth: DepthEstimator.DepthResult
    ): String {
        return when (depth.zone) {
            DepthEstimator.DistanceZone.IMMEDIATE ->
                "Stop. $label ${position.description}, ${depth.description}."
            DepthEstimator.DistanceZone.NEAR ->
                "$label ${position.description}, ${depth.description}."
            DepthEstimator.DistanceZone.MEDIUM ->
                "$label ${position.description}, ${depth.description} ahead."
            DepthEstimator.DistanceZone.FAR ->
                "$label ${position.description}."
        }
    }
}
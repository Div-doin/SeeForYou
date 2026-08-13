package com.example.seeforyou.utils

import android.graphics.RectF

/**
 * Smart filter that picks ONE most important object per frame to speak about.
 * Prevents overwhelming the user with too many simultaneous alerts.
 *
 * Rules:
 * 1. Only speak about objects within NEAR zone or closer
 * 2. Center path objects get priority over side objects
 * 3. Dangerous objects (vehicles) get highest priority
 * 4. Only speak when something meaningful changes
 * 5. Minimum cooldown between any speech
 */
class SmartDetectionFilter {

    data class FilterResult(
        val detection: Detection,
        val position: PositionEstimator.PositionResult,
        val depth: DepthEstimator.DepthResult,
        val alert: String,
        val priority: AudioPriorityQueue.Priority,
        val shouldSpeak: Boolean
    )

    // Danger level per object label (higher = more dangerous)
    private val dangerLevel = mapOf(
        "car"                   to 10,
        "truck"                 to 10,
        "bus"                   to 10,
        "motorcycle"            to 9,
        "bicycle"               to 8,
        "person"                to 7,
        "pothole"               to 9,
        "open_manhole"          to 10,
        "construction_barrier"  to 8,
        "electric_pole"         to 7,
        "traffic_signal"        to 5,
        "chair"                 to 5,
        "dining table"          to 5,
        "couch"                 to 4,
        "bed"                   to 4,
        "door_handle"           to 3,
        "stairs"                to 8,
        "staircase"             to 8
    )

    // Default danger level for unlisted objects
    private val defaultDangerLevel = 3

    // Last spoken state — track what was last said
    private var lastSpokenLabel    = ""
    private var lastSpokenPosition = ""
    private var lastSpokenZone     = DepthEstimator.DistanceZone.FAR
    private var lastSpeakTimeMs    = 0L

    // Minimum time between any two speech outputs (milliseconds)
    private val minSpeakIntervalMs = 3000L

    // Zone where object must be to speak about it
    private val speakableZones = setOf(
        DepthEstimator.DistanceZone.IMMEDIATE,
        DepthEstimator.DistanceZone.NEAR
    )

    // Center positions that are in the user's direct path
    private val centerPositions = setOf(
        PositionEstimator.Position.CENTER,
        PositionEstimator.Position.SLIGHTLY_LEFT,
        PositionEstimator.Position.SLIGHTLY_RIGHT
    )

    /**
     * Process a list of detections and return the single most important
     * one to speak about, or null if nothing should be spoken.
     */
    fun process(
        detections: List<Detection>,
        imageWidth: Int,
        imageHeight: Int
    ): FilterResult? {
        if (detections.isEmpty()) {
            // Reset last spoken when scene is clear
            if (lastSpokenLabel.isNotEmpty()) {
                lastSpokenLabel    = ""
                lastSpokenPosition = ""
                lastSpokenZone     = DepthEstimator.DistanceZone.FAR
            }
            return null
        }

        // Step 1 — Compute position and depth for all detections
        val analyzed = detections.map { det ->
            val position = PositionEstimator.estimate(det.boundingBox, imageWidth)
            val depth    = DepthEstimator.estimate(det.boundingBox, imageHeight)
            Triple(det, position, depth)
        }

        // Step 2 — Filter to only speakable zones
        val inRange = analyzed.filter { (_, _, depth) ->
            depth.zone in speakableZones
        }

        // If nothing is close enough, stay silent
        if (inRange.isEmpty()) return null

        // Step 3 — Sort by priority score (higher = more important)
        val sorted = inRange.sortedByDescending { (det, position, depth) ->
            computePriorityScore(det, position, depth)
        }

        // Step 4 — Pick the top object
        val (topDet, topPosition, topDepth) = sorted.first()

        // Step 5 — Check if something changed since last speech
        val zoneChanged     = topDepth.zone != lastSpokenZone
        val labelChanged    = topDet.label != lastSpokenLabel
        val positionChanged = topPosition.shortDescription != lastSpokenPosition
        val cooldownPassed  = (System.currentTimeMillis() - lastSpeakTimeMs) > minSpeakIntervalMs

        // For IMMEDIATE zone danger — use shorter cooldown (1.5s)
        val immediateCooldownPassed = topDepth.zone == DepthEstimator.DistanceZone.IMMEDIATE &&
                (System.currentTimeMillis() - lastSpeakTimeMs) > 1500L

        val shouldSpeak = when {
            // Always speak immediate danger after short cooldown
            immediateCooldownPassed && topDepth.zone == DepthEstimator.DistanceZone.IMMEDIATE -> true
            // Speak if zone got worse (object got closer)
            zoneChanged && isZoneWorse(lastSpokenZone, topDepth.zone) -> true
            // Speak if new object
            labelChanged && cooldownPassed -> true
            // Speak if position changed significantly
            positionChanged && cooldownPassed -> true
            // Speak periodically even if nothing changed
            cooldownPassed && topDepth.zone == DepthEstimator.DistanceZone.NEAR -> true
            else -> false
        }

        // Build alert message
        val alert = PositionEstimator.buildAlert(topDet.label, topPosition, topDepth)

        // Determine audio priority
        val audioPriority = determineAudioPriority(topDet, topDepth)

        if (shouldSpeak) {
            lastSpokenLabel    = topDet.label
            lastSpokenPosition = topPosition.shortDescription
            lastSpokenZone     = topDepth.zone
            lastSpeakTimeMs    = System.currentTimeMillis()
        }

        return FilterResult(
            detection  = topDet,
            position   = topPosition,
            depth      = topDepth,
            alert      = alert,
            priority   = audioPriority,
            shouldSpeak = shouldSpeak
        )
    }

    /**
     * Compute a numeric priority score for sorting.
     * Higher score = speak first.
     */
    private fun computePriorityScore(
        det: Detection,
        position: PositionEstimator.PositionResult,
        depth: DepthEstimator.DepthResult
    ): Int {
        var score = 0

        // Zone score — closer = higher
        score += when (depth.zone) {
            DepthEstimator.DistanceZone.IMMEDIATE -> 1000
            DepthEstimator.DistanceZone.NEAR      -> 500
            DepthEstimator.DistanceZone.MEDIUM    -> 100
            DepthEstimator.DistanceZone.FAR       -> 0
        }

        // Position score — center path = higher
        score += when (position.position) {
            PositionEstimator.Position.CENTER         -> 300
            PositionEstimator.Position.SLIGHTLY_LEFT  -> 200
            PositionEstimator.Position.SLIGHTLY_RIGHT -> 200
            PositionEstimator.Position.FAR_LEFT       -> 50
            PositionEstimator.Position.FAR_RIGHT      -> 50
        }

        // Danger level score
        score += (dangerLevel[det.label] ?: defaultDangerLevel) * 20

        // Confidence score
        score += (det.confidence * 100).toInt()

        return score
    }

    /**
     * Check if new zone is worse (closer) than previous zone.
     */
    private fun isZoneWorse(
        previous: DepthEstimator.DistanceZone,
        current: DepthEstimator.DistanceZone
    ): Boolean {
        val order = listOf(
            DepthEstimator.DistanceZone.FAR,
            DepthEstimator.DistanceZone.MEDIUM,
            DepthEstimator.DistanceZone.NEAR,
            DepthEstimator.DistanceZone.IMMEDIATE
        )
        return order.indexOf(current) > order.indexOf(previous)
    }

    /**
     * Map detection zone + label to AudioPriorityQueue priority.
     */
    private fun determineAudioPriority(
        det: Detection,
        depth: DepthEstimator.DepthResult
    ): AudioPriorityQueue.Priority {
        val danger = dangerLevel[det.label] ?: defaultDangerLevel
        return when {
            depth.zone == DepthEstimator.DistanceZone.IMMEDIATE && danger >= 7 ->
                AudioPriorityQueue.Priority.DANGER
            depth.zone == DepthEstimator.DistanceZone.IMMEDIATE ->
                AudioPriorityQueue.Priority.WARNING
            depth.zone == DepthEstimator.DistanceZone.NEAR && danger >= 7 ->
                AudioPriorityQueue.Priority.WARNING
            depth.zone == DepthEstimator.DistanceZone.NEAR ->
                AudioPriorityQueue.Priority.CAUTION
            else ->
                AudioPriorityQueue.Priority.CAUTION
        }
    }

    /**
     * Reset all state — call when fragment is hidden or camera stops.
     */
    fun reset() {
        lastSpokenLabel    = ""
        lastSpokenPosition = ""
        lastSpokenZone     = DepthEstimator.DistanceZone.FAR
        lastSpeakTimeMs    = 0L
    }
}
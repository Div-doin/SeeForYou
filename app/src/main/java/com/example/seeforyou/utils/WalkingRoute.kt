package com.example.seeforyou.utils

import org.json.JSONObject
import com.example.seeforyou.localization.PhraseBook
import kotlin.math.*

data class RoutePoint(val lat: Double, val lon: Double)
data class WalkingStep(val instruction: String, val start: Int, val end: Int)
data class WalkingRoute(
    val points: List<RoutePoint>, val steps: List<WalkingStep>,
    val meters: Double, val seconds: Double
) {
    companion object {
        fun parse(json: String, phrases: PhraseBook = PhraseBook()): WalkingRoute {
            val feature = JSONObject(json).getJSONArray("features").getJSONObject(0)
            val coordinates = feature.getJSONObject("geometry").getJSONArray("coordinates")
            val points = (0 until coordinates.length()).map {
                val p = coordinates.getJSONArray(it)
                RoutePoint(p.getDouble(1), p.getDouble(0))
            }
            require(points.size >= 2) { "Route geometry is missing" }
            val segments = feature.getJSONObject("properties").getJSONArray("segments")
            val steps = mutableListOf<WalkingStep>()
            var meters = 0.0
            var seconds = 0.0
            for (s in 0 until segments.length()) {
                val segment = segments.getJSONObject(s)
                meters += segment.getDouble("distance")
                seconds += segment.getDouble("duration")
                val instructions = segment.getJSONArray("steps")
                for (i in 0 until instructions.length()) {
                    val step = instructions.getJSONObject(i)
                    val indices = step.getJSONArray("way_points")
                    val start = indices.getInt(0)
                    val end = indices.getInt(1)
                    require(start in points.indices && end in start until points.size)
                    // Goal is announced only after the final route segment is reached.
                    if (step.optInt("type", -1) != 10 && end > start) {
                        steps.add(WalkingStep(phrases.maneuver(step.optInt("type", -1),
                            step.optString("name", ""), step.getString("instruction"),
                            step.optInt("exit_number", 0)), start, end))
                    }
                }
            }
            require(steps.isNotEmpty()) { "No walking instructions" }
            return WalkingRoute(points, steps, meters, seconds)
        }
    }
}

/** Pure route logic: coordinates are GPS estimates, never camera depth. */
class WalkingGuidance(private val route: WalkingRoute, private val phrases: PhraseBook = PhraseBook()) {
    var stepIndex = 0
        private set
    private var approachAnnounced = false
    private var offRouteCount = 0
    private var arrived = false
    data class Update(val speech: String? = null, val reroute: Boolean = false, val arrived: Boolean = false)

    fun currentInstruction(): String = route.steps[stepIndex].instruction

    fun update(point: RoutePoint, accuracy: Float): Update {
        if (arrived || !accuracy.isFinite() || accuracy <= 0 || accuracy > 25) return Update()
        val routeDistance = route.points.zipWithNext().minOf { (a, b) -> segmentDistance(point, a, b) }
        if (routeDistance > max(35.0, accuracy * 2.0)) {
            offRouteCount++
            return Update(reroute = offRouteCount >= 3)
        }
        offRouteCount = 0
        val step = route.steps[stepIndex]
        val remaining = distance(point, route.points[step.end])
        if (remaining <= 12 && accuracy <= 15) {
            if (stepIndex == route.steps.lastIndex) {
                if (distance(point, route.points.last()) <= 12) {
                    arrived = true
                    return Update(arrived = true, speech = phrases.text("arrival"))
                }
            } else {
                stepIndex++
                approachAnnounced = false
                return Update(speech = currentInstruction())
            }
        }
        if (remaining <= 35 && !approachAnnounced && stepIndex < route.steps.lastIndex) {
            approachAnnounced = true
            return Update(speech = phrases.text("approach", max(10, (remaining / 10).roundToInt() * 10), route.steps[stepIndex + 1].instruction))
        }
        return Update()
    }

    companion object {
        fun distance(a: RoutePoint, b: RoutePoint): Double {
            val lat = Math.toRadians(b.lat - a.lat)
            val lon = Math.toRadians(b.lon - a.lon)
            val h = sin(lat / 2).pow(2) + cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(lon / 2).pow(2)
            return 6371000 * 2 * asin(sqrt(h.coerceIn(0.0, 1.0)))
        }
        private fun segmentDistance(p: RoutePoint, a: RoutePoint, b: RoutePoint): Double {
            val scale = cos(Math.toRadians(p.lat))
            val ax = Math.toRadians(a.lon - p.lon) * scale * 6371000
            val ay = Math.toRadians(a.lat - p.lat) * 6371000
            val bx = Math.toRadians(b.lon - p.lon) * scale * 6371000
            val by = Math.toRadians(b.lat - p.lat) * 6371000
            val dx = bx - ax
            val dy = by - ay
            val length = dx * dx + dy * dy
            val t = if (length == 0.0) 0.0 else (-(ax * dx + ay * dy) / length).coerceIn(0.0, 1.0)
            return hypot(ax + t * dx, ay + t * dy)
        }
    }
}

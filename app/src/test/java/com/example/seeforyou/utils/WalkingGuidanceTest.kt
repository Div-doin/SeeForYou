package com.example.seeforyou.utils

import org.junit.Assert.*
import org.junit.Test

class WalkingGuidanceTest {
    private val points = listOf(RoutePoint(0.0, 0.0), RoutePoint(0.0, 0.001), RoutePoint(0.001, 0.001))
    private fun guidance() = WalkingGuidance(WalkingRoute(points,
        listOf(WalkingStep("Head east", 0, 1), WalkingStep("Turn left", 1, 2)), 222.0, 180.0))

    @Test fun doesNotAdvanceAtDeparture() {
        val g = guidance()
        assertNull(g.update(points[0], 5f).speech)
        assertEquals(0, g.stepIndex)
    }
    @Test fun approachIsAnnouncedOnceThenTurnAtEndpoint() {
        val g = guidance()
        assertTrue(g.update(RoutePoint(0.0, 0.0008), 5f).speech!!.contains("Turn left"))
        assertNull(g.update(RoutePoint(0.0, 0.0008), 5f).speech)
        assertEquals("Turn left", g.update(points[1], 5f).speech)
        assertEquals(1, g.stepIndex)
        assertFalse(g.update(points[1], 5f).arrived)
        assertTrue(g.update(points[2], 5f).arrived)
        assertFalse(g.update(points[2], 5f).arrived)
    }
    @Test fun inaccurateFixCannotAdvanceOrReroute() {
        val g = guidance()
        repeat(4) { assertFalse(g.update(RoutePoint(1.0, 1.0), 90f).reroute) }
        g.update(points[1], 90f)
        assertEquals(0, g.stepIndex)
    }
    @Test fun deviationRequiresThreeFixesAndResetsOnRoute() {
        val g = guidance()
        val off = RoutePoint(0.0006, 0.0005)
        assertFalse(g.update(off, 5f).reroute)
        assertFalse(g.update(off, 5f).reroute)
        g.update(points[0], 5f)
        assertFalse(g.update(off, 5f).reroute)
        assertFalse(g.update(off, 5f).reroute)
        assertTrue(g.update(off, 5f).reroute)
    }
    @Test fun midpointOfLongSegmentIsNotOffRoute() {
        val g = guidance()
        repeat(4) { assertFalse(g.update(RoutePoint(0.0, 0.0005), 5f).reroute) }
    }
    private fun fixture(end: Int = 1) = """
        {"features":[{"geometry":{"coordinates":[[0,0],[0.001,0]]},
        "properties":{"segments":[{"distance":111,"duration":90,"steps":[
        {"instruction":"Head east","way_points":[0,$end],"type":11},
        {"instruction":"Arrive","way_points":[1,1],"type":10}]}]}}]}
    """
    @Test fun parsesWaypointArrayAndExcludesGoal() {
        val route = WalkingRoute.parse(fixture())
        assertEquals(1, route.steps.size)
        assertEquals(1, route.steps[0].end)
        assertEquals(RoutePoint(0.0, 0.001), route.points[1])
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsInvalidWaypoint() {
        WalkingRoute.parse(fixture(50))
    }
}

package com.example.seeforyou.utils

import org.junit.Assert.*
import org.junit.Test

class QuietScenePolicyTest {
    private fun item(label: String = "person", height: Float = 0.5f, x: Float = 0.5f, confidence: Float = 0.9f) =
        QuietScenePolicy.ObjectInView(label, confidence, x, height)
    private fun settle(p: QuietScenePolicy, value: QuietScenePolicy.ObjectInView, start: Long = 0) {
        repeat(3) { p.observe(listOf(value), start + it * 100L) }
    }
    @Test fun phonesAndLaptopsNeverAutomaticallySpeak() {
        val p = QuietScenePolicy()
        repeat(100) {
            val time = it * 1000L
            p.observe(listOf(item("laptop"), item("cell phone")), time)
            assertNull(p.navigationAlert(time))
        }
    }
    @Test fun ordinaryObjectsAreAvailableOnRequest() {
        val p = QuietScenePolicy()
        repeat(3) { p.observe(listOf(item("laptop", x = 0.2f), item("cell phone", x = 0.8f)), it * 100L) }
        assertEquals("In the camera view: laptop on the left, cell phone on the right.", p.description(200))
    }
    @Test fun requiresThreeStableFrames() {
        val p = QuietScenePolicy()
        p.observe(listOf(item()), 0)
        assertNull(p.navigationAlert(0))
        p.observe(listOf(item()), 100)
        assertNull(p.navigationAlert(100))
        p.observe(listOf(item()), 200)
        assertNotNull(p.navigationAlert(200))
    }
    @Test fun unchangedObstacleDoesNotRepeatEvenAfterMinutes() {
        val p = QuietScenePolicy()
        settle(p, item())
        assertNotNull(p.navigationAlert(200))
        repeat(180) {
            val now = 1000L + it * 1000
            p.observe(listOf(item()), now)
            assertNull(p.navigationAlert(now))
        }
    }
    @Test fun briefDetectionDropoutDoesNotCauseReannouncement() {
        val p = QuietScenePolicy()
        settle(p, item())
        p.navigationAlert(200)
        p.observe(emptyList(), 1000)
        settle(p, item(), 2000)
        assertNull(p.navigationAlert(2200))
    }
    @Test fun returnsAfterRealAbsence() {
        val p = QuietScenePolicy()
        settle(p, item())
        p.navigationAlert(200)
        p.observe(emptyList(), 5500)
        settle(p, item(), 7000)
        assertNotNull(p.navigationAlert(7200))
    }
    @Test fun sizeSpikeNeedsConfirmationAndSustainedGrowthCanAlert() {
        val p = QuietScenePolicy()
        settle(p, item())
        p.navigationAlert(200)
        for (now in 1000L..6000L step 1000L) p.observe(listOf(item()), now)
        p.observe(listOf(item(height = 0.8f)), 7000)
        assertNull(p.navigationAlert(7000))
        p.observe(listOf(item(height = 0.8f)), 7100)
        assertNull(p.navigationAlert(7100))
        p.observe(listOf(item(height = 0.8f)), 7200)
        assertNotNull(p.navigationAlert(7200))
    }
    @Test fun weakSideAndSmallDetectionsDoNotInterrupt() {
        listOf(item(confidence = 0.4f), item(x = 0.1f), item(height = 0.2f)).forEach {
            val p = QuietScenePolicy()
            settle(p, it)
            assertNull(p.navigationAlert(200))
        }
    }
    @Test fun unknownOrStaleSceneNeverClaimsClearPath() {
        val p = QuietScenePolicy()
        assertTrue(p.description(0).contains("not ready"))
        p.observe(emptyList(), 0)
        assertTrue(p.description(0).contains("cannot identify"))
        settle(p, item())
        assertTrue(p.description(4000).contains("not ready"))
        assertNull(p.navigationAlert(4000))
    }
    @Test fun descriptionIsLimitedToThreeObjects() {
        val p = QuietScenePolicy()
        repeat(3) { p.observe(listOf(item("chair"), item("person"), item("bottle"), item("laptop")), it * 100L) }
        assertEquals(3, p.description(200).split("in the center").size - 1)
    }
}

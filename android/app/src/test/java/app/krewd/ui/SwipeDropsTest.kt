package app.krewd.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SwipeDropsTest {
    private val width = 1000f
    private val tile = 160f
    private val gap = 30f

    private fun drops(): SwipeDrops {
        val d = SwipeDrops()
        d.configure(width, tile, gap, breakGap = 20f, joinGap = 5f, squashLimit = 30f)
        return d
    }

    /** Slides the card from [from] to [to] over [seconds] at 120 Hz, then holds it there for [hold] seconds. */
    private fun SwipeDrops.slide(from: Float, to: Float, seconds: Float, hold: Float = 0f): Int {
        var pops = 0
        val frames = (seconds * 120).toInt().coerceAtLeast(1)
        for (f in 1..frames) pops += step(1f / 120f, from + (to - from) * f / frames)
        repeat((hold * 120).toInt()) { pops += step(1f / 120f, to) }
        return pops
    }

    @Test
    fun closedRowHidesBothDropsInTheCard() {
        val d = drops()
        assertTrue(d.isAtRest)
        assertEquals(0f, d.exposure(SwipeDrops.DELETE), 0f)
        assertEquals(0f, d.exposure(SwipeDrops.PIN), 0f)
    }

    @Test
    fun deleteBulgesOutOfTheMovingEndBeforeItPops() {
        val d = drops()
        d.slide(0f, d.firstPop * 0.8f, 0.6f, hold = 0.3f)
        assertFalse(d.isPopped(SwipeDrops.DELETE))
        // Held by the card: it sticks out of the card's end, not parked at the row's right edge.
        val edge = width - d.firstPop * 0.8f
        assertTrue(d.center(SwipeDrops.DELETE) + tile / 2f > edge)
        assertTrue(d.center(SwipeDrops.DELETE) < d.spot(SwipeDrops.DELETE) - 1f)
    }

    @Test
    fun deletePopsFirstThenPinAndBothSpringToTheirSpots() {
        val d = drops()
        assertEquals(1, d.slide(0f, d.firstPop * 1.3f, 0.8f, hold = 0.2f))
        assertTrue(d.isPopped(SwipeDrops.DELETE))
        assertFalse(d.isPopped(SwipeDrops.PIN))
        assertEquals(1, d.slide(d.firstPop * 1.3f, d.firstPop * 2.3f, 0.8f, hold = 1.5f))
        assertTrue(d.isPopped(SwipeDrops.PIN))
        assertEquals(d.spot(SwipeDrops.DELETE), d.center(SwipeDrops.DELETE), 1f)
        assertEquals(d.spot(SwipeDrops.PIN), d.center(SwipeDrops.PIN), 1f)
    }

    @Test
    fun keptOpenEverythingPopsAndClosingSwallowsThemBack() {
        val d = drops()
        d.slide(0f, d.reveal * 0.6f, 0.3f)
        d.popAll()
        d.slide(d.reveal * 0.6f, d.reveal, 0.3f, hold = 1.5f)
        assertEquals(d.spot(SwipeDrops.PIN), d.center(SwipeDrops.PIN), 1f)
        d.slide(d.reveal, 0f, 0.4f, hold = 1.5f)
        assertFalse(d.isPopped(SwipeDrops.PIN))
        assertFalse(d.isPopped(SwipeDrops.DELETE))
        assertTrue(d.isAtRest)
        assertTrue(d.exposure(SwipeDrops.DELETE) < 0.05f)
    }
}

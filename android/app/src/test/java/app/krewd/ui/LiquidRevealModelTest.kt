package app.krewd.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiquidRevealModelTest {
    private val rowWidth = 1000f
    private val dropSize = 120f
    private val gapSize = 25f

    private fun model() = LiquidRevealModel().apply { configure(rowWidth, dropSize, gapSize) }

    /** Runs [seconds] of display frames at [hz]. */
    private fun LiquidRevealModel.advance(seconds: Double, hz: Int = 120) {
        val frame = 1_000_000_000L / hz
        repeat((seconds * hz).toInt()) { step(frame) }
    }

    @Test
    fun closedRowShowsNothingAndDropsAreFused() {
        val m = model()
        assertTrue(m.isAtRest)
        assertFalse(m.isVisible)
        assertTrue(m.isBonded(0))
        assertTrue(m.isBonded(1))
        assertEquals(0f, m.exposure(1), 0f)
        assertEquals(0f, m.exposure(2), 0f)
    }

    @Test
    fun fullRevealPinchesBothDropsOffAndRestsAtTheirSlots() {
        val m = model()
        m.setEdge(rowWidth - m.revealWidth)
        m.advance(2.0)
        assertTrue(m.isAtRest)
        assertFalse(m.isBonded(0))
        assertFalse(m.isBonded(1))
        assertEquals(2, m.pinchCount)
        assertEquals(rowWidth - gapSize - dropSize / 2f, m.center(2), 0.5f)
        assertEquals(rowWidth - 2f * gapSize - 1.5f * dropSize, m.center(1), 0.5f)
        assertEquals(1f, m.exposure(1), 1e-3f)
        assertEquals(1f, m.exposure(2), 1e-3f)
    }

    @Test
    fun deleteDetachesFromPinBeforePinLeavesTheCard() {
        val m = model()
        // Slide in small steps, like a finger, and note which neck snaps first.
        var firstBroken = -1
        for (k in 1..200) {
            m.setEdge(rowWidth - m.revealWidth * k / 200f)
            m.step(8_333_333L)
            if (firstBroken < 0) {
                if (!m.isBonded(1)) firstBroken = 1 else if (!m.isBonded(0)) firstBroken = 0
            }
        }
        assertEquals(1, firstBroken)
    }

    @Test
    fun closingAgainMergesTheDropsBack() {
        val m = model()
        m.setEdge(rowWidth - m.revealWidth)
        m.advance(2.0)
        m.setEdge(rowWidth)
        m.advance(2.0)
        assertTrue(m.isAtRest)
        assertTrue(m.isBonded(0))
        assertTrue(m.isBonded(1))
        assertFalse(m.isVisible)
    }

    @Test
    fun motionIsTheSameAt60And120Hz() {
        val a = model()
        val b = model()
        a.setEdge(rowWidth - a.revealWidth)
        b.setEdge(rowWidth - b.revealWidth)
        a.advance(0.25, hz = 60)
        b.advance(0.25, hz = 120)
        assertEquals(a.center(1), b.center(1), 0.01f)
        assertEquals(a.neck(0), b.neck(0), 0.01f)
    }

    @Test
    fun bondedOverlappingCapsGetAFlatBridgeInsteadOfANotch() {
        val out = FloatArray(4)
        LiquidRevealModel.bridgeGeometry(capA = 100f, capB = 80f, r = 30f, neck = 30f, out = out, offset = 0)
        assertEquals(90f, out[0], 0f)
        assertEquals(10f, out[1], 0f)
        assertEquals(30f, out[3], 0f)
    }

    @Test
    fun aSlowOpenSnapsEachNeckOnceAndOnlyBetweenSeparateDrops() {
        val m = model()
        val slots = FloatArray(6)
        val wasBonded = booleanArrayOf(true, true)
        var breaks = 0
        for (k in 1..300) {
            m.setEdge(rowWidth - m.revealWidth * k / 300f)
            m.step(8_333_333L)
            m.fillSlots(slots)
            for (j in 0..1) {
                if (wasBonded[j] && !m.isBonded(j)) {
                    breaks++
                    // The drops must already be apart, or they would visibly part a second time later.
                    assertTrue("boundary $j broke while overlapping", slots[(j + 1) * 2] >= slots[j * 2 + 1])
                }
                assertFalse("boundary $j rejoined while opening", !wasBonded[j] && m.isBonded(j))
                wasBonded[j] = m.isBonded(j)
            }
        }
        m.advance(2.0)
        assertEquals(2, breaks)
        assertEquals(2, m.pinchCount)
    }

    @Test
    fun closingMergesTheDropsEarlyInTheReturn() {
        val m = model()
        m.setEdge(rowWidth - m.revealWidth)
        m.advance(2.0)
        // A fifth of the way back, both necks have formed again.
        for (k in 1..20) {
            m.setEdge(rowWidth - m.revealWidth * (1f - 0.2f * k / 20f))
            m.step(8_333_333L)
        }
        m.advance(0.1)
        assertTrue(m.isBonded(0))
        assertTrue(m.isBonded(1))
    }

    @Test
    fun overdragStretchesTheDropsAfterTheCardAndTheyEaseBack() {
        val m = model()
        val open = rowWidth - m.revealWidth
        m.setEdge(open)
        m.advance(2.0)
        val pin = m.center(1)
        val delete = m.center(2)
        val over = 100f
        m.setEdge(open - over)
        m.advance(2.0)
        // Both drops follow the card part of the way, Pin more than Delete, so the gaps widen evenly.
        assertTrue(m.center(1) < pin - 0.5f * over)
        assertTrue(m.center(2) < delete - 0.3f * over)
        assertTrue(m.center(2) > delete - over)
        m.setEdge(open)
        m.advance(2.0)
        assertEquals(pin, m.center(1), 0.5f)
        assertEquals(delete, m.center(2), 0.5f)
    }
}

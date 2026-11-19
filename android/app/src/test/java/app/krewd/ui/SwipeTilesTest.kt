package app.krewd.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class SwipeTilesTest {
    private val width = 1000f
    private val tile = 160f
    private val gap = 20f
    private val out = FloatArray(SwipeTiles.SIZE)

    private fun at(slide: Float): FloatArray {
        SwipeTiles.place(slide, width, tile, gap, out)
        return out
    }

    @Test
    fun closedRowHasNoTiles() {
        val t = at(0f)
        assertEquals(0f, t[1], 0f)
        assertEquals(0f, t[3], 0f)
    }

    @Test
    fun tilesFillExactlyTheSlidOutStrip() {
        for (slide in listOf(10f, 100f, 170f, 200f, 300f, 360f, 420f, 500f)) {
            val t = at(slide)
            assertEquals(width - slide + t[SwipeTiles.PIN_GAP], t[0], 0.01f)
            assertEquals(t[0] + t[1] + t[SwipeTiles.DELETE_GAP], t[2], 0.01f)
            assertEquals(width, t[2] + t[3], 0.01f)
        }
    }

    @Test
    fun deleteGrowsAndPinchesOffBeforePinAppears() {
        val growing = at(tile / 2f)
        assertEquals(tile / 2f, growing[3], 0.01f)
        assertEquals(0f, growing[SwipeTiles.DELETE_GAP], 0f)
        assertEquals(0f, growing[1], 0f)
        val split = at(tile + gap)
        assertEquals(gap, split[SwipeTiles.DELETE_GAP], 0.01f)
        assertEquals(0f, split[1], 0f)
        val pinGrowing = at(tile + gap + tile / 2f)
        assertEquals(tile / 2f, pinGrowing[1], 0.01f)
        assertEquals(0f, pinGrowing[SwipeTiles.PIN_GAP], 0f)
    }

    @Test
    fun fullRevealShowsBothTilesWithRestingGaps() {
        val t = at(SwipeTiles.reveal(tile, gap))
        assertEquals(tile, t[1], 0.01f)
        assertEquals(tile, t[3], 0.01f)
        assertEquals(gap, t[SwipeTiles.PIN_GAP], 0.01f)
        assertEquals(gap, t[SwipeTiles.DELETE_GAP], 0.01f)
    }

    @Test
    fun overdragWidensBothTiles() {
        val t = at(SwipeTiles.reveal(tile, gap) + 40f)
        assertEquals(tile + 20f, t[1], 0.01f)
        assertEquals(tile + 20f, t[3], 0.01f)
        assertEquals(gap, t[SwipeTiles.PIN_GAP], 0.01f)
        assertEquals(gap, t[SwipeTiles.DELETE_GAP], 0.01f)
    }
}

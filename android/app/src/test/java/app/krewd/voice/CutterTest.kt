package app.krewd.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/** How the microphone is cut into pieces of speech: [Cutter] fed with 20 ms frames of tone (speech) and quiet. */
class CutterTest {
    private val pieces = mutableListOf<Pair<Int, Boolean>>() // samples, instruct
    private val events = mutableListOf<String>()
    private val cutter = Cutter(piece = { pcm, instruct -> pieces.add(pcm.size to instruct); events.add("piece") }, pause = { events.add("pause") })
    private var t = 0

    private fun feed(ms: Int, loud: Boolean, instruct: Boolean = false) {
        repeat(ms / 20) {
            val f = ShortArray(Voice.FRAME) { i -> if (loud) (6000 * sin(2 * PI * 220 * (t + i) / Voice.RATE)).toInt().toShort() else (if (i % 2 == 0) 40 else -40).toShort() }
            t += Voice.FRAME
            cutter.feed(f, f.size, instruct)
        }
    }

    @Test
    fun speechBetweenPausesIsOnePieceThenAPause() {
        feed(1000, loud = false)
        feed(2000, loud = true)
        feed(700, loud = false)
        assertEquals(1, pieces.size)
        // 300 ms before the start, 2 s of speech, 200 ms after it (give or take the onset frames).
        assertTrue(pieces[0].first in (2_300 * 16)..(2_600 * 16))
        feed(1600, loud = false)
        assertEquals(listOf("piece", "pause"), events)
    }

    @Test
    fun aCoughIsDropped() {
        feed(500, loud = false)
        feed(200, loud = true)
        feed(1000, loud = false)
        assertTrue(pieces.isEmpty())
    }

    @Test
    fun longSpeechIsCutAt15Seconds() {
        feed(500, loud = false)
        feed(20_000, loud = true)
        feed(700, loud = false)
        assertEquals(2, pieces.size)
        assertEquals(15 * Voice.RATE, pieces[0].first)
    }

    @Test
    fun anInstructionIsEverythingWhileHeldThenAPause() {
        feed(500, loud = false)
        feed(1000, loud = true, instruct = true)
        feed(400, loud = false, instruct = true)
        feed(20, loud = false)
        assertEquals(listOf(1400 * 16 to true), pieces)
        assertEquals(listOf("piece", "pause"), events)
    }
}

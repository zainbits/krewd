package app.krewd.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/** How the microphone is cut into pieces of speech: [Cutter] fed with 20 ms frames of tone (speech) and quiet. */
class CutterTest {
    private val pieces = mutableListOf<Int>() // samples per piece
    private val cutter = Cutter(piece = { pcm -> pieces.add(pcm.size) })
    private var t = 0

    private fun feed(ms: Int, loud: Boolean) {
        repeat(ms / 20) {
            val f = ShortArray(Voice.FRAME) { i -> if (loud) (6000 * sin(2 * PI * 220 * (t + i) / Voice.RATE)).toInt().toShort() else (if (i % 2 == 0) 40 else -40).toShort() }
            t += Voice.FRAME
            cutter.feed(f, f.size)
        }
    }

    /** Talking: 400 ms of sound and 100 ms between words (a steady tone would become the quiet floor). */
    private fun talk(ms: Int) = repeat(ms / 500) {
        feed(400, loud = true)
        feed(100, loud = false)
    }

    @Test
    fun shortPausesStayInsideOnePiece() {
        feed(1000, loud = false)
        feed(2000, loud = true)
        feed(700, loud = false) // a breath: the piece goes on
        feed(1500, loud = true)
        assertTrue(pieces.isEmpty())
        feed(2100, loud = false) // 2 s of quiet ends it
        assertEquals(1, pieces.size)
        // 300 ms before the start, 2 s + 0.7 s + 1.5 s, then 200 ms of the quiet (give or take the onset frames).
        assertTrue(pieces[0] in (4_600 * 16)..(4_900 * 16))
        feed(5000, loud = false) // the mic stays on: quiet sends nothing
        assertEquals(1, pieces.size)
    }

    @Test
    fun aCoughIsDropped() {
        feed(500, loud = false)
        feed(200, loud = true)
        feed(2500, loud = false)
        assertTrue(pieces.isEmpty())
    }

    @Test
    fun aLongPieceIsCutAtItsQuietestMomentBefore30Seconds() {
        feed(500, loud = false)
        talk(24_000)
        feed(400, loud = false) // a longer pause 24 s in
        talk(10_000)
        assertEquals(1, pieces.size)
        val cutAt = pieces[0] / Voice.RATE.toDouble()
        assertTrue("cut at $cutAt s", cutAt in 24.1..24.8) // in that pause (piece time), not at 30 s
        feed(2100, loud = false)
        assertEquals(2, pieces.size) // the rest of the speech
        assertTrue(pieces[1] / Voice.RATE.toDouble() in 9.5..10.5)
    }

    @Test
    fun talkWithoutAPauseIsCutAt30Seconds() {
        feed(500, loud = false)
        talk(40_000)
        assertEquals(1, pieces.size)
        assertTrue(pieces[0] in (20 * Voice.RATE)..(30 * Voice.RATE))
    }

    @Test
    fun cutSendsThePieceBeingHeardAtOnce() {
        feed(500, loud = false)
        feed(1000, loud = true)
        cutter.cut() // Send, Mute or leaving voice mode while still talking
        assertEquals(1, pieces.size)
        assertTrue(cutter.inSpeech.not())
        cutter.cut() // nothing open: nothing more
        assertEquals(1, pieces.size)
    }

    @Test
    fun heardWordsJoinTheSameSpeakersLine() {
        var draft = ""
        draft = Voice.joinLine(draft, "Others", "My order never came.")
        draft = Voice.joinLine(draft, "Others", "It was last week.")
        draft = Voice.joinLine(draft, "Me", "Let me check.")
        assertEquals("Others: My order never came. It was last week.\nMe: Let me check.", draft)
        // The owner typed a line of their own: the next words start a new line.
        draft = Voice.joinLine("$draft\nWhat do I say?  ", "Me", "Okay.")
        assertEquals("Others: My order never came. It was last week.\nMe: Let me check.\nWhat do I say?\nMe: Okay.", draft)
        assertEquals("typed", Voice.joinLine("typed", "Me", "  "))
    }
}

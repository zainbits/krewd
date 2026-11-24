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

    @Test
    fun speechBetweenPausesIsOnePiece() {
        feed(1000, loud = false)
        feed(2000, loud = true)
        feed(700, loud = false)
        assertEquals(1, pieces.size)
        // 300 ms before the start, 2 s of speech, 200 ms after it (give or take the onset frames).
        assertTrue(pieces[0] in (2_300 * 16)..(2_600 * 16))
        feed(5000, loud = false) // the mic stays on: quiet sends nothing
        assertEquals(1, pieces.size)
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
        assertEquals(15 * Voice.RATE, pieces[0])
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

package app.krewd.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class LiveTailTest {
    private fun t(id: Long, label: String?, text: String) = LiveTail.Turn(id, label, text)

    @Test
    fun wordsBeingHeardAreReplacedThenTurnFinal() {
        val tail = LiveTail()
        var text = "Context typed first."
        text = tail.apply(text, emptyList(), listOf(t(0, null, "Tell me")))
        assertEquals("Context typed first.\n…: Tell me", text)
        text = tail.apply(text, emptyList(), listOf(t(0, "Others", "Tell me about your")))
        assertEquals("Context typed first.\nOthers: Tell me about your", text)
        text = tail.apply(text, listOf(t(0, "Others", "Tell me about your project.")), listOf(t(1, "Me", "I built")))
        assertEquals("Context typed first.\nOthers: Tell me about your project.\nMe: I built", text)
        text = tail.apply(text, listOf(t(1, "Me", "I built a voice app.")), emptyList())
        assertEquals("Context typed first.\nOthers: Tell me about your project.\nMe: I built a voice app.", text)
    }

    @Test
    fun theSameSpeakersTurnsJoinOneLine() {
        val tail = LiveTail()
        var text = tail.apply("", listOf(t(0, "Others", "One.")), listOf(t(1, "Others", "Two")))
        assertEquals("Others: One. Two", text)
        text = tail.apply(text, listOf(t(1, "Others", "Two.")), emptyList())
        assertEquals("Others: One. Two.", text)
    }

    @Test
    fun aFixByTheOwnerStaysAndThatTurnIsTheirs() {
        val tail = LiveTail()
        var text = tail.apply("", emptyList(), listOf(t(0, "Others", "Tell me about Rag")))
        text = text.replace("Rag", "RAG") // the owner fixes a word while it is still being heard
        text = tail.apply(text, listOf(t(0, "Others", "Tell me about Rag systems.")), listOf(t(1, "Me", "Sure")))
        assertEquals("Others: Tell me about RAG\nMe: Sure", text)
    }

    @Test
    fun afterSendTheComposerStartsEmpty() {
        val tail = LiveTail()
        tail.apply("", listOf(t(0, "Others", "First question.")), emptyList())
        var text = "" // sent
        text = tail.apply(text, emptyList(), listOf(t(1, "Others", "Next")))
        assertEquals("Others: Next", text)
    }
}

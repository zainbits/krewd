package app.krewd.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownListTest {
    private fun ids(text: String) = markdownBlocks(text).map { it.first }

    @Test
    fun listContinuedUnderLabelStaysAList() {
        val text = "**networking.md**\n1. One\n2. Two\n\n**data.md**\n5. Five\n6. Six"
        assertEquals(listOf("b0", "b1.0", "b1.1", "b2", "b3.0", "b3.1"), ids(text))
    }

    @Test
    fun lazyLineInsideListIsNotALabel() {
        assertEquals("1. a\nmore\n2. b", listBreaks("1. a\nmore\n2. b"))
    }

    @Test
    fun codeFenceIsLeftAlone() {
        val text = "```\nlabel\n5. not a list\n```"
        assertEquals(text, listBreaks(text))
    }

    @Test
    fun indentedContinuationIsLeftAlone() {
        assertEquals("- item\n  text\n  3. sub", listBreaks("- item\n  text\n  3. sub"))
    }
}

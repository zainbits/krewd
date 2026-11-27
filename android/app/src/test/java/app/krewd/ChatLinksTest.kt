package app.krewd

import app.krewd.data.AlertLevel
import app.krewd.data.NeedItem
import app.krewd.data.PreviewKind
import app.krewd.ui.chatLinkId
import app.krewd.ui.dueText
import app.krewd.ui.hasScripts
import app.krewd.ui.linkChats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ChatLinksTest {
    private val id = "5d740053-16d1-41c1-96ab-62e977396784"
    private val titles = mapOf(id to "Zoho [fix] check-in")
    private val title: (String) -> String? = { titles[it] }

    @Test
    fun knownIdsBecomeTitledLinks() {
        assertEquals("See [Zoho \\[fix\\] check-in](krewd://chat/$id).", linkChats("See $id.", title))
        assertEquals("[Zoho \\[fix\\] check-in](krewd://chat/$id)", linkChats("`$id`", title))
        assertEquals("[Zoho \\[fix\\] check-in](krewd://chat/$id)", linkChats("krewd://chat/$id", title))
        assertEquals("[that chat](krewd://chat/$id)", linkChats("[that chat](krewd://chat/$id)", title))
        assertEquals("[Zoho \\[fix\\] check-in](krewd://chat/$id)", linkChats("[$id](krewd://chat/$id)", title))
        assertEquals("Chat [Zoho \\[fix\\] check-in](krewd://chat/$id)", linkChats("Chat ${id.uppercase()}", title))
    }

    @Test
    fun otherIdsCodeAndAddressesStay() {
        val other = "11111111-2222-3333-4444-555555555555"
        assertEquals("id $other", linkChats("id $other", title))
        assertEquals("https://x.example/$id", linkChats("https://x.example/$id", title))
        val code = "```\n$id\n```"
        assertEquals(code, linkChats(code, title))
    }

    @Test
    fun chatLinkIds() {
        assertEquals(id, chatLinkId("krewd://chat/$id"))
        assertNull(chatLinkId("https://example.com"))
    }

    @Test
    fun dueSoonComesFirst() {
        val now = Instant.parse("2026-11-26T10:00:00Z")
        fun item(key: String, due: Instant?, level: String = AlertLevel.ACTION) =
            NeedItem(key, key, key, "", null, due, level, "open", null, null, "agent", now)
        val later = item("later", now.plusSeconds(3 * 86400), AlertLevel.URGENT)
        val soon = item("soon", now.plusSeconds(1800))
        val sooner = item("sooner", now.plusSeconds(600))
        val none = item("none", null, AlertLevel.URGENT)
        val quiet = item("quiet", null)
        // Near due times first (soonest first), then by level (urgent before action), snoozed and the rest after.
        val order = NeedItem.sorted(listOf(quiet, none, later, soon, sooner), now).map { it.key }
        assertEquals(listOf("sooner", "soon"), order.take(2))
        assertEquals(setOf("none", "later"), order.subList(2, 4).toSet())
        assertEquals("quiet", order.last())
        assertEquals("due in 10 min", dueText(sooner.dueAt, now))
        assertEquals("overdue 5 min", dueText(now.minusSeconds(300), now))
        assertEquals("due in 2 h 30 min", dueText(now.plusSeconds(9000), now))
    }

    @Test
    fun previewKinds() {
        assertEquals(PreviewKind.Markdown, PreviewKind.of("notes.MD", null))
        assertEquals(PreviewKind.Pdf, PreviewKind.of("x", "application/pdf"))
        assertEquals(PreviewKind.Html, PreviewKind.of("page.htm", "text/plain"))
        assertNull(PreviewKind.of("app.apk", "application/vnd.android.package-archive"))
    }

    @Test
    fun htmlScriptsAreNoticed() {
        assertTrue(hasScripts("<body><SCRIPT>go()</SCRIPT></body>"))
        assertTrue(hasScripts("<button onclick=\"go()\">1</button>"))
        assertTrue(hasScripts("<a href=\"javascript:go()\">x</a>"))
        assertFalse(hasScripts("<p>Plain page about scripts and the description of a button.</p>"))
    }
}

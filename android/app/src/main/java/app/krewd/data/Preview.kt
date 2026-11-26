package app.krewd.data

import java.io.File

/** The files Krewd opens inside the app instead of handing them to another app (ui/FilePreview.kt). */
enum class PreviewKind(val mime: String, val maxBytes: Long) {
    Markdown("text/markdown", 4L shl 20),
    Pdf("application/pdf", 80L shl 20),
    Html("text/html", 8L shl 20);

    companion object {
        fun of(name: String, mime: String?): PreviewKind? = when (name.substringAfterLast('.', "").lowercase()) {
            "md", "markdown", "mdown", "mkd" -> Markdown
            "pdf" -> Pdf
            "html", "htm", "xhtml" -> Html
            else -> when (mime?.substringBefore(';')?.trim()?.lowercase()) {
                "text/markdown", "text/x-markdown" -> Markdown
                "application/pdf" -> Pdf
                "text/html", "application/xhtml+xml" -> Html
                else -> null
            }
        }
    }
}

/** The preview open now: the file, and once fetched its copy in the cache ([local]) or why it failed. */
data class FilePreview(val file: Attachment, val kind: PreviewKind, val local: File? = null, val error: String? = null)

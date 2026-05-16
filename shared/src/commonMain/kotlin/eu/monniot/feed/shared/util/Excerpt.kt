package eu.monniot.feed.shared.util

private val TAG_REGEX = Regex("<[^>]*>")
private val WHITESPACE_REGEX = Regex("\\s+")
private const val EXCERPT_LENGTH = 180
private const val WORDS_PER_MINUTE = 220

/**
 * Strip HTML tags from [html] and return plain text.
 */
fun stripHtml(html: String): String =
    TAG_REGEX.replace(html, " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&nbsp;", " ")
        .let { WHITESPACE_REGEX.replace(it, " ") }
        .trim()

/**
 * Return the first ~[EXCERPT_LENGTH] characters of [html] after stripping HTML tags.
 * Appends "…" if the text was truncated.
 */
fun excerpt(html: String): String {
    val text = stripHtml(html)
    return if (text.length <= EXCERPT_LENGTH) {
        text
    } else {
        text.take(EXCERPT_LENGTH).trimEnd() + "…"
    }
}

/**
 * Estimate reading time in minutes for [html] content.
 * Uses word count / 220 wpm, with a minimum of 1 minute.
 */
fun minutesToRead(html: String): Int {
    val text = stripHtml(html)
    if (text.isBlank()) return 1
    val wordCount = text.split(WHITESPACE_REGEX).count { it.isNotBlank() }
    return maxOf(1, wordCount / WORDS_PER_MINUTE)
}

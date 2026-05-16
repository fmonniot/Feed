package eu.monniot.feed.web.ui.feed

/**
 * Minimal HTML allowlist sanitizer for article body content.
 *
 * Allowed tags: <p>, <a href>, <strong>, <em>, <blockquote>,
 *               <ul>, <ol>, <li>, <img src alt>, <h2>, <h3>
 *
 * Stripped unconditionally: <script>, <iframe>, <style>, inline event
 * handlers (on*="..."), javascript: URLs.
 *
 * All other tags are stripped (their text content is preserved where
 * applicable — block-level unknowns are replaced with their inner text).
 */
fun sanitizeHtml(html: String): String {
    if (html.isBlank()) return ""

    // Step 1: Strip dangerous whole-element tags (script/style/iframe) with their content.
    // Using a character-by-character approach to avoid DOT_MATCHES_ALL which is
    // not available in Kotlin/JS (see: KT-22168).
    var result = html
    result = stripTagWithContent(result, "script")
    result = stripTagWithContent(result, "style")
    result = stripTagWithContent(result, "iframe")

    // Step 2: Process remaining tags — clean attributes on allowed tags
    result = processAllowedTags(result)

    // Step 3: Strip any remaining HTML tags not in the allowlist
    result = stripUnknownTags(result)

    return result
}

/**
 * Removes a tag and all its content (including nested content).
 *
 * Uses a simple linear scan rather than regex with DOT_MATCHES_ALL (which
 * is not supported by the Kotlin/JS regex engine).
 */
private fun stripTagWithContent(html: String, tagName: String): String {
    val result = StringBuilder()
    var i = 0
    val lowerHtml = html.lowercase()
    val openTag = "<$tagName"
    val closeTag = "</$tagName>"

    while (i < html.length) {
        // Find next opening tag (case-insensitive)
        val openIdx = lowerHtml.indexOf(openTag, i)
        if (openIdx == -1) {
            // No more occurrences — append rest and stop
            result.append(html, i, html.length)
            break
        }
        // Append everything before the opening tag
        result.append(html, i, openIdx)
        // Find the corresponding closing tag
        val closeIdx = lowerHtml.indexOf(closeTag, openIdx)
        if (closeIdx == -1) {
            // No closing tag found — strip to end of string
            break
        }
        // Skip past the closing tag
        i = closeIdx + closeTag.length
    }

    return result.toString()
}

/** Set of allowed tag names (lowercase) */
private val ALLOWED_TAGS = setOf("p", "a", "strong", "em", "blockquote", "ul", "ol", "li", "img", "h2", "h3")

/**
 * Processes allowed tags: strips disallowed attributes and sanitizes
 * attribute values. Returns the HTML with cleaned allowed tags; unknown
 * tags are left for [stripUnknownTags] to handle.
 */
private fun processAllowedTags(html: String): String {
    // Match any HTML tag
    val tagPattern = Regex("<(/?)([a-zA-Z][a-zA-Z0-9]*)([^>]*)>")
    return tagPattern.replace(html) { match ->
        val closing = match.groupValues[1] == "/"
        val tag = match.groupValues[2].lowercase()
        val attrs = match.groupValues[3]

        if (tag !in ALLOWED_TAGS) {
            // Leave as-is for stripUnknownTags to remove (keep text content)
            return@replace match.value
        }

        if (closing) return@replace "</$tag>"

        val cleanedAttrs = buildAllowedAttributes(tag, attrs)
        if (cleanedAttrs.isEmpty()) "<$tag>" else "<$tag $cleanedAttrs>"
    }
}

/** Returns only the safe attributes for a given tag */
private fun buildAllowedAttributes(tag: String, rawAttrs: String): String {
    val parts = mutableListOf<String>()

    when (tag) {
        "a" -> {
            val href = extractAttr(rawAttrs, "href")
            if (href != null && !href.lowercase().startsWith("javascript:")) {
                parts += "href=\"${escapeAttr(href)}\""
                parts += "rel=\"noopener noreferrer\""
                parts += "target=\"_blank\""
            }
        }
        "img" -> {
            val src = extractAttr(rawAttrs, "src")
            val alt = extractAttr(rawAttrs, "alt") ?: ""
            if (src != null && !src.lowercase().startsWith("javascript:")) {
                parts += "src=\"${escapeAttr(src)}\""
                parts += "alt=\"${escapeAttr(alt)}\""
                // Constrain image size
                parts += "style=\"max-width:100%;height:auto\""
            }
        }
        // All other allowed tags: no attributes needed (p, strong, em, etc.)
    }

    return parts.joinToString(" ")
}

/** Extracts a quoted or unquoted attribute value by name (case-insensitive) */
private fun extractAttr(attrs: String, name: String): String? {
    // Match name="value", name='value', or name=value
    val pattern = Regex(
        """${Regex.escape(name)}\s*=\s*(?:"([^"]*?)"|'([^']*?)'|([^\s>]+))""",
        RegexOption.IGNORE_CASE
    )
    val match = pattern.find(attrs) ?: return null
    return match.groupValues[1].ifBlank { null }
        ?: match.groupValues[2].ifBlank { null }
        ?: match.groupValues[3].ifBlank { null }
}

private fun escapeAttr(value: String): String =
    value.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")

/** Removes any remaining HTML tags (those not in ALLOWED_TAGS were left by processAllowedTags) */
private fun stripUnknownTags(html: String): String {
    val tagPattern = Regex("<(/?)([a-zA-Z][a-zA-Z0-9]*)([^>]*)>")
    return tagPattern.replace(html) { match ->
        val tag = match.groupValues[2].lowercase()
        if (tag in ALLOWED_TAGS) match.value else ""
    }
}

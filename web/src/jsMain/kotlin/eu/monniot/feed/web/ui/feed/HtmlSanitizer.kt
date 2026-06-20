package eu.monniot.feed.web.ui.feed

/**
 * Minimal HTML allowlist sanitizer for article body content.
 *
 * Allowed tags: <p>, <a href>, <strong>, <em>, <blockquote>,
 *               <ul>, <ol>, <li>, <img src alt>, <h2>, <h3>,
 *               <br>, <pre>, <code>, <samp>, <kbd>
 *
 * Stripped unconditionally: <script>, <iframe>, <style>, inline event
 * handlers (on*="..."), non-allowlisted URL schemes.
 *
 * URL scheme policy (allowlist, not denylist):
 *  - <a href>: permits http:, https:, protocol-relative (//), and relative
 *    URLs (no scheme). Everything else — including javascript:, data:,
 *    vbscript:, etc. — is rejected and the href dropped entirely.
 *  - <img src>: permits http:, https:, protocol-relative (//), relative URLs
 *    (no scheme), and data:image/ (embedded images). Everything else is
 *    rejected and the src dropped.
 *
 * Attribute values are normalised before scheme-checking: ASCII control
 * characters (U+0000–U+001F) are stripped and the result is trimmed, matching
 * what browsers do when they parse URLs. This defeats whitespace-prefix and
 * embedded-tab/newline bypass techniques.
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
private val ALLOWED_TAGS = setOf(
    "p", "a", "strong", "em", "blockquote", "ul", "ol", "li", "img", "h2", "h3",
    "br", "pre", "code", "samp", "kbd",
)

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
            if (href != null && isAllowedHref(href)) {
                parts += "href=\"${escapeAttr(href)}\""
                parts += "rel=\"noopener noreferrer\""
                parts += "target=\"_blank\""
            }
        }
        "img" -> {
            val src = extractAttr(rawAttrs, "src")
            val alt = extractAttr(rawAttrs, "alt") ?: ""
            if (src != null && isAllowedSrc(src)) {
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

/**
 * Normalises a URL attribute value for scheme checking.
 *
 * Browsers strip ASCII control characters (U+0000–U+001F, including tab,
 * newline, carriage-return) and U+007F (DEL) from URLs before processing them.
 * An attacker can embed these characters to defeat a naive
 * `startsWith("javascript:")` check (e.g. `"jav\tascript:alert(1)"` or
 * `" javascript:alert(1)"`). We apply the same normalisation before our
 * allowlist check.
 */
private fun normalizeUrlForCheck(value: String): String =
    value.filter { it.code > 0x1F && it.code != 0x7F }.trim()

/**
 * Returns true if the href value is safe for an <a> element.
 *
 * Allowed: http:, https:, protocol-relative (//), and relative URLs (those
 * that contain no scheme — i.e. no ":" before the first "/", "?", or "#",
 * or no ":" at all). Everything else is rejected.
 */
private fun isAllowedHref(raw: String): Boolean {
    val url = normalizeUrlForCheck(raw)
    if (url.isEmpty()) return false
    val lower = url.lowercase()
    return when {
        lower.startsWith("http://") -> true
        lower.startsWith("https://") -> true
        lower.startsWith("//") -> true
        // Relative URL: no scheme present. A scheme would appear as
        // "word:" before any path separator. Reject if we see that pattern.
        else -> !hasScheme(lower)
    }
}

/**
 * Returns true if the src value is safe for an <img> element.
 *
 * Allowed: http:, https:, protocol-relative (//), relative URLs (no scheme),
 * and data:image/ excluding data:image/svg+xml (embedded raster images only).
 *
 * data:text/ and other data: subtypes are rejected to prevent script execution
 * via data:text/html or data:application/javascript.
 *
 * data:image/svg+xml is excluded because SVG can embed <script> elements.
 * Modern browsers sandbox SVG loaded via <img src> and do not execute those
 * scripts, but the exclusion is explicit defense-in-depth for older clients
 * and avoids an invisible security assumption in the code.
 */
private fun isAllowedSrc(raw: String): Boolean {
    val url = normalizeUrlForCheck(raw)
    if (url.isEmpty()) return false
    val lower = url.lowercase()
    return when {
        lower.startsWith("http://") -> true
        lower.startsWith("https://") -> true
        lower.startsWith("//") -> true
        // SVG can embed <script>; exclude it even though modern browsers sandbox
        // SVG loaded via <img src>. Other data:image/ subtypes (png, jpeg, gif,
        // webp, avif) are raster formats that cannot execute scripts.
        lower.startsWith("data:image/svg") -> false
        lower.startsWith("data:image/") -> true
        // Relative URLs: allowed when no scheme is present
        else -> !hasScheme(lower)
    }
}

/**
 * Returns true if [lower] (already lowercased) looks like it contains a URL
 * scheme — i.e. it matches `[a-z][a-z0-9+\-.]*:` at the start.
 *
 * We use this to distinguish relative URLs (safe) from unknown-scheme URLs
 * (unsafe) in [isAllowedHref].
 */
private fun hasScheme(lower: String): Boolean {
    // A scheme is defined by RFC 3986 as: ALPHA *( ALPHA / DIGIT / "+" / "-" / "." )
    // followed by ":". We only need to detect this pattern; we do not need to
    // parse the full URL.
    val colonIdx = lower.indexOf(':')
    if (colonIdx <= 0) return false
    // RFC 3986: the first character of a scheme must be ALPHA; subsequent chars
    // may be ALPHA / DIGIT / "+" / "-" / ".". Without this check a path segment
    // like "1999:the-talk" (digit-first) would be wrongly classified as a scheme
    // and rejected as an unsafe relative URL.
    if (!lower[0].isLetter()) return false
    return lower.substring(0, colonIdx).all { it.isLetter() || it.isDigit() || it == '+' || it == '-' || it == '.' }
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

private val BLOCK_TAGS = setOf(
    "div", "section", "article", "header", "footer", "nav", "aside", "main",
    "figure", "figcaption", "details", "summary", "address",
)

/** Removes any remaining HTML tags (those not in ALLOWED_TAGS were left by processAllowedTags) */
private fun stripUnknownTags(html: String): String {
    val tagPattern = Regex("<(/?)([a-zA-Z][a-zA-Z0-9]*)([^>]*)>")
    return tagPattern.replace(html) { match ->
        val tag = match.groupValues[2].lowercase()
        val closing = match.groupValues[1] == "/"
        if (tag in ALLOWED_TAGS) match.value
        else if (closing && tag in BLOCK_TAGS) "\n"
        else ""
    }
}

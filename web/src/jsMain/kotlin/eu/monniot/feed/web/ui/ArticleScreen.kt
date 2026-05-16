package eu.monniot.feed.web.ui

import org.w3c.dom.HTMLElement

fun renderArticle(container: HTMLElement, articleUrl: String, onBack: () -> Unit) {
    val escapedUrl = articleUrl.replace("\"", "&quot;")
    container.innerHTML = """
        <button id="article-back">← Back</button>
        <a href="$escapedUrl" target="_blank" rel="noopener noreferrer">Open in browser</a>
        <br>
        <iframe src="$escapedUrl" width="100%" height="600"
            sandbox="allow-same-origin allow-scripts"
            style="border:none"></iframe>
    """.trimIndent()
    container.querySelector("#article-back")?.addEventListener("click", { onBack() })
}

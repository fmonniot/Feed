package eu.monniot.feed.web.ui

import eu.monniot.feed.web.ui.dom.render
import kotlinx.html.ButtonType
import kotlinx.html.a
import kotlinx.html.br
import kotlinx.html.button
import kotlinx.html.iframe
import kotlinx.html.id
import org.w3c.dom.HTMLElement

fun renderArticle(container: HTMLElement, articleUrl: String, onBack: () -> Unit) {
    render(container) {
        button(type = ButtonType.button) {
            id = "article-back"
            +"← Back"
        }
        a(href = articleUrl, target = "_blank") {
            attributes["rel"] = "noopener noreferrer"
            +"Open in browser"
        }
        br()
        iframe {
            src = articleUrl
            width = "100%"
            height = "600"
            attributes["sandbox"] = "allow-same-origin allow-scripts"
            attributes["style"] = "border:none"
        }
    }
    container.querySelector("#article-back")?.addEventListener("click", { onBack() })
}

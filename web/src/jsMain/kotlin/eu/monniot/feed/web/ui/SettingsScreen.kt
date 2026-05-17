package eu.monniot.feed.web.ui

import eu.monniot.feed.shared.FeedViewModel
import eu.monniot.feed.shared.data.DefaultSort
import eu.monniot.feed.shared.data.KeepArticles
import eu.monniot.feed.shared.data.ReaderTheme
import eu.monniot.feed.shared.data.RefreshInterval
import eu.monniot.feed.web.Route
import eu.monniot.feed.web.navigate
import eu.monniot.feed.web.ui.components.segmented
import eu.monniot.feed.web.ui.components.wireSegmentedClicks
import eu.monniot.feed.web.ui.dom.render
import eu.monniot.feed.web.ui.feed.renderSidebar
import kotlinx.browser.document
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.html.ButtonType
import kotlinx.html.InputType
import kotlinx.html.TagConsumer
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.dom.append
import kotlinx.html.h1
import kotlinx.html.id
import kotlinx.html.input
import kotlinx.html.p
import kotlinx.html.span
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement

// --------------------------------------------------------------------------
// Element IDs
// --------------------------------------------------------------------------

private const val SETTINGS_SIDEBAR_ID = "settings-screen-sidebar"
private const val SETTINGS_CONTENT_ID = "settings-screen-content"
private const val SETTINGS_URL_INPUT_ID = "settings-url-input"
private const val SETTINGS_URL_ERROR_ID = "settings-url-error"
private const val SETTINGS_OPML_STATUS_ID = "settings-opml-status"
private const val SETTINGS_OPML_INPUT_ID = "settings-opml-file-input"

// --------------------------------------------------------------------------
// Public entry point
// --------------------------------------------------------------------------

/**
 * Renders the Settings screen into [container].
 *
 * Layout: 220px sidebar (from Phase 4) + a single content area (max-width
 * 640px centered) with three sections — Reading, Sync, Account — each
 * preceded by an uppercase eyebrow label. Settings rows use segmented
 * controls bound to [FeedViewModel.prefs].
 */
fun renderSettings(container: HTMLElement, viewModel: FeedViewModel) {
    render(container) {
        // Two-column shell: sidebar + content area
        div {
            attributes["style"] = buildString {
                append("display: flex;")
                append("height: 100vh;")
                append("overflow: hidden;")
            }

            // Sidebar (Phase 4)
            div {
                id = SETTINGS_SIDEBAR_ID
                attributes["style"] = buildString {
                    append("width: 220px;")
                    append("flex-shrink: 0;")
                    append("display: flex;")
                    append("flex-direction: column;")
                    append("background: var(--feed-panel);")
                    append("border-right: 1px solid var(--feed-border);")
                    append("height: 100vh;")
                    append("overflow: hidden;")
                }
            }

            // Scrollable content area
            div {
                id = SETTINGS_CONTENT_ID
                attributes["style"] = buildString {
                    append("flex: 1;")
                    append("overflow-y: auto;")
                    append("padding: 48px 40px;")
                    append("background: var(--feed-bg);")
                }
            }
        }
    }

    // Mount sidebar
    val sidebarEl = document.getElementById(SETTINGS_SIDEBAR_ID) as? HTMLElement
    if (sidebarEl != null) {
        renderSidebar(sidebarEl, viewModel)
    }

    // Initial render of the content area with the current prefs
    renderSettingsContent(viewModel)

    // Re-render content whenever prefs change so segmented controls track the
    // live state (e.g. after the user changes a value on another screen).
    GlobalScope.launch {
        viewModel.prefs.collect {
            renderSettingsContent(viewModel)
        }
    }

    // Observe serverUrlError
    GlobalScope.launch {
        viewModel.serverUrlError.collect { err ->
            val errEl = document.getElementById(SETTINGS_URL_ERROR_ID) as? HTMLElement
            errEl?.textContent = err ?: ""
        }
    }

    // Observe OPML import status
    GlobalScope.launch {
        viewModel.opmlImportStatus.collect { status ->
            val statusEl = document.getElementById(SETTINGS_OPML_STATUS_ID) as? HTMLElement
            if (statusEl != null) {
                statusEl.textContent = status ?: ""
            }
        }
    }
}

// --------------------------------------------------------------------------
// Content rendering
// --------------------------------------------------------------------------

private fun renderSettingsContent(viewModel: FeedViewModel) {
    val content = document.getElementById(SETTINGS_CONTENT_ID) as? HTMLElement ?: return
    val prefs = viewModel.prefs.value

    content.innerHTML = ""
    content.append {
        // Page H1
        h1 {
            attributes["class"] = "type-page-h1"
            attributes["style"] = buildString {
                append("margin: 0 0 40px 0;")
                append("color: var(--feed-ink);")
            }
            +"Settings"
        }

        settingsContent(prefs, viewModel)
    }

    // Wire all segmented controls
    wireSegmentedClicks("mark-as-read-on-scroll", content) { value ->
        viewModel.updateMarkAsReadOnScroll(value == "on")
    }
    wireSegmentedClicks("reader-theme", content) { value ->
        val theme = when (value) {
            "Soft" -> ReaderTheme.Soft
            "Dim" -> ReaderTheme.Dim
            else -> ReaderTheme.Paper
        }
        viewModel.updateReaderTheme(theme)
    }
    wireSegmentedClicks("default-sort", content) { value ->
        val sort = if (value == "Priority") DefaultSort.Priority else DefaultSort.Newest
        viewModel.updateDefaultSort(sort)
    }
    wireSegmentedClicks("refresh-interval", content) { value ->
        val interval = when (value) {
            "15m" -> RefreshInterval.Min15
            "6h" -> RefreshInterval.Hour6
            "Manual" -> RefreshInterval.Manual
            else -> RefreshInterval.Hour1
        }
        viewModel.updateRefreshInterval(interval)
    }
    wireSegmentedClicks("keep-articles", content) { value ->
        val keep = when (value) {
            "30d" -> KeepArticles.Days30
            "1y" -> KeepArticles.Year1
            "∞" -> KeepArticles.Forever
            else -> KeepArticles.Days90
        }
        viewModel.updateKeepArticles(keep)
    }

    // Wire server URL save
    document.getElementById("settings-save-url")?.addEventListener("click", {
        val url = (document.getElementById(SETTINGS_URL_INPUT_ID) as? HTMLInputElement)?.value ?: ""
        viewModel.setServerUrl(url)
    })

    // Wire logout
    document.getElementById("settings-logout-btn")?.addEventListener("click", {
        viewModel.logout()
        navigate(Route.Login)
    })

    // Populate server URL input with current value
    GlobalScope.launch {
        val url = viewModel.serverUrl.value
        (document.getElementById(SETTINGS_URL_INPUT_ID) as? HTMLInputElement)?.value = url
    }

    // Wire OPML file input
    val fileInput = document.getElementById(SETTINGS_OPML_INPUT_ID) as? HTMLInputElement
    document.getElementById("settings-opml-btn")?.addEventListener("click", {
        fileInput?.click()
    })
    fileInput?.addEventListener("change", {
        val file = fileInput.files?.item(0) ?: return@addEventListener
        val reader = org.w3c.files.FileReader()
        reader.onload = { _ ->
            val text = reader.result as? String
            if (text != null) {
                viewModel.importOpml(text)
            }
            Unit
        }
        reader.readAsText(file)
    })
}

// --------------------------------------------------------------------------
// DSL helpers
// --------------------------------------------------------------------------

private fun TagConsumer<HTMLElement>.settingsContent(
    prefs: eu.monniot.feed.shared.data.UserPrefs.Snapshot,
    viewModel: FeedViewModel,
) {
    // ── Reading section ──────────────────────────────────────────────────────
    sectionEyebrow("Reading")

    settingsGroup {
        settingsRow(
            label = "Mark as read on scroll",
            isFirst = true,
        ) {
            segmented(
                options = listOf("off" to "Off", "on" to "On"),
                current = if (prefs.markAsReadOnScroll) "on" else "off",
                name = "mark-as-read-on-scroll",
                onSelect = {},  // wired externally via wireSegmentedClicks
            )
        }

        settingsRow(
            label = "Reader theme",
        ) {
            segmented(
                options = listOf("Paper" to "Paper", "Soft" to "Soft", "Dim" to "Dim"),
                current = prefs.readerTheme.name,
                name = "reader-theme",
                onSelect = {},
            )
        }

        settingsRow(
            label = "Default sort",
            isLast = true,
        ) {
            segmented(
                options = listOf("Newest" to "Newest", "Priority" to "Priority"),
                current = prefs.defaultSort.name,
                name = "default-sort",
                onSelect = {},
            )
        }
    }

    // ── Sync section ─────────────────────────────────────────────────────────
    sectionEyebrow("Sync")

    settingsGroup {
        settingsRow(
            label = "Refresh interval",
            isFirst = true,
        ) {
            segmented(
                options = listOf("15m" to "15m", "1h" to "1h", "6h" to "6h", "Manual" to "Manual"),
                current = when (prefs.refreshInterval) {
                    RefreshInterval.Min15 -> "15m"
                    RefreshInterval.Hour1 -> "1h"
                    RefreshInterval.Hour6 -> "6h"
                    RefreshInterval.Manual -> "Manual"
                },
                name = "refresh-interval",
                onSelect = {},
            )
        }

        settingsRow(
            label = "Keep articles",
            isLast = true,
        ) {
            segmented(
                options = listOf("30d" to "30d", "90d" to "90d", "1y" to "1y", "∞" to "∞"),
                current = when (prefs.keepArticles) {
                    KeepArticles.Days30 -> "30d"
                    KeepArticles.Days90 -> "90d"
                    KeepArticles.Year1 -> "1y"
                    KeepArticles.Forever -> "∞"
                },
                name = "keep-articles",
                onSelect = {},
            )
        }
    }

    // ── Account section ──────────────────────────────────────────────────────
    sectionEyebrow("Account")

    settingsGroup {
        // Signed in as (text-only row, no control)
        div {
            attributes["data-settings-row"] = "signed-in-as"
            attributes["style"] = buildString {
                append("display: flex;")
                append("align-items: center;")
                append("justify-content: space-between;")
                append("padding: 18px 0;")
                append("gap: 24px;")
                append("border-bottom: 1px solid var(--feed-border);")
            }
            span {
                attributes["class"] = "type-settings-label"
                attributes["style"] = "color: var(--feed-ink); max-width: 360px;"
                +"Signed in as"
            }
            span {
                attributes["style"] = buildString {
                    append("font-family: var(--feed-font-sans);")
                    append("font-size: 14px;")
                    append("color: var(--feed-ink3);")
                }
                +"local · this device"
            }
        }

        // Import OPML
        div {
            attributes["data-settings-row"] = "import-opml"
            attributes["style"] = buildString {
                append("display: flex;")
                append("align-items: center;")
                append("justify-content: space-between;")
                append("padding: 18px 0;")
                append("gap: 24px;")
                append("border-bottom: 1px solid var(--feed-border);")
            }
            div {
                attributes["style"] = "max-width: 360px;"
                span {
                    attributes["class"] = "type-settings-label"
                    attributes["style"] = "display: block; color: var(--feed-ink);"
                    +"Import OPML"
                }
                span {
                    id = SETTINGS_OPML_STATUS_ID
                    attributes["class"] = "type-settings-hint"
                    attributes["style"] = "display: block; color: var(--feed-ink3); margin-top: 4px;"
                }
            }
            div {
                // Hidden file input
                input(type = InputType.file) {
                    id = SETTINGS_OPML_INPUT_ID
                    attributes["accept"] = ".opml,.xml"
                    attributes["style"] = "display: none;"
                }
                // Visible "Choose file…" button (same style as reader action buttons)
                button(type = ButtonType.button) {
                    id = "settings-opml-btn"
                    attributes["style"] = buildString {
                        append("padding: 6px 12px;")
                        append("border: 1px solid var(--feed-border);")
                        append("border-radius: 4px;")
                        append("background: var(--feed-panel);")
                        append("font-family: var(--feed-font-sans);")
                        append("font-size: 12px;")
                        append("color: var(--feed-ink2);")
                        append("cursor: pointer;")
                    }
                    +"Choose file…"
                }
            }
        }

        // Server URL
        div {
            attributes["data-settings-row"] = "server-url"
            attributes["style"] = buildString {
                append("display: flex;")
                append("align-items: flex-start;")
                append("justify-content: space-between;")
                append("padding: 18px 0;")
                append("gap: 24px;")
                append("border-bottom: 1px solid var(--feed-border);")
            }
            div {
                attributes["style"] = "max-width: 360px;"
                span {
                    attributes["class"] = "type-settings-label"
                    attributes["style"] = "display: block; color: var(--feed-ink);"
                    +"Server URL"
                }
                p {
                    id = SETTINGS_URL_ERROR_ID
                    attributes["style"] = buildString {
                        append("margin: 4px 0 0;")
                        append("font-family: var(--feed-font-sans);")
                        append("font-size: 12px;")
                        append("color: #c0392b;")
                    }
                }
            }
            div {
                attributes["style"] = "display: flex; gap: 8px; align-items: center;"
                input(type = InputType.text) {
                    id = SETTINGS_URL_INPUT_ID
                    attributes["style"] = buildString {
                        append("padding: 6px 10px;")
                        append("border: 1px solid var(--feed-border);")
                        append("border-radius: 4px;")
                        append("background: var(--feed-panel);")
                        append("font-family: var(--feed-font-sans);")
                        append("font-size: 13px;")
                        append("color: var(--feed-ink);")
                        append("width: 240px;")
                    }
                }
                button(type = ButtonType.button) {
                    id = "settings-save-url"
                    attributes["style"] = buildString {
                        append("padding: 6px 12px;")
                        append("border: 1px solid var(--feed-border);")
                        append("border-radius: 4px;")
                        append("background: var(--feed-panel);")
                        append("font-family: var(--feed-font-sans);")
                        append("font-size: 12px;")
                        append("color: var(--feed-ink2);")
                        append("cursor: pointer;")
                    }
                    +"Save"
                }
            }
        }

        // Logout
        div {
            attributes["data-settings-row"] = "logout"
            attributes["style"] = buildString {
                append("display: flex;")
                append("align-items: center;")
                append("justify-content: space-between;")
                append("padding: 18px 0;")
                append("gap: 24px;")
            }
            span {
                attributes["class"] = "type-settings-label"
                attributes["style"] = "color: var(--feed-ink); max-width: 360px;"
                +"Logout"
            }
            button(type = ButtonType.button) {
                id = "settings-logout-btn"
                attributes["style"] = buildString {
                    append("padding: 6px 12px;")
                    append("border: 1px solid var(--feed-border);")
                    append("border-radius: 4px;")
                    append("background: var(--feed-panel);")
                    append("font-family: var(--feed-font-sans);")
                    append("font-size: 12px;")
                    append("color: var(--feed-ink2);")
                    append("cursor: pointer;")
                }
                +"Sign out"
            }
        }
    }
}

/** Renders the uppercase section eyebrow label. */
private fun TagConsumer<HTMLElement>.sectionEyebrow(title: String) {
    div {
        attributes["class"] = "type-eyebrow"
        attributes["style"] = buildString {
            append("color: var(--feed-ink3);")
            append("letter-spacing: 0.1em;")
            append("font-size: 11px;")
            append("margin-bottom: 8px;")
            append("margin-top: 32px;")
        }
        +title.uppercase()
    }
}

/** A rounded container for a group of settings rows. */
private fun TagConsumer<HTMLElement>.settingsGroup(block: TagConsumer<HTMLElement>.() -> Unit) {
    div {
        attributes["style"] = buildString {
            append("background: var(--feed-panel);")
            append("border: 1px solid var(--feed-border);")
            append("border-radius: 4px;")
            append("padding: 0 20px;")
            append("max-width: 640px;")
        }
        block()
    }
}

/**
 * A single settings row: label left (max-width 360px), control right,
 * 24px gap, 18px top/bottom padding. A 1px bottom border separates rows
 * unless this is the last row in its group.
 */
private fun TagConsumer<HTMLElement>.settingsRow(
    label: String,
    hint: String? = null,
    isFirst: Boolean = false,
    isLast: Boolean = false,
    control: TagConsumer<HTMLElement>.() -> Unit,
) {
    div {
        attributes["data-settings-row"] = label
        attributes["style"] = buildString {
            append("display: flex;")
            append("align-items: center;")
            append("justify-content: space-between;")
            append("padding: 18px 0;")
            append("gap: 24px;")
            if (!isLast) {
                append("border-bottom: 1px solid var(--feed-border);")
            }
        }
        div {
            attributes["style"] = "max-width: 360px;"
            span {
                attributes["class"] = "type-settings-label"
                attributes["style"] = "display: block; color: var(--feed-ink);"
                +label
            }
            if (hint != null) {
                span {
                    attributes["class"] = "type-settings-hint"
                    attributes["style"] = "display: block; color: var(--feed-ink3); margin-top: 4px;"
                    +hint
                }
            }
        }
        control()
    }
}

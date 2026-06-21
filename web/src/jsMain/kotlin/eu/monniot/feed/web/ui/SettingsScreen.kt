package eu.monniot.feed.web.ui

import eu.monniot.feed.shared.FeedViewModel
import eu.monniot.feed.shared.api.OpmlFeedResult
import eu.monniot.feed.shared.data.Density
import eu.monniot.feed.web.CLIENT_VERSION
import eu.monniot.feed.shared.data.KeepArticles
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
import kotlinx.html.h1
import kotlinx.html.id
import kotlinx.html.input
import kotlinx.html.li
import kotlinx.html.span
import kotlinx.html.ul
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement

// --------------------------------------------------------------------------
// Element IDs
// --------------------------------------------------------------------------

private const val SETTINGS_SIDEBAR_ID = "settings-screen-sidebar"
private const val SETTINGS_CONTENT_ID = "settings-screen-content"
private const val SETTINGS_OPML_STATUS_ID = "settings-opml-status"
private const val SETTINGS_OPML_FAILURES_ID = "settings-opml-failures"
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
                    append("padding: 48px 40px 60px;")
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

    // Clear stale OPML state from a previous visit (parity with Android's DisposableEffect).
    viewModel.clearOpmlImportStatus()
    viewModel.clearOpmlImportFailures()

    // Kick off the server version fetch; the collect below will re-render when it arrives.
    viewModel.loadServerVersion()

    // Sync the local keep-articles pref with the server-side retention setting.
    viewModel.loadRetention()

    // Initial render of the content area with the current prefs
    renderSettingsContent(viewModel)

    // Re-render content whenever prefs change so segmented controls track the
    // live state (e.g. after the user changes a value on another screen).
    GlobalScope.launch {
        viewModel.prefs.collect {
            renderSettingsContent(viewModel)
        }
    }

    // Re-render when the server version loads (or fails) so the About row updates.
    GlobalScope.launch {
        viewModel.serverVersion.collect {
            renderSettingsContent(viewModel)
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

    // Observe OPML import failures — render inline list under the status span
    GlobalScope.launch {
        viewModel.opmlImportFailures.collect { failures ->
            val listEl = document.getElementById(SETTINGS_OPML_FAILURES_ID) as? HTMLElement
            updateOpmlFailureList(failures, listEl)
        }
    }
}

// --------------------------------------------------------------------------
// Version hint helper (internal for testability)
// --------------------------------------------------------------------------

internal fun buildVersionHint(serverVersion: String?): String =
    if (serverVersion != null)
        "Client v$CLIENT_VERSION · Server v$serverVersion"
    else
        "Client v$CLIENT_VERSION · Server unreachable"

// --------------------------------------------------------------------------
// Content rendering
// --------------------------------------------------------------------------

private fun renderSettingsContent(viewModel: FeedViewModel) {
    val content = document.getElementById(SETTINGS_CONTENT_ID) as? HTMLElement ?: return
    val prefs = viewModel.prefs.value

    render(content) {
        // Content column — max-width 640px, centred (spec §Web · Settings)
        div {
            attributes["style"] = "max-width: 640px; margin: 0 auto;"

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
    }

    // Wire all segmented controls
    wireSegmentedClicks("reader-font-size", content) { value ->
        viewModel.updateFontSize(value.toInt())
    }
    wireSegmentedClicks("density", content) { value ->
        val d = when (value) {
            "Compact" -> Density.Compact
            "Comfy" -> Density.Comfy
            else -> Density.Regular
        }
        viewModel.updateDensity(d)
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
    // Wire logout
    document.getElementById("settings-logout-btn")?.addEventListener("click", {
        viewModel.logout()
        navigate(Route.Login)
    })

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
            label = "Reader font size",
            hint = "Applies to the article body. Live-updates the open reader without reload.",
            isFirst = true,
        ) {
            segmented(
                options = listOf(
                    "14" to "14", "16" to "16", "18" to "18",
                    "20" to "20", "22" to "22", "24" to "24",
                ),
                current = prefs.fontSize.toString(),
                name = "reader-font-size",
                onSelect = {},
            )
        }

        settingsRow(
            label = "Article-list density",
            hint = "Compact hides excerpts; Comfy shows thumbnails.",
            isLast = true,
        ) {
            segmented(
                options = listOf(
                    "Compact" to "Compact",
                    "Regular" to "Regular",
                    "Comfy" to "Comfy",
                ),
                current = prefs.density.name,
                name = "density",
                onSelect = {},
            )
        }

    }

    // ── Sync section ─────────────────────────────────────────────────────────
    sectionEyebrow("Sync")

    settingsGroup {
        settingsRow(
            label = "Refresh interval",
            hint = "Client-side auto-poll cadence for the article list.",
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
            hint = "Retention window. ∞ disables retention.",
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
        // Import OPML — raw div to preserve dynamic status span in label area
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
                    attributes["class"] = "type-settings-hint"
                    attributes["style"] = "display: block; color: var(--feed-ink3); margin-top: 4px;"
                    +"Bring in a backup or export from another reader."
                }
                span {
                    id = SETTINGS_OPML_STATUS_ID
                    attributes["class"] = "type-settings-hint"
                    attributes["style"] = "display: block; color: var(--feed-ink3); margin-top: 2px;"
                }
                ul {
                    id = SETTINGS_OPML_FAILURES_ID
                    attributes["style"] = buildString {
                        append("display: none;")
                        append("margin: 6px 0 0 0;")
                        append("padding-left: 16px;")
                        append("color: var(--err-fg);")
                        append("font-size: 11px;")
                        append("list-style: disc;")
                    }
                }
            }
            div {
                // Hidden file input
                input(type = InputType.file) {
                    id = SETTINGS_OPML_INPUT_ID
                    attributes["accept"] = ".opml,.xml"
                    attributes["style"] = "display: none;"
                }
                // Visible "Choose file…" button
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

        // About
        settingsRow(
            label = "About",
            hint = buildVersionHint(viewModel.serverVersion.value),
        ) {
            span {
                attributes["style"] = buildString {
                    append("font-family: var(--feed-font-sans);")
                    append("font-size: 12px;")
                    append("color: var(--feed-ink3);")
                }
                +"—"
            }
        }

        // Logout
        settingsRow(
            label = "Logout",
            hint = "Clears the local session and returns to the login screen.",
            isLast = true,
        ) {
            button(type = ButtonType.button) {
                id = "settings-logout-btn"
                attributes["style"] = buildString {
                    append("padding: 6px 12px;")
                    append("border: 1px solid var(--feed-danger);")
                    append("border-radius: 4px;")
                    append("background: var(--feed-panel);")
                    append("font-family: var(--feed-font-sans);")
                    append("font-size: 12px;")
                    append("color: var(--feed-danger);")
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

internal fun updateOpmlFailureList(failures: List<OpmlFeedResult>, listEl: HTMLElement?) {
    listEl ?: return
    if (failures.isEmpty()) {
        listEl.style.display = "none"
        listEl.innerHTML = ""
        return
    }
    listEl.style.display = "block"
    listEl.innerHTML = ""
    for (feed in failures) {
        val li = document.createElement("li")
        val label = feed.title.ifBlank { feed.url }
        val errorPart = if (!feed.error.isNullOrBlank()) " — ${feed.error}" else ""
        li.textContent = "$label$errorPart"
        listEl.appendChild(li)
    }
}

/**
 * A flat group of settings rows (#72). The spec's Settings surface has no card
 * chrome — sections are an uppercase eyebrow over rows separated by 1px hairline
 * dividers, sitting directly on `bg`. This is just a transparent grouping div;
 * the row borders come from [settingsRow], and the 640px content cap + centering
 * is applied once by the wrapper in [renderSettingsContent].
 */
private fun TagConsumer<HTMLElement>.settingsGroup(block: TagConsumer<HTMLElement>.() -> Unit) {
    div {
        attributes["data-settings-group"] = "true"
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

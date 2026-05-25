package eu.monniot.feed.web.ui.components

import kotlinx.html.ButtonType
import kotlinx.html.TagConsumer
import kotlinx.html.a
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.span
import org.w3c.dom.HTMLElement

/**
 * The single source of truth for the sidebar footer state.
 *
 * Do not introduce a sixth state — every condition fits one of these five.
 */
sealed class SyncStatus {
    /** Normal state: last sync succeeded. [onRefresh] triggers a manual sync. */
    class Ok(val timeAgo: String, val onRefresh: () -> Unit = {}) : SyncStatus()

    /** A sync is in progress. */
    object Syncing : SyncStatus()

    /** The most recent sync attempt failed. [onRetry] triggers a fresh sync. */
    class Failed(val onRetry: () -> Unit) : SyncStatus()

    /** Device is offline; reads are served from cache. */
    object Offline : SyncStatus()

    /** Auto-poll is paused (e.g. 429 rate-limiting). [duration] is human-readable (e.g. "10m"). */
    class Paused(val duration: String) : SyncStatus()
}

/**
 * Sidebar footer state-machine component.
 *
 * DOM shape:
 * ```
 * div[data-component="sidebar-footer", data-state="{state}"]
 *   span[data-part="text"]     left label — state-specific copy
 *   span/button[data-part="glyph"]  right decorative glyph
 * ```
 *
 * For the `failed` state the text span contains an inline
 * `a[data-part="retry"]` element. Wire it via [wireSidebarFooterEvents].
 */
fun TagConsumer<HTMLElement>.sidebarFooter(status: SyncStatus) {
    val stateName = when (status) {
        is SyncStatus.Ok      -> "ok"
        SyncStatus.Syncing    -> "syncing"
        is SyncStatus.Failed  -> "failed"
        SyncStatus.Offline    -> "offline"
        is SyncStatus.Paused  -> "paused"
    }

    div {
        attributes["data-component"] = "sidebar-footer"
        attributes["data-state"] = stateName
        attributes["style"] = buildString {
            append("display: flex;")
            append("align-items: center;")
            append("justify-content: space-between;")
            append("font-family: var(--feed-font-sans);")
            append("font-size: 11px;")
        }

        // ── Left: text ──────────────────────────────────────────────────────

        when (status) {
            is SyncStatus.Ok -> span {
                attributes["data-part"] = "text"
                attributes["style"] = "color: var(--feed-ink3);"
                +"Synced ${status.timeAgo} ago"
            }

            SyncStatus.Syncing -> span {
                attributes["data-part"] = "text"
                attributes["style"] = "color: var(--feed-ink3);"
                +"Syncing…"
            }

            is SyncStatus.Failed -> span {
                attributes["data-part"] = "text"
                attributes["style"] = "color: var(--feed-ink2);"
                +"Last sync failed · "
                a(href = "#") {
                    attributes["data-part"] = "retry"
                    attributes["style"] = buildString {
                        append("color: var(--feed-accent);")
                        append("text-decoration: underline;")
                        append("text-underline-offset: 2px;")
                    }
                    +"retry"
                }
            }

            SyncStatus.Offline -> span {
                attributes["data-part"] = "text"
                attributes["style"] = "color: var(--feed-ink2);"
                +"Offline · cache only"
            }

            is SyncStatus.Paused -> span {
                attributes["data-part"] = "text"
                attributes["style"] = "color: var(--feed-ink2);"
                +"Paused · ${status.duration}"
            }
        }

        // ── Right: glyph ────────────────────────────────────────────────────

        when (status) {
            is SyncStatus.Ok -> button(type = ButtonType.button) {
                attributes["data-part"] = "glyph"
                attributes["style"] = buildString {
                    append("background: none;")
                    append("border: none;")
                    append("cursor: pointer;")
                    append("color: var(--feed-ink3);")
                    append("font-size: 13px;")
                    append("padding: 0;")
                    append("line-height: 1;")
                }
                +"↻" // ↻
            }

            SyncStatus.Syncing -> span {
                attributes["data-part"] = "glyph"
                attributes["style"] = "color: var(--feed-ink3); font-size: 13px; line-height: 1;"
                +"↻"
            }

            is SyncStatus.Failed -> span {
                attributes["data-part"] = "glyph"
                attributes["style"] = "color: var(--err-fg); font-size: 13px; line-height: 1;"
                +"!"
            }

            SyncStatus.Offline -> span {
                attributes["data-part"] = "glyph"
                attributes["style"] = "color: var(--feed-ink3); font-size: 13px; line-height: 1;"
                +"○" // ○
            }

            is SyncStatus.Paused -> span {
                attributes["data-part"] = "glyph"
                attributes["style"] = "color: var(--warn-fg); font-size: 13px; line-height: 1;"
                +"‖" // ‖
            }
        }
    }
}

/**
 * Wires interactive event listeners for [status] into [footerEl].
 *
 * Call this after [sidebarFooter]'s DOM is attached. Safe to call for any
 * state — only [SyncStatus.Ok] and [SyncStatus.Failed] have interactive elements.
 */
fun wireSidebarFooterEvents(footerEl: HTMLElement, status: SyncStatus) {
    when (status) {
        is SyncStatus.Ok -> footerEl.querySelector("[data-part='glyph']")
            ?.addEventListener("click", { status.onRefresh() })
        is SyncStatus.Failed -> footerEl.querySelector("[data-part='retry']")
            ?.addEventListener("click", { it.preventDefault(); status.onRetry() })
        else -> Unit
    }
}

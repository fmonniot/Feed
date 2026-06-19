# Screenshot scenarios

Canonical catalog of the screens we capture for design-accuracy verification
(ticket #75). The **scenario name** is the shared filename stem across platforms,
so `build/.shots/web/<vp>/reader.png`, `build/.shots/android/reader.png` and the
matching reference artboard can be opened side by side.

- **Web** shots come from `scripts/shot-web.sh` (one file per scenario, per viewport).
- **Android** shots are captured **manually** — navigate the running app to the
  screen, then run `scripts/shot-android.sh <scenario>`. `shot-android.sh --list`
  prints the rows below.
- **Reference** artboards come from `scripts/shot-ref.sh`
  (`build/.shots/ref/`). The web/Android editorial artboards are *combined*
  (list + reader in one frame), so they map to more than one live scenario.

The platforms are **not symmetric** — capture what exists, leave the rest. Known
asymmetries: Android has no per-feed list and adds a Server-URL editor; web has
neither a tab bar nor a Server-URL screen. These are intentional (see
`spec/VISUAL_SPEC.md` and FEATURES.md), not bugs to "fix" toward each other.

## Scenarios

| Scenario | Web route | Android nav | Reference artboard |
|---|---|---|---|
| `login` | `#login` (logged out) | Cold start logged out → `login` screen | `login-web` / `login-android` |
| `unread` | `#list` | **Unread** tab (default landing) | `desktop-editorial` / `android-editorial` (list pane) |
| `all` | `#all` | **All** tab | `desktop-editorial` / `android-editorial` (list pane) |
| `reader` | `#article/<id>` | Tap any article → full-screen reader (tab bar hidden) | `desktop-editorial` / `android-editorial` (reader pane) |
| `feeds` | `#subscriptions` | **Feeds** tab | subscriptions appears in `edge-*` artboards |
| `settings` | `#settings` | **Settings** tab | — (see VISUAL_SPEC § Settings) |
| `feed` | `#feed/<id>` | — (no per-feed list on Android) | — |
| `server-config` | — (web has no Server-URL screen) | **Settings** → Server URL row | — |
| `inspector` | `#feed/<id>/parse-error` | Feeds/article → parse-error details | `edge-feed-parse` / `edge-raw-response` |

### Reference-only states

These exist as design artboards but are awkward to reproduce in the live app on
demand; compare against the reference directly until we have seed presets for them.

| Scenario | Reference artboard | What it shows |
|---|---|---|
| `state-empty` | `state-empty` / `state-empty-m` | Empty article list |
| `state-sync-failed` | `state-sync-failed` / `state-sync-failed-m` | Sync failure banner |
| `state-syncing` | `state-syncing` / `state-syncing-m` | Mid-sync loading |

## Android capture recipe

Get the app onto a running emulator/device first:

```sh
( cd server && cargo run )            # so the client has data to show
./scripts/shots/seed.sh               # populate sample feeds (optional)
./gradlew :app:installDebug
```

Then, for each scenario above, drive the app to the screen and capture:

```sh
./scripts/shot-android.sh unread      # Unread tab (default after login)
./scripts/shot-android.sh all         # tap the All tab
./scripts/shot-android.sh reader      # tap any article
./scripts/shot-android.sh feeds       # tap the Feeds tab
./scripts/shot-android.sh settings    # tap the Settings tab
./scripts/shot-android.sh server-config  # Settings → Server URL
./scripts/shot-android.sh login       # log out (Settings → Logout) first
```

Files land in `build/.shots/android/<scenario>.png`. Use the **same scenario
name** as the web/reference shots so they line up.

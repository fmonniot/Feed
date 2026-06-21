// Subscriptions · Feed errors — visual exploration (Paper palette, web + Android).
// Three approaches to surfacing per-feed error detail in the Subscriptions screen.
//
// Note: error types shown here (HTTP 410, parse failure, HTTP 500) are a
// representative starting point. The final implementation will cover the
// exhaustive set of conditions that produce the sidebar `!` badge.
//
// ─ Var A  "Needs attention" pinned group ──────────────────────────────────────
//   Broken feeds are pulled out of their folders into a collapsible section at
//   the top. Each row expands to reveal a mono diagnostic block + action buttons.
//
// ─ Var B  In-list inline ──────────────────────────────────────────────────────
//   Broken feeds stay in their folder positions with an error badge. Clicking
//   opens an accordion with the diagnostic block + action buttons below the row.
//
// ─ Var C  Summary callout + expandable panel ──────────────────────────────────
//   A compact error strip ("3 feeds need attention") sits above the search bar.
//   Clicking expands a dedicated panel; each feed row inside is individually
//   expandable for its full diagnostic.

// ── Seed data ─────────────────────────────────────────────────────────────────
const SE_ERR = [
  {
    feedId: 'coldtake',
    badge: '410 GONE',
    severity: 'error',
    since: '14d',
    url: 'coldtake.blog/feed.xml',
    mono: [
      'HTTP 410 Gone · coldtake.blog/feed.xml',
      '14 consecutive failures · since 06 May 2026',
      'last attempt: 2h ago · next retry: none',
      '↳ permanent failure signal; retries paused',
    ].join('\n'),
    human: 'The publisher signals this feed is permanently gone. Cached articles are preserved. No further automatic retries are scheduled.',
    actions: [
      { label: 'Retry once',  danger: false },
      { label: 'Fix URL…',    danger: false },
      { label: 'View raw ↗', danger: false },
      { label: 'Unsubscribe', danger: true  },
    ],
  },
  {
    feedId: 'frequencies',
    badge: 'PARSE FAIL',
    severity: 'error',
    since: '6h',
    url: 'frequencies.fm/rss',
    mono: [
      '200 OK · text/html  (expected application/rss+xml)',
      'frequencies.fm/rss · 1.4 KB',
      'parser: unexpected <!DOCTYPE html> at line 1, col 1',
      '4 consecutive failures · next retry in ~2h',
    ].join('\n'),
    human: 'The server returned HTML instead of a feed — likely a maintenance page or login wall. Showing stale articles from 6h ago.',
    actions: [
      { label: 'Retry now',   danger: false },
      { label: 'View raw ↗', danger: false },
      { label: 'Unsubscribe', danger: true  },
    ],
  },
  {
    feedId: 'atlas',
    badge: 'HTTP 500',
    severity: 'warn',
    since: '3h',
    url: 'atlasessays.org/feed',
    mono: [
      'HTTP 500 Internal Server Error · atlasessays.org/feed',
      '2 consecutive failures · last attempt: 3h ago',
      'next retry in ~30m',
    ].join('\n'),
    human: 'The server is returning errors. This usually resolves on its own. Articles from 3h ago are still available.',
    actions: [
      { label: 'Retry now',   danger: false },
      { label: 'Unsubscribe', danger: true  },
    ],
  },
];

const SE_BROKEN_IDS = new Set(SE_ERR.map(e => e.feedId));
const SE_FS         = { coldtake: 'dead', frequencies: 'error', atlas: 'error' };

// ── Shared atoms ──────────────────────────────────────────────────────────────

function SEBadge({ severity, label }) {
  const t = severity === 'warn'
    ? { fg: EDGE_TOK.warnFg, bd: EDGE_TOK.warnBd, bg: EDGE_TOK.warnBg }
    : { fg: EDGE_TOK.errFg,  bd: EDGE_TOK.errBd,  bg: EDGE_TOK.errBg  };
  return (
    <span style={{
      fontFamily: 'ui-monospace, monospace', fontSize: 9.5,
      letterSpacing: '.14em', textTransform: 'uppercase', color: t.fg,
      padding: '2px 5px', border: `1px solid ${t.bd}`,
      borderRadius: 2, background: t.bg, flex: '0 0 auto', lineHeight: 1.1,
      whiteSpace: 'nowrap',
    }}>{label}</span>
  );
}

function SEMono({ text }) {
  const ED_C = React.useContext(EdThemeContext);
  return (
    <pre style={{
      margin: 0,
      fontFamily: '"SF Mono", "Fira Code", ui-monospace, monospace',
      fontSize: 11, lineHeight: 1.7, color: ED_C.ink2,
      background: ED_C.bg, border: `1px solid ${ED_C.border}`,
      padding: '10px 14px', borderRadius: 3,
      whiteSpace: 'pre-wrap', overflowWrap: 'break-word',
    }}>{text}</pre>
  );
}

function SEHuman({ text }) {
  const ED_C = React.useContext(EdThemeContext);
  return (
    <div style={{
      fontFamily: edUiFont, fontSize: 12.5, lineHeight: 1.55,
      color: ED_C.ink2, textWrap: 'pretty',
    }}>{text}</div>
  );
}

function SEActions({ actions }) {
  const ED_C = React.useContext(EdThemeContext);
  return (
    <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
      {actions.map(a => (
        <button key={a.label} style={{
          all: 'unset', cursor: 'pointer', padding: '6px 12px', borderRadius: 4,
          border: `1px solid ${a.danger ? ED_C.danger : ED_C.border}`,
          background: ED_C.panel, fontFamily: edUiFont, fontSize: 12,
          color: a.danger ? ED_C.danger : ED_C.ink2,
        }}>{a.label}</button>
      ))}
    </div>
  );
}

function SEDetail({ err }) {
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
      <SEMono text={err.mono} />
      <SEHuman text={err.human} />
      <SEActions actions={err.actions} />
    </div>
  );
}

// ── Web shared layout pieces ───────────────────────────────────────────────────

function SEPageHeader() {
  const ED_C = React.useContext(EdThemeContext);
  return (
    <div style={{ display: 'flex', alignItems: 'baseline', justifyContent: 'space-between', marginBottom: 24 }}>
      <h1 style={{ fontFamily: edSerifFont, fontSize: 28, fontWeight: 500, letterSpacing: '-.02em', margin: 0 }}>
        Subscriptions
      </h1>
      <button style={{
        all: 'unset', cursor: 'pointer', padding: '8px 14px', borderRadius: 4,
        background: ED_C.accent, color: ED_C.onAccent, fontFamily: edUiFont, fontSize: 12.5,
      }}>+ Add feed</button>
    </div>
  );
}

function SESearch({ count }) {
  const ED_C = React.useContext(EdThemeContext);
  return (
    <div style={{
      display: 'flex', alignItems: 'center', gap: 8, padding: '10px 14px',
      border: `1px solid ${ED_C.border}`, borderRadius: 4, background: ED_C.panel,
    }}>
      <span style={{ color: ED_C.ink3 }}>⌕</span>
      <span style={{ flex: 1, fontSize: 13, color: ED_C.ink3 }}>Search subscriptions…</span>
      <span style={{ fontSize: 11, color: ED_C.ink3 }}>{count} feeds</span>
    </div>
  );
}

// Feed list with all feeds including broken — badges shown; `expandedId` opens inline accordion (Var B)
function SEFeedListAll({ expandedId = null }) {
  const ED_C = React.useContext(EdThemeContext);
  const folders = [...new Set(FEEDS.map(f => f.folder))];
  return (
    <div>
      {folders.map(folder => (
        <div key={folder} style={{ marginBottom: 20 }}>
          <div style={{ fontSize: 10, letterSpacing: '.1em', textTransform: 'uppercase', color: ED_C.ink3, marginBottom: 6 }}>
            {folder}
          </div>
          {FEEDS.filter(f => f.folder === folder).map((f, i, arr) => {
            const err = SE_ERR.find(e => e.feedId === f.id);
            const isExp = !!(err && expandedId === f.id);
            const showDivider = !isExp && i < arr.length - 1;
            return (
              <div key={f.id} style={{ borderBottom: showDivider ? `1px solid ${ED_C.border}` : 'none' }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: 14, padding: '12px 0', cursor: err ? 'pointer' : 'default' }}>
                  <div style={{
                    width: 34, height: 34, borderRadius: 4, flex: '0 0 auto',
                    background: `oklch(0.85 0.05 ${f.hue})`, color: `oklch(0.35 0.08 ${f.hue})`,
                    display: 'flex', alignItems: 'center', justifyContent: 'center',
                    fontFamily: edSerifFont, fontWeight: 500, fontSize: 15,
                    opacity: err ? 0.65 : 1,
                  }}>{f.name[0]}</div>

                  <div style={{ flex: 1, minWidth: 0 }}>
                    <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                      <span style={{ fontFamily: edSerifFont, fontSize: 15, fontWeight: 500 }}>{f.name}</span>
                      {err && <SEBadge severity={err.severity} label={err.badge} />}
                    </div>
                    <div style={{ fontSize: 11, color: ED_C.ink3, marginTop: 3 }}>{f.url}</div>
                  </div>

                  {err ? (
                    <React.Fragment>
                      <span style={{ fontSize: 11, color: err.severity === 'error' ? EDGE_TOK.errFg : EDGE_TOK.warnFg, whiteSpace: 'nowrap' }}>{err.since}</span>
                      <span style={{ fontSize: 11, color: ED_C.ink3, width: 14 }}>{isExp ? '▲' : '▼'}</span>
                    </React.Fragment>
                  ) : (
                    <React.Fragment>
                      <span style={{ fontSize: 11, color: ED_C.ink3, fontVariantNumeric: 'tabular-nums' }}>{f.unread} new</span>
                      <button style={{ all: 'unset', color: ED_C.ink3, padding: '4px 8px' }}>⋯</button>
                    </React.Fragment>
                  )}
                </div>

                {isExp && (
                  <div style={{
                    margin: '0 0 14px', padding: '14px',
                    background: ED_C.panel,
                    border: `1px solid ${ED_C.border}`,
                    borderLeft: `3px solid ${err.severity === 'error' ? EDGE_TOK.errFg : EDGE_TOK.warnFg}`,
                    borderRadius: 3,
                  }}>
                    <SEDetail err={err} />
                  </div>
                )}

                {isExp && i < arr.length - 1 && <div style={{ height: 1, background: ED_C.border }} />}
              </div>
            );
          })}
        </div>
      ))}
    </div>
  );
}

// ════════════════════════════════════════════════════════════════════
// ANDROID — mobile subscriptions screen
// ════════════════════════════════════════════════════════════════════

function SEMobileShell({ subtitle, children }) {
  const ED_C = React.useContext(EdThemeContext);
  return (
    <div style={{ width: '100%', height: '100%', background: ED_C.bg, position: 'relative', overflow: 'hidden', display: 'flex', flexDirection: 'column' }}>
      <EdgeMHeader title="Subscriptions" subtitle={subtitle} topInset={14} />
      <div style={{ flex: 1, overflow: 'auto', paddingBottom: 88 }}>
        {children}
      </div>
      <EdgeMTabBar active="feeds" />
    </div>
  );
}

// Non-expandable summary strip — purely informational, no toggle.
function SESummaryBanner({ errCount = SE_ERR.length }) {
  const errColor   = errCount > 0 ? EDGE_TOK.errFg  : 'oklch(0.42 0.12 145)';
  const errBg      = errCount > 0 ? EDGE_TOK.errBg   : 'oklch(0.96 0.03 145)';
  const errBd      = errCount > 0 ? EDGE_TOK.errBd   : 'oklch(0.88 0.05 145)';
  const label      = errCount === 1 ? '1 error' : `${errCount} errors`;
  const warnCount  = SE_ERR.filter(e => e.severity === 'warn').length;
  const errOnly    = SE_ERR.filter(e => e.severity === 'error').length;
  const detail     = errOnly === errCount
    ? `${errCount} feed${errCount > 1 ? 's' : ''} failing — last checked 2h ago`
    : `${errOnly} failing · ${warnCount} warning — last checked 2h ago`;

  return (
    <div style={{
      display: 'flex', alignItems: 'center', gap: 12, padding: '10px 16px',
      background: errBg, border: `1px solid ${errBd}`,
      borderRadius: 4, marginBottom: 20,
    }}>
      <span style={{
        fontFamily: 'ui-monospace, monospace', fontSize: 9.5, letterSpacing: '.14em',
        textTransform: 'uppercase', color: errColor,
        padding: '2px 6px', border: `1px solid ${errBd}`, borderRadius: 2,
        background: 'rgba(255,255,255,.55)', flex: '0 0 auto', lineHeight: 1.2,
      }}>{label}</span>
      <span style={{ fontSize: 13, color: errColor, flex: 1, fontFamily: edUiFont }}>
        {detail}
      </span>
      {/* No expand control — details live in the list rows below */}
    </div>
  );
}

function SubsMixedWeb({ expandedId = null }) {
  const ED_C = React.useContext(EdThemeContext);
  return (
    <div style={{ flex: 1, height: '100%', overflow: 'auto', background: ED_C.bg, fontFamily: edUiFont, color: ED_C.ink }}>
      <div style={{ maxWidth: 720, margin: '0 auto', padding: '48px 40px 60px' }}>
        <SEPageHeader />
        <SESummaryBanner />
        <div style={{ marginBottom: 24 }}><SESearch count={FEEDS.length} /></div>
        <SEFeedListAll expandedId={expandedId} />
      </div>
    </div>
  );
}

// Android version of the mixed design
function SubsMixedMobile({ expandedId = null }) {
  const ED_C = React.useContext(EdThemeContext);
  const errCount = SE_ERR.length;
  const errOnly  = SE_ERR.filter(e => e.severity === 'error').length;
  const warnCount = errCount - errOnly;
  const folders = [...new Set(FEEDS.map(f => f.folder))];

  return (
    <SEMobileShell subtitle={`${FEEDS.length} feeds`}>
      <div style={{ padding: '14px 20px' }}>

        {/* Static summary banner */}
        <div style={{
          display: 'flex', alignItems: 'center', gap: 10, padding: '10px 14px',
          background: EDGE_TOK.errBg, border: `1px solid ${EDGE_TOK.errBd}`,
          borderRadius: 4, marginBottom: 18,
        }}>
          <span style={{
            fontFamily: 'ui-monospace, monospace', fontSize: 9.5, letterSpacing: '.14em',
            textTransform: 'uppercase', color: EDGE_TOK.errFg,
            padding: '2px 5px', border: `1px solid ${EDGE_TOK.errBd}`, borderRadius: 2,
            background: 'rgba(255,255,255,.55)', flex: '0 0 auto', lineHeight: 1.2,
          }}>{errCount} errors</span>
          <span style={{ fontSize: 13, color: EDGE_TOK.errFg, flex: 1, fontFamily: edUiFont, lineHeight: 1.35 }}>
            {errOnly} failing
            {warnCount > 0 ? ` · ${warnCount} warning` : ''}
            {' '}— 2h ago
          </span>
        </div>

        {/* Search bar */}
        <div style={{
          display: 'flex', alignItems: 'center', gap: 8, padding: '10px 14px',
          border: `1px solid ${ED_C.border}`, borderRadius: 4,
          background: ED_C.panel, marginBottom: 20,
        }}>
          <span style={{ color: ED_C.ink3 }}>⌕</span>
          <span style={{ flex: 1, fontSize: 13, color: ED_C.ink3 }}>Search subscriptions…</span>
        </div>

        {/* All feeds in folders, Var B inline accordion */}
        {folders.map(folder => (
          <div key={folder} style={{ marginBottom: 20 }}>
            <div style={{
              fontSize: 10, letterSpacing: '.1em', textTransform: 'uppercase',
              color: ED_C.ink3, marginBottom: 8,
            }}>{folder}</div>

            {FEEDS.filter(f => f.folder === folder).map((f, i, arr) => {
              const err = SE_ERR.find(e => e.feedId === f.id);
              const isExp = !!(err && expandedId === f.id);
              const showDivider = !isExp && i < arr.length - 1;

              return (
                <div key={f.id} style={{ borderBottom: showDivider ? `1px solid ${ED_C.border}` : 'none' }}>
                  <div style={{
                    display: 'flex', alignItems: 'center', gap: 12, padding: '13px 0',
                    cursor: err ? 'pointer' : 'default',
                  }}>
                    <div style={{
                      width: 38, height: 38, borderRadius: 6, flex: '0 0 auto',
                      background: `oklch(0.85 0.05 ${f.hue})`,
                      color: `oklch(0.35 0.08 ${f.hue})`,
                      display: 'flex', alignItems: 'center', justifyContent: 'center',
                      fontFamily: edSerifFont, fontWeight: 500, fontSize: 17,
                      opacity: err ? 0.6 : 1,
                    }}>{f.name[0]}</div>

                    <div style={{ flex: 1, minWidth: 0 }}>
                      <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
                        <span style={{ fontFamily: edSerifFont, fontSize: 15.5, fontWeight: 500 }}>{f.name}</span>
                        {err && <SEBadge severity={err.severity} label={err.badge} />}
                      </div>
                      <div style={{
                        fontSize: 11, color: ED_C.ink3, marginTop: 2,
                        overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
                      }}>{f.url}</div>
                    </div>

                    {err ? (
                      <React.Fragment>
                        <span style={{
                          fontSize: 11, whiteSpace: 'nowrap',
                          color: err.severity === 'error' ? EDGE_TOK.errFg : EDGE_TOK.warnFg,
                        }}>{err.since}</span>
                        <span style={{ fontSize: 11, color: ED_C.ink3, width: 14 }}>{isExp ? '▲' : '▼'}</span>
                      </React.Fragment>
                    ) : (
                      f.unread > 0
                        ? <span style={{ fontSize: 12, color: ED_C.ink3 }}>{f.unread}</span>
                        : null
                    )}
                  </div>

                  {isExp && (
                    <div style={{
                      margin: '0 0 12px', padding: '12px',
                      background: ED_C.panel,
                      border: `1px solid ${ED_C.border}`,
                      borderLeft: `3px solid ${err.severity === 'error' ? EDGE_TOK.errFg : EDGE_TOK.warnFg}`,
                      borderRadius: 3,
                    }}>
                      <SEDetail err={err} />
                    </div>
                  )}
                  {isExp && i < arr.length - 1 && (
                    <div style={{ height: 1, background: ED_C.border }} />
                  )}
                </div>
              );
            })}
          </div>
        ))}
      </div>
    </SEMobileShell>
  );
}

// Artboard wrappers for chosen direction
function SubsMixedWebDefault() {
  return (
    <EdgeShell sidebar={<EdgeSidebar active="subs" feedStatus={SE_FS} feeds={FEEDS} />}>
      <SubsMixedWeb expandedId={null} />
    </EdgeShell>
  );
}

function SubsMixedWebExpanded() {
  return (
    <EdgeShell sidebar={<EdgeSidebar active="subs" feedStatus={SE_FS} feeds={FEEDS} />}>
      <SubsMixedWeb expandedId="coldtake" />
    </EdgeShell>
  );
}

function SubsMixedMobileDefault() {
  return (
    <AndroidDevice width={412} height={892}>
      <EdThemeContext.Provider value={ED_PALETTES.paper}>
        <SubsMixedMobile expandedId={null} />
      </EdThemeContext.Provider>
    </AndroidDevice>
  );
}

function SubsMixedMobileExpanded() {
  return (
    <AndroidDevice width={412} height={892}>
      <EdThemeContext.Provider value={ED_PALETTES.paper}>
        <SubsMixedMobile expandedId="coldtake" />
      </EdThemeContext.Provider>
    </AndroidDevice>
  );
}

Object.assign(window, {
  SubsMixedWebDefault, SubsMixedWebExpanded,
  SubsMixedMobileDefault, SubsMixedMobileExpanded,
});

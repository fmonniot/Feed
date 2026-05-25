// Edge cases & error states — Android (Paper palette).
// One static snapshot per scenario, dropped on the design canvas as
// independent artboards beneath their desktop counterparts. Each composes
// the same atoms as the happy-path mobile prototype.
//
// Surface map (mobile-specific, per VISUAL_SPEC.md §States & feedback):
//   • Banner (web)                 → Snackbar (above the tab bar)
//   • Big mid-pane state (web)     → Big screen state (fills the list area)
//   • Sidebar `!` badge (web)      → Feeds-tab row badge
//   • Inline reader note (web)     → identical on mobile
//   • Inline form error (web)      → identical on mobile, inside the Feeds
//                                     search/paste row
//   • Modal interrupt (web)        → identical, sized to fit a 412dp frame

const EDGE_TOK_M = {
  warnBg: 'oklch(0.96 0.035 78)',
  warnFg: 'oklch(0.40 0.10 70)',
  warnBd: 'oklch(0.86 0.06 75)',
  errBg:  'oklch(0.965 0.025 25)',
  errFg:  'oklch(0.42 0.13 25)',
  errBd:  'oklch(0.86 0.07 25)',
};

// ── Header (matches EdMHeader from editorial-mobile.jsx) ────────────
function EdgeMHeader({ title, subtitle, topInset = 14 }) {
  const ED_C = React.useContext(EdThemeContext);
  return (
    <div style={{
      paddingTop: topInset + 14,
      paddingLeft: 22, paddingRight: 22, paddingBottom: 18,
      background: ED_C.bg, borderBottom: `1px solid ${ED_C.border}`,
      fontFamily: edUiFont, color: ED_C.ink, flex: '0 0 auto',
    }}>
      <h1 style={{ fontFamily: edSerifFont, fontSize: 30, fontWeight: 500,
        letterSpacing: '-.02em', lineHeight: 1.05, margin: 0 }}>{title}</h1>
      {subtitle ? <div style={{ fontSize: 12, color: ED_C.ink3, marginTop: 6 }}>{subtitle}</div> : null}
    </div>
  );
}

// ── Bottom tab bar (static; mirrors EdMTabBar) ──────────────────────
function EdgeMTabBar({ active = 'unread' }) {
  const ED_C = React.useContext(EdThemeContext);
  const items = [
    { id: 'unread',   label: 'Unread',   glyph: '◉' },
    { id: 'all',      label: 'All',      glyph: '☰' },
    { id: 'feeds',    label: 'Feeds',    glyph: '⌒' },
    { id: 'settings', label: 'Settings', glyph: '◌' },
  ];
  return (
    <div style={{
      position: 'absolute', left: 0, right: 0, bottom: 0,
      padding: '6px 0 30px', borderTop: `1px solid ${ED_C.border}`,
      background: 'rgba(249, 250, 251, 0.94)',
      backdropFilter: 'blur(24px)', WebkitBackdropFilter: 'blur(24px)',
      zIndex: 20, display: 'flex',
      fontFamily: edUiFont,
    }}>
      {items.map(t => {
        const isActive = t.id === active;
        return (
          <div key={t.id} style={{
            flex: 1, display: 'flex', flexDirection: 'column',
            alignItems: 'center', gap: 3,
            color: isActive ? ED_C.accent : ED_C.ink3,
          }}>
            <div style={{
              padding: '4px 18px', borderRadius: 999,
              background: isActive ? ED_C.accentSoft : 'transparent',
              fontFamily: edSerifFont, fontSize: 18, lineHeight: 1,
            }}>{t.glyph}</div>
            <div style={{ fontSize: 10, fontWeight: isActive ? 600 : 500 }}>{t.label}</div>
          </div>
        );
      })}
    </div>
  );
}

// ── Snackbar (above the tab bar; ink bg, panel fg) ──────────────────
function EdgeMSnackbar({ text, action, sticky = true }) {
  const ED_C = React.useContext(EdThemeContext);
  return (
    <div style={{
      position: 'absolute', left: 16, right: 16, bottom: 86,
      background: ED_C.ink, color: ED_C.panel,
      borderRadius: 4, padding: '14px 16px',
      fontFamily: edUiFont, fontSize: 13.5, lineHeight: 1.4,
      display: 'flex', alignItems: 'center', gap: 12,
      zIndex: 30,
    }}>
      <span style={{ flex: 1 }}>{text}</span>
      {action ? (
        <a href="#" style={{
          color: ED_C.accent, fontWeight: 500, fontSize: 13,
          textDecoration: 'none', flex: '0 0 auto',
          textTransform: 'uppercase', letterSpacing: '.04em',
        }}>{action}</a>
      ) : null}
    </div>
  );
}

// ── Big screen state — replaces the list area, leaves the tab bar ──
function EdgeMBigState({ eyebrow, title, body, primary, secondary, hint }) {
  const ED_C = React.useContext(EdThemeContext);
  return (
    <div style={{
      flex: 1, minHeight: 0, background: ED_C.bg, color: ED_C.ink,
      display: 'flex', alignItems: 'center', justifyContent: 'center',
      padding: '40px 28px 110px', overflow: 'hidden',
    }}>
      <div style={{ maxWidth: 320, textAlign: 'center' }}>
        {eyebrow ? (
          <div style={{
            fontFamily: 'ui-monospace, monospace', fontSize: 10, letterSpacing: '.14em',
            textTransform: 'uppercase', color: ED_C.ink3, marginBottom: 14,
          }}>{eyebrow}</div>
        ) : null}
        <div style={{
          fontFamily: edSerifFont, fontSize: 24, fontWeight: 500,
          letterSpacing: '-.02em', lineHeight: 1.15, marginBottom: 10,
        }}>{title}</div>
        <div style={{
          fontFamily: edSerifFont, fontStyle: 'italic', fontSize: 14.5,
          color: ED_C.ink2, lineHeight: 1.55, marginBottom: 24, textWrap: 'pretty',
        }}>{body}</div>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 8, alignItems: 'stretch' }}>
          {primary ? (
            <button style={{
              all: 'unset', cursor: 'pointer', padding: '12px 18px', borderRadius: 4,
              background: ED_C.ink, color: ED_C.panel, fontSize: 13.5, fontFamily: edUiFont,
              textAlign: 'center',
            }}>{primary}</button>
          ) : null}
          {secondary ? (
            <button style={{
              all: 'unset', cursor: 'pointer', padding: '11px 16px', borderRadius: 4,
              border: `1px solid ${ED_C.border}`, background: ED_C.panel, color: ED_C.ink2,
              fontFamily: edUiFont, fontSize: 13, textAlign: 'center',
            }}>{secondary}</button>
          ) : null}
        </div>
        {hint ? (
          <div style={{
            marginTop: 18, fontSize: 11.5, color: ED_C.ink3, fontFamily: edUiFont,
            textWrap: 'pretty',
          }}>{hint}</div>
        ) : null}
      </div>
    </div>
  );
}

// ── Modal interrupt (mobile-sized) ──────────────────────────────────
function EdgeMModal({ eyebrow, title, body, primary, secondary, detail }) {
  const ED_C = React.useContext(EdThemeContext);
  return (
    <div style={{
      position: 'absolute', inset: 0, zIndex: 50,
      background: 'rgba(20,25,40,.32)', backdropFilter: 'blur(2px)',
      display: 'flex', alignItems: 'center', justifyContent: 'center',
      padding: '0 24px',
    }}>
      <div style={{
        background: ED_C.bg, border: `1px solid ${ED_C.borderStrong}`,
        boxShadow: '0 24px 60px rgba(0,0,0,.18)',
        width: '100%', maxWidth: 340, padding: '28px 24px 24px',
        fontFamily: edUiFont, color: ED_C.ink,
      }}>
        {eyebrow ? (
          <div style={{
            fontFamily: 'ui-monospace, monospace', fontSize: 10, letterSpacing: '.14em',
            textTransform: 'uppercase', color: EDGE_TOK_M.warnFg, marginBottom: 12,
          }}>{eyebrow}</div>
        ) : null}
        <div style={{
          fontFamily: edSerifFont, fontSize: 22, fontWeight: 500, letterSpacing: '-.02em',
          lineHeight: 1.18, marginBottom: 10,
        }}>{title}</div>
        <div style={{
          fontFamily: edSerifFont, fontStyle: 'italic', fontSize: 14, color: ED_C.ink2,
          lineHeight: 1.5, marginBottom: detail ? 14 : 20, textWrap: 'pretty',
        }}>{body}</div>
        {detail ? (
          <div style={{
            padding: '10px 12px', marginBottom: 20,
            background: ED_C.panel, border: `1px solid ${ED_C.border}`, borderRadius: 3,
            fontFamily: edUiFont, fontSize: 12, color: ED_C.ink2,
            display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 12,
          }}>{detail}</div>
        ) : null}
        <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
          {primary ? (
            <button style={{
              all: 'unset', cursor: 'pointer', padding: '12px 16px', borderRadius: 4,
              background: ED_C.ink, color: ED_C.panel, fontSize: 13.5, fontFamily: edUiFont,
              textAlign: 'center',
            }}>{primary}</button>
          ) : null}
          {secondary ? (
            <button style={{
              all: 'unset', cursor: 'pointer', padding: '11px 16px', borderRadius: 4,
              border: `1px solid ${ED_C.border}`, background: ED_C.panel, color: ED_C.ink2,
              fontFamily: edUiFont, fontSize: 13, textAlign: 'center',
            }}>{secondary}</button>
          ) : null}
        </div>
      </div>
    </div>
  );
}

// ── List stub (static rows) ─────────────────────────────────────────
function EdgeMListStub({ articles = ARTICLES.slice(0, 4) }) {
  const ED_C = React.useContext(EdThemeContext);
  return (
    <div style={{ flex: 1, minHeight: 0, overflow: 'hidden', paddingBottom: 100 }}>
      {articles.map((a, i) => {
        const feed = FEED_BY_ID[a.feed];
        return (
          <div key={a.id} style={{
            display: 'flex', flexDirection: 'column', gap: 8,
            padding: '16px 22px',
            borderBottom: i < articles.length - 1 ? `1px solid ${ED_C.border}` : 'none',
            background: ED_C.bg,
          }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: 11, color: ED_C.ink3 }}>
              <span style={{ width: 6, height: 6, borderRadius: '50%', background: `oklch(0.65 0.12 ${feed.hue})` }} />
              <span style={{ fontWeight: 500, color: ED_C.ink2 }}>{feed.name}</span>
              <span>·</span>
              <span>{edTimeAgo(a.ts)}</span>
              <span style={{ flex: 1 }} />
              {a.unread ? <span style={{ width: 6, height: 6, borderRadius: '50%', background: ED_C.accent }} /> : null}
            </div>
            <div style={{
              fontFamily: edSerifFont, fontSize: 18, lineHeight: 1.2,
              fontWeight: 500, color: ED_C.ink, letterSpacing: '-.01em',
            }}>{a.title}</div>
            <div style={{
              fontSize: 12.5, color: ED_C.ink2, lineHeight: 1.45,
              display: '-webkit-box', WebkitLineClamp: 2, WebkitBoxOrient: 'vertical', overflow: 'hidden',
            }}>{a.excerpt}</div>
          </div>
        );
      })}
    </div>
  );
}

// ── Reader stub (full-screen, replaces the tab bar) ────────────────
function EdgeMReaderStub({ article = ARTICLES[0], topInset = 14, note = null }) {
  const ED_C = React.useContext(EdThemeContext);
  const feed = FEED_BY_ID[article.feed];
  return (
    <div style={{ position: 'absolute', inset: 0, background: ED_C.bg, overflow: 'hidden' }}>
      <div style={{
        paddingTop: topInset + 10, paddingLeft: 16, paddingRight: 16, paddingBottom: 10,
        borderBottom: `1px solid ${ED_C.border}`, background: ED_C.bg,
        display: 'flex', alignItems: 'center', gap: 8,
        fontFamily: edUiFont,
      }}>
        <span style={{ color: ED_C.accent, fontSize: 14, padding: '4px 6px' }}>‹ {feed.name}</span>
        <span style={{ flex: 1 }} />
        <button style={{ all: 'unset', padding: '6px 10px', border: `1px solid ${ED_C.border}`, borderRadius: 4, background: ED_C.panel, fontSize: 12, color: ED_C.ink2 }}>Aa</button>
        <button style={{ all: 'unset', padding: '6px 10px', border: `1px solid ${ED_C.border}`, borderRadius: 4, background: ED_C.panel, fontSize: 12, color: ED_C.ink2 }}>↗</button>
      </div>
      <div style={{ padding: '24px 24px 40px', fontFamily: edSerifFont, color: ED_C.ink, overflow: 'hidden' }}>
        <div style={{
          fontFamily: edUiFont, fontSize: 10.5, letterSpacing: '.08em', textTransform: 'uppercase',
          color: ED_C.ink3, display: 'flex', gap: 8, marginBottom: 14,
        }}>
          <span style={{ width: 6, height: 6, borderRadius: '50%', background: `oklch(0.65 0.12 ${feed.hue})`, alignSelf: 'center' }} />
          <span style={{ color: ED_C.ink2 }}>{feed.name}</span>
          <span>·</span>
          <span>{feed.author}</span>
          <span>·</span>
          <span>{edTimeAgo(article.ts)}</span>
        </div>
        <h1 style={{ fontSize: 26, fontWeight: 500, lineHeight: 1.15, letterSpacing: '-.02em', margin: '0 0 12px' }}>
          {article.title}
        </h1>
        <div style={{ fontStyle: 'italic', fontSize: 16, lineHeight: 1.5, color: ED_C.ink2, marginBottom: 22 }}>
          {article.excerpt}
        </div>
        {note}
        <div style={{ fontSize: 17, lineHeight: 1.65, color: ED_C.ink }}>
          <p style={{ margin: '0 0 1.1em', textWrap: 'pretty' }}>{ARTICLE_BODY[0]}</p>
        </div>
      </div>
    </div>
  );
}

// ── Feeds tab stub (used for Feeds-tab edge cases) ──────────────────
function EdgeMFeedsStub({
  topInset = 14,
  searchValue = '',
  addError = null,         // { kind: 'error'|'warn', text }
  feedStatus = {},         // { feedId: 'dead' | 'error' }
  emptyText = null,
}) {
  const ED_C = React.useContext(EdThemeContext);
  const rows = FEEDS.filter(f => f.name.toLowerCase().includes(searchValue.trim().toLowerCase()));
  const folders = [...new Set(rows.map(f => f.folder))];
  return (
    <React.Fragment>
      <EdgeMHeader title="Feeds" subtitle={`${FEEDS.length} subscriptions`} topInset={topInset} />
      <div style={{ flex: 1, minHeight: 0, overflow: 'hidden', paddingBottom: 100 }}>
        <div style={{ padding: '14px 22px 0' }}>
          <div style={{
            display: 'flex', alignItems: 'center', gap: 8, padding: '10px 14px',
            border: `1px solid ${addError ? (addError.kind === 'warn' ? EDGE_TOK_M.warnBd : EDGE_TOK_M.errBd) : ED_C.border}`,
            borderRadius: 4, background: ED_C.panel,
          }}>
            <span style={{ color: ED_C.ink3 }}>⌕</span>
            <span style={{ flex: 1, fontSize: 13, color: searchValue ? ED_C.ink : ED_C.ink3 }}>
              {searchValue || 'Search or paste a URL…'}
            </span>
          </div>
          {addError ? (
            <div style={{
              display: 'flex', alignItems: 'flex-start', gap: 8, marginTop: 8,
              fontSize: 12, lineHeight: 1.45,
              color: addError.kind === 'warn' ? EDGE_TOK_M.warnFg : EDGE_TOK_M.errFg,
              fontFamily: edUiFont,
            }}>
              <span style={{
                fontFamily: 'ui-monospace, monospace', fontSize: 9.5, letterSpacing: '.14em',
                textTransform: 'uppercase',
                padding: '1px 5px', border: `1px solid currentColor`, borderRadius: 2,
                marginTop: 1, flex: '0 0 auto', opacity: .8,
              }}>{addError.kind === 'warn' ? 'WARN' : 'ERR'}</span>
              <span>{addError.text}</span>
            </div>
          ) : null}
        </div>

        {rows.length === 0 ? (
          <div style={{
            padding: '60px 28px', textAlign: 'center',
            fontFamily: edSerifFont, fontStyle: 'italic',
            fontSize: 15, color: ED_C.ink3, lineHeight: 1.5,
          }}>
            {emptyText || `No subscriptions match “${searchValue}”.`}
          </div>
        ) : folders.map(folder => (
          <div key={folder}>
            <div style={{
              padding: '20px 22px 6px', fontFamily: edUiFont,
              fontSize: 10, letterSpacing: '.1em', textTransform: 'uppercase',
              fontWeight: 500, color: ED_C.ink3,
            }}>{folder}</div>
            {rows.filter(f => f.folder === folder).map((f, i, arr) => {
              const status = feedStatus[f.id];
              const dead = status === 'dead';
              const err  = status === 'error';
              return (
                <div key={f.id} style={{
                  display: 'flex', alignItems: 'center', gap: 14,
                  padding: '12px 22px',
                  borderBottom: i < arr.length - 1 ? `1px solid ${ED_C.border}` : 'none',
                  background: ED_C.bg,
                  opacity: dead ? .55 : 1,
                }}>
                  <div style={{
                    width: 34, height: 34, borderRadius: 4, flex: '0 0 auto',
                    background: `oklch(0.85 0.05 ${f.hue})`, color: `oklch(0.35 0.08 ${f.hue})`,
                    display: 'flex', alignItems: 'center', justifyContent: 'center',
                    fontFamily: edSerifFont, fontWeight: 500, fontSize: 15,
                  }}>{f.name[0]}</div>
                  <div style={{ flex: 1, minWidth: 0 }}>
                    <div style={{
                      display: 'flex', alignItems: 'center', gap: 8,
                      fontFamily: edSerifFont, fontSize: 15, fontWeight: 500, color: ED_C.ink,
                      textDecoration: dead ? 'line-through' : 'none',
                    }}>
                      <span style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{f.name}</span>
                      {(err || dead) ? (
                        <span style={{
                          fontFamily: 'ui-monospace, monospace', fontSize: 10, color: EDGE_TOK_M.errFg,
                          fontWeight: 600, padding: '0 4px',
                          border: `1px solid ${EDGE_TOK_M.errBd}`, borderRadius: 2,
                          background: EDGE_TOK_M.errBg,
                        }}>!</span>
                      ) : null}
                    </div>
                    <div style={{
                      fontSize: 11, color: ED_C.ink3, marginTop: 2,
                      overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
                    }}>{f.url}</div>
                  </div>
                  {!dead && f.unread > 0 ? (
                    <span style={{ fontSize: 11, color: ED_C.ink3, fontVariantNumeric: 'tabular-nums' }}>
                      {f.unread} new
                    </span>
                  ) : null}
                </div>
              );
            })}
          </div>
        ))}
      </div>
    </React.Fragment>
  );
}

// ── Shell ───────────────────────────────────────────────────────────
function EdgeMShell({ header, children, tabActive = 'unread', snackbar, modal, hideTabBar = false }) {
  const ED_C = React.useContext(EdThemeContext);
  return (
    <EdThemeContext.Provider value={ED_C}>
      <div style={{
        position: 'relative', width: '100%', height: '100%',
        background: ED_C.bg, fontFamily: edUiFont,
        display: 'flex', flexDirection: 'column', overflow: 'hidden',
      }}>
        {header}
        {children}
        {snackbar}
        {hideTabBar ? null : <EdgeMTabBar active={tabActive} />}
        {modal}
      </div>
    </EdThemeContext.Provider>
  );
}

// ════════════════════════════════════════════════════════════════════
// SCENARIOS (Android)
// ════════════════════════════════════════════════════════════════════

// 1 · OFFLINE — snackbar above the tab bar, cached list still scrolling.
function EdgeOfflineM({ topInset = 14 }) {
  return (
    <EdgeMShell
      header={<EdgeMHeader title="Unread" subtitle="24 articles · cached" topInset={topInset} />}
      tabActive="unread"
      snackbar={<EdgeMSnackbar text="You're offline. Showing cached articles." action="Retry" />}
    >
      <EdgeMListStub />
    </EdgeMShell>
  );
}

// 2 · SERVER UNREACHABLE — big screen state replaces the list area.
function EdgeServerDownM({ topInset = 14 }) {
  return (
    <EdgeMShell
      header={<EdgeMHeader title="Unread" subtitle="Last sync failed" topInset={topInset} />}
      tabActive="unread"
    >
      <EdgeMBigState
        eyebrow="ERR · CONN_REFUSED"
        title="Couldn't reach the server."
        body="We've tried 3 times in the last minute. Your cached articles are intact."
        primary="Retry now"
        secondary="Check service status"
        hint="Next retry in 28s."
      />
    </EdgeMShell>
  );
}

// 3 · RATE-LIMITED — snackbar with countdown.
function EdgeRateLimitedM({ topInset = 14 }) {
  return (
    <EdgeMShell
      header={<EdgeMHeader title="Unread" subtitle="24 articles · refresh paused" topInset={topInset} />}
      tabActive="unread"
      snackbar={<EdgeMSnackbar text="Rate limit hit. Auto-refresh paused for 4m 38s." action="Settings" />}
    >
      <EdgeMListStub />
    </EdgeMShell>
  );
}

// 4 · FEED GONE (410) — Feeds tab, dead-feed row + big state.
function EdgeFeedGoneM({ topInset = 14 }) {
  return (
    <EdgeMShell
      header={<EdgeMHeader title="Cold Take" subtitle="A. Mendez · 410 Gone for 14 days" topInset={topInset} />}
      tabActive="feeds"
    >
      <EdgeMBigState
        eyebrow="ERR · HTTP 410 GONE"
        title="“Cold Take” is gone."
        body="The publisher returned 410 every time we've checked for 14 days running. Cached articles are kept."
        primary="Unsubscribe"
        secondary="Keep watching"
      />
    </EdgeMShell>
  );
}

// 5 · FEED PARSE ERROR — snackbar over a stale per-feed list.
function EdgeFeedParseErrorM({ topInset = 14 }) {
  return (
    <EdgeMShell
      header={<EdgeMHeader title="Frequencies" subtitle="L. Hara · 5 articles · stale" topInset={topInset} />}
      tabActive="all"
      snackbar={<EdgeMSnackbar text="Couldn't parse Frequencies — showing last successful read." action="Details" />}
    >
      <EdgeMListStub articles={ARTICLES.filter(a => a.feed === 'frequencies').slice(0, 4)} />
    </EdgeMShell>
  );
}

// 5b · FEED PARSE ERROR · raw response inspector (mobile)
// Where the snackbar's "Details" action lands. Full-screen pushed view;
// tab bar hidden. Same anatomy as the web inspector — header / metadata /
// source / footer — stacked vertically and tightened for a 412dp frame.
function EdgeRawResponseM({ topInset = 14 }) {
  const ED_C = React.useContext(EdThemeContext);
  const rawLines = [
    '<!DOCTYPE html>',
    '<html lang="en">',
    '<head>',
    '  <meta charset="utf-8">',
    '  <title>Frequencies — brief',
    '         maintenance</title>',
    '</head>',
    '<body class="maintenance">',
    '  <main>',
    '    <h1>We\'re upgrading.</h1>',
    '    <p>The site is briefly',
    '       offline while we move',
    '       hosts.</p>',
    '  </main>',
    '</body>',
    '</html>',
  ];
  const errorLine = 1;
  const monoFont = '"SF Mono", "JetBrains Mono", ui-monospace, monospace';

  const metaRow = (label, value) => (
    <div style={{ display: 'flex', gap: 14, alignItems: 'baseline' }}>
      <div style={{
        flex: '0 0 64px', color: ED_C.ink3,
        fontSize: 9.5, letterSpacing: '.14em',
        textTransform: 'uppercase', fontWeight: 500,
      }}>{label}</div>
      <div style={{ flex: 1, color: ED_C.ink2, fontSize: 12, lineHeight: 1.5 }}>{value}</div>
    </div>
  );

  return (
    <EdgeMShell hideTabBar>
      <div style={{ position: 'absolute', inset: 0, background: ED_C.bg, display: 'flex', flexDirection: 'column', overflow: 'hidden' }}>
        {/* Top bar */}
        <div style={{
          paddingTop: topInset + 10, paddingLeft: 16, paddingRight: 16, paddingBottom: 10,
          borderBottom: `1px solid ${ED_C.border}`, background: ED_C.bg,
          display: 'flex', alignItems: 'center', gap: 8,
          fontFamily: edUiFont, flex: '0 0 auto',
        }}>
          <span style={{ color: ED_C.accent, fontSize: 14, padding: '4px 6px' }}>‹ Frequencies</span>
          <span style={{ flex: 1 }} />
          <button style={{
            all: 'unset', padding: '6px 10px',
            border: `1px solid ${ED_C.border}`, borderRadius: 4,
            background: ED_C.panel, fontSize: 12, color: ED_C.ink2,
          }}>Copy</button>
          <button style={{
            all: 'unset', padding: '6px 10px',
            border: `1px solid ${ED_C.border}`, borderRadius: 4,
            background: ED_C.panel, fontSize: 12, color: ED_C.ink2,
          }}>↗</button>
        </div>

        {/* Title row */}
        <div style={{
          padding: '14px 22px 6px',
          fontFamily: edSerifFont, fontSize: 22, fontWeight: 500,
          letterSpacing: '-.02em', color: ED_C.ink,
          flex: '0 0 auto',
        }}>Raw response</div>

        {/* Metadata strip */}
        <div style={{
          margin: '0 16px', padding: '14px 16px',
          background: ED_C.panel, border: `1px solid ${ED_C.border}`, borderRadius: 4,
          display: 'flex', flexDirection: 'column', gap: 10,
          flex: '0 0 auto', fontFamily: edUiFont,
        }}>
          {metaRow('URL', <span style={{ fontFamily: monoFont, color: ED_C.ink, wordBreak: 'break-all' }}>frequencies.fm/rss</span>)}
          {metaRow('Fetched', '6h ago · attempt 4/5')}
          {metaRow('Response', (
            <span>
              <span style={{ fontFamily: monoFont, color: ED_C.ink }}>200 OK</span>{' · 1.4 KB · '}
              <span style={{ color: EDGE_TOK_M.errFg, fontFamily: monoFont }}>text/html</span>
            </span>
          ))}
          {metaRow('Parser', (
            <span style={{ color: EDGE_TOK_M.errFg, lineHeight: 1.5 }}>
              <span style={{
                fontFamily: 'ui-monospace, monospace', fontSize: 9, letterSpacing: '.14em',
                textTransform: 'uppercase',
                padding: '1px 5px', border: `1px solid ${EDGE_TOK_M.errBd}`, borderRadius: 2,
                background: EDGE_TOK_M.errBg, marginRight: 6,
              }}>ERR</span>
              Got <span style={{ fontFamily: monoFont }}>{'<!DOCTYPE html>'}</span> at line 1.
              Expected <span style={{ fontFamily: monoFont }}>{'<?xml…?>'}</span>.
            </span>
          ))}
        </div>

        {/* Source label */}
        <div style={{
          padding: '14px 22px 4px', flex: '0 0 auto',
          fontSize: 10, letterSpacing: '.14em', textTransform: 'uppercase',
          fontWeight: 500, color: ED_C.ink3, fontFamily: edUiFont,
        }}>Source · 15 lines</div>

        {/* Source view */}
        <div style={{
          flex: 1, minHeight: 0, overflow: 'auto',
          margin: '0 16px 16px', border: `1px solid ${ED_C.border}`,
          background: ED_C.bg, borderRadius: 4,
          fontFamily: monoFont, fontSize: 11.5, lineHeight: 1.7,
        }}>
          <div style={{ padding: '10px 0' }}>
            {rawLines.map((line, i) => {
              const num = i + 1;
              const isErr = num === errorLine;
              return (
                <React.Fragment key={i}>
                  <div style={{
                    display: 'grid', gridTemplateColumns: '36px 1fr',
                    background: isErr ? EDGE_TOK_M.errBg : 'transparent',
                    borderLeft: isErr ? `2px solid ${EDGE_TOK_M.errFg}` : '2px solid transparent',
                  }}>
                    <span style={{
                      textAlign: 'right', paddingRight: 10,
                      color: isErr ? EDGE_TOK_M.errFg : ED_C.ink3,
                      fontVariantNumeric: 'tabular-nums', userSelect: 'none',
                      fontWeight: isErr ? 600 : 400,
                    }}>{num}</span>
                    <span style={{ paddingRight: 12, whiteSpace: 'pre', color: ED_C.ink }}>{line}</span>
                  </div>
                  {isErr ? (
                    /* Caret annotation sits directly under the error line. */
                    <div style={{
                      display: 'grid', gridTemplateColumns: '36px 1fr',
                      color: EDGE_TOK_M.errFg, fontSize: 10.5,
                      marginTop: 2, marginBottom: 4,
                    }}>
                      <span />
                      <span style={{ paddingRight: 12, whiteSpace: 'pre', fontFamily: monoFont, lineHeight: 1.2 }}>
                        {'^^^^^^^^^  HTML where XML was expected'}
                      </span>
                    </div>
                  ) : null}
                </React.Fragment>
              );
            })}
          </div>
        </div>
      </div>
    </EdgeMShell>
  );
}

// 6 · ARTICLE LINK-ROT — inline reader note, tab bar hidden.
function EdgeArticleLinkRotM({ topInset = 14 }) {
  const ED_C = React.useContext(EdThemeContext);
  const article = ARTICLES.find(a => a.id === 'a09');
  const note = (
    <div style={{
      display: 'flex', alignItems: 'flex-start', gap: 8,
      padding: '10px 12px', marginBottom: 22,
      background: EDGE_TOK_M.warnBg, border: `1px solid ${EDGE_TOK_M.warnBd}`,
      color: EDGE_TOK_M.warnFg,
      fontFamily: edUiFont, fontSize: 12, lineHeight: 1.5,
    }}>
      <span style={{
        fontFamily: 'ui-monospace, monospace', fontSize: 9.5, letterSpacing: '.14em',
        textTransform: 'uppercase',
        padding: '1px 5px', border: `1px solid ${EDGE_TOK_M.warnBd}`, borderRadius: 2,
        background: 'rgba(255,255,255,.5)', flex: '0 0 auto', marginTop: 1,
      }}>WARN</span>
      <span>
        The original page now returns <strong>404</strong>. Reading the cached copy from 30h ago.
      </span>
    </div>
  );
  return (
    <EdgeMShell hideTabBar>
      <EdgeMReaderStub article={article} topInset={topInset} note={note} />
    </EdgeMShell>
  );
}

// 7 · FIRST RUN — no feeds.
function EdgeFirstRunM({ topInset = 14 }) {
  return (
    <EdgeMShell
      header={<EdgeMHeader title="Welcome" subtitle="Nothing to sync yet" topInset={topInset} />}
      tabActive="unread"
    >
      <EdgeMBigState
        eyebrow="WELCOME"
        title="Start by adding a feed."
        body="Paste any blog URL and we'll find its feed for you — or import an OPML backup from another reader."
        primary="Paste a URL…"
        secondary="Import OPML…"
        hint="The feed is yours."
      />
    </EdgeMShell>
  );
}

// 8 · INBOX ZERO — caught up.
function EdgeInboxZeroM({ topInset = 14 }) {
  return (
    <EdgeMShell
      header={<EdgeMHeader title="Unread" subtitle="0 articles" topInset={topInset} />}
      tabActive="unread"
    >
      <EdgeMBigState
        eyebrow="INBOX ZERO"
        title="You're caught up."
        body="No unread articles across 7 feeds."
        secondary="Browse all articles"
      />
    </EdgeMShell>
  );
}

// 9 · NO SEARCH RESULTS — Feeds tab.
function EdgeNoSearchM({ topInset = 14 }) {
  return (
    <EdgeMShell tabActive="feeds">
      <EdgeMFeedsStub topInset={topInset} searchValue="matrix" />
    </EdgeMShell>
  );
}

// 10 · ADD FEED — not a feed (mobile uses the same search/paste row).
function EdgeAddInvalidM({ topInset = 14 }) {
  return (
    <EdgeMShell tabActive="feeds">
      <EdgeMFeedsStub
        topInset={topInset}
        searchValue="https://nytimes.com"
        addError={{
          kind: 'error',
          text: "This URL didn't return a valid feed. Paste the feed URL directly (e.g. /rss/feed.xml), not the homepage.",
        }}
      />
    </EdgeMShell>
  );
}

// 11 · ADD FEED — duplicate.
function EdgeAddDuplicateM({ topInset = 14 }) {
  return (
    <EdgeMShell tabActive="feeds">
      <EdgeMFeedsStub
        topInset={topInset}
        searchValue="https://theloop.cc/rss"
        addError={{
          kind: 'warn',
          text: "You're already subscribed to The Loop — it's in the Tech folder.",
        }}
      />
    </EdgeMShell>
  );
}

// 12 · SESSION EXPIRED — modal interrupt.
function EdgeSessionExpiredM({ topInset = 14 }) {
  const ED_C = React.useContext(EdThemeContext);
  return (
    <EdgeMShell
      header={<EdgeMHeader title="Unread" subtitle="24 articles" topInset={topInset} />}
      tabActive="unread"
      modal={
        <EdgeMModal
          eyebrow="SESSION EXPIRED"
          title="You've been signed out."
          body="Your session expired after 30 days of inactivity. Sign in to resume — nothing in your cache has been lost."
          detail={
            <React.Fragment>
              <span style={{ color: ED_C.ink3 }}>Signed in as</span>
              <span style={{ fontFamily: 'ui-monospace, monospace', fontSize: 12, color: ED_C.ink }}>admin@feed.app</span>
            </React.Fragment>
          }
          primary="Sign in again"
          secondary="Forget this device"
        />
      }
    >
      <EdgeMListStub />
    </EdgeMShell>
  );
}

Object.assign(window, {
  EdgeOfflineM, EdgeServerDownM, EdgeRateLimitedM,
  EdgeFeedGoneM, EdgeFeedParseErrorM, EdgeRawResponseM, EdgeArticleLinkRotM,
  EdgeFirstRunM, EdgeInboxZeroM, EdgeNoSearchM,
  EdgeAddInvalidM, EdgeAddDuplicateM, EdgeSessionExpiredM,
});

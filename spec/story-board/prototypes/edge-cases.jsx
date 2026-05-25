// Edge cases & error states (Paper palette, web).
// One static snapshot per scenario, dropped on the design canvas as
// independent artboards. Each composes the same atoms as the happy-path
// editorial prototype (sidebar shell, type, palette) and overrides only
// the parts that differ per case.
//
// Pattern menu — pick the right surface for the failure:
//   • Global banner (top of content area)      → app-wide condition, app still usable
//   • Big mid-pane state (replaces list+reader)→ app can't proceed without action
//   • Inline form error                        → bad input in a form
//   • Sidebar feed badge (!)                   → per-feed failure, scoped
//   • Modal overlay                            → session/auth interrupt

const EDGE_TOK = {
  warnBg: 'oklch(0.96 0.035 78)',
  warnFg: 'oklch(0.40 0.10 70)',
  warnBd: 'oklch(0.86 0.06 75)',
  errBg:  'oklch(0.965 0.025 25)',
  errFg:  'oklch(0.42 0.13 25)',
  errBd:  'oklch(0.86 0.07 25)',
};

// ── Top-of-content banner ──────────────────────────────────────────
function EdgeBanner({ kind = 'info', label, children, action }) {
  const ED_C = React.useContext(EdThemeContext);
  const m = {
    info:  { bg: ED_C.accentSoft,   fg: ED_C.accent,    bd: ED_C.border },
    warn:  { bg: EDGE_TOK.warnBg,   fg: EDGE_TOK.warnFg, bd: EDGE_TOK.warnBd },
    error: { bg: EDGE_TOK.errBg,    fg: EDGE_TOK.errFg,  bd: EDGE_TOK.errBd },
  }[kind];
  return (
    <div style={{
      padding: '9px 18px', background: m.bg, color: m.fg,
      borderBottom: `1px solid ${m.bd}`,
      fontFamily: edUiFont, fontSize: 12.5,
      display: 'flex', alignItems: 'center', gap: 12,
    }}>
      <span style={{
        fontFamily: 'ui-monospace, monospace', fontSize: 9.5, letterSpacing: '.16em',
        textTransform: 'uppercase', opacity: .9,
        padding: '2px 6px', border: `1px solid ${m.bd}`, borderRadius: 2,
        background: 'rgba(255,255,255,.45)',
      }}>{label || kind}</span>
      <div style={{ flex: 1, lineHeight: 1.4 }}>{children}</div>
      {action ? <div style={{ flex: '0 0 auto' }}>{action}</div> : null}
    </div>
  );
}

function EdgeBannerLink({ children, kind = 'info' }) {
  const ED_C = React.useContext(EdThemeContext);
  const c = kind === 'error' ? EDGE_TOK.errFg
          : kind === 'warn'  ? EDGE_TOK.warnFg
          : ED_C.accent;
  return <a href="#" style={{ color: c, textDecoration: 'underline', textUnderlineOffset: 2 }}>{children}</a>;
}

// ── Static snapshot sidebar ────────────────────────────────────────
// (Live EdSidebar is stateful and doesn't expose per-feed badges.)
function EdgeSidebar({
  active = 'unread',
  syncState = 'ok',     // ok | failed | offline | syncing | paused
  syncLabel,            // override the footer left text
  feedStatus = {},      // { feedId: 'dead' | 'error' | 'syncing' }
  feeds = FEEDS,
  unreadCount,
  highlightFeed = null,
}) {
  const ED_C = React.useContext(EdThemeContext);
  const folders = [...new Set(feeds.map(f => f.folder))];
  const totalUnread = unreadCount != null ? unreadCount : feeds.reduce((a, f) => a + (f.unread || 0), 0);

  const NavItem = ({ id, label, count }) => {
    const isActive = active === id;
    return (
      <div style={{
        display: 'flex', alignItems: 'center', justifyContent: 'space-between',
        padding: '6px 10px', borderRadius: 4,
        background: isActive ? ED_C.accentSoft : 'transparent',
        color: isActive ? ED_C.accent : ED_C.ink, fontSize: 13, fontWeight: 500,
      }}>
        <span>{label}</span>
        {count != null ? (
          <span style={{ fontSize: 11, color: ED_C.muted, fontVariantNumeric: 'tabular-nums' }}>{count}</span>
        ) : null}
      </div>
    );
  };

  return (
    <div style={{
      width: 220, flex: '0 0 220px', height: '100%',
      background: ED_C.panel, borderRight: `1px solid ${ED_C.border}`,
      display: 'flex', flexDirection: 'column', fontFamily: edUiFont, color: ED_C.ink,
    }}>
      <div style={{ padding: '20px 18px 14px', display: 'flex', alignItems: 'center', gap: 10 }}>
        <div style={{
          width: 22, height: 22, borderRadius: '50%', border: `1.5px solid ${ED_C.ink}`,
          display: 'flex', alignItems: 'center', justifyContent: 'center',
        }}>
          <div style={{ width: 6, height: 6, borderRadius: '50%', background: ED_C.accent }} />
        </div>
        <div style={{ fontFamily: edSerifFont, fontSize: 17, fontWeight: 500, letterSpacing: '-.01em' }}>Feed</div>
      </div>

      <div style={{ padding: '4px 10px', display: 'flex', flexDirection: 'column', gap: 1 }}>
        <NavItem id="unread"   label="Unread"        count={totalUnread} />
        <NavItem id="all"      label="All articles"  count={ARTICLES.length} />
        <NavItem id="subs"     label="Subscriptions" count={feeds.length} />
        <NavItem id="settings" label="Settings" />
      </div>

      <div style={{ height: 1, background: ED_C.border, margin: '14px 18px' }} />

      <div style={{ padding: '0 10px', flex: 1, overflow: 'hidden' }}>
        {folders.map(folder => (
          <div key={folder} style={{ marginBottom: 14 }}>
            <div style={{
              fontSize: 10, letterSpacing: '.1em', textTransform: 'uppercase',
              color: ED_C.ink3, padding: '4px 10px',
            }}>{folder}</div>
            {feeds.filter(f => f.folder === folder).map(f => {
              const status = feedStatus[f.id];
              const dead = status === 'dead';
              const err  = status === 'error';
              const isHi = highlightFeed === f.id;
              return (
                <div key={f.id} style={{
                  display: 'flex', alignItems: 'center', justifyContent: 'space-between',
                  padding: '5px 10px', borderRadius: 4, fontSize: 12.5,
                  background: isHi ? ED_C.accentSoft : 'transparent',
                  color: isHi ? ED_C.accent : ED_C.ink2,
                  opacity: dead ? .55 : 1,
                }}>
                  <span style={{ display: 'flex', alignItems: 'center', gap: 8, overflow: 'hidden', minWidth: 0 }}>
                    <span style={{
                      width: 6, height: 6, borderRadius: '50%',
                      background: `oklch(0.65 0.12 ${f.hue})`, flex: '0 0 auto',
                    }} />
                    <span style={{
                      overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
                      textDecoration: dead ? 'line-through' : 'none',
                    }}>{f.name}</span>
                    {(err || dead) ? (
                      <span style={{
                        flex: '0 0 auto', fontFamily: 'ui-monospace, monospace',
                        fontSize: 10, color: EDGE_TOK.errFg, fontWeight: 600,
                        padding: '0 4px', border: `1px solid ${EDGE_TOK.errBd}`,
                        borderRadius: 2, background: EDGE_TOK.errBg,
                      }}>!</span>
                    ) : null}
                  </span>
                  {!dead && f.unread > 0 ? (
                    <span style={{
                      fontSize: 10.5, color: ED_C.muted, fontVariantNumeric: 'tabular-nums',
                    }}>{f.unread}</span>
                  ) : null}
                </div>
              );
            })}
          </div>
        ))}
      </div>

      <div style={{
        padding: '12px 18px', borderTop: `1px solid ${ED_C.border}`,
        fontSize: 11, display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 8,
      }}>
        {syncState === 'offline' ? (
          <React.Fragment>
            <span style={{ color: ED_C.ink2 }}>{syncLabel || 'Offline · cache only'}</span>
            <span style={{ color: ED_C.ink3 }}>○</span>
          </React.Fragment>
        ) : syncState === 'failed' ? (
          <React.Fragment>
            <span style={{ color: ED_C.ink2 }}>
              {syncLabel || 'Sync failed'} · <a href="#" style={{ color: ED_C.accent, textDecoration: 'underline', textUnderlineOffset: 2 }}>retry</a>
            </span>
            <span style={{ color: EDGE_TOK.errFg }}>!</span>
          </React.Fragment>
        ) : syncState === 'paused' ? (
          <React.Fragment>
            <span style={{ color: ED_C.ink2 }}>{syncLabel || 'Sync paused'}</span>
            <span style={{ color: EDGE_TOK.warnFg }}>‖</span>
          </React.Fragment>
        ) : syncState === 'syncing' ? (
          <React.Fragment>
            <span style={{ color: ED_C.ink3 }}>{syncLabel || 'Syncing…'}</span>
            <span style={{ color: ED_C.ink3 }}>↻</span>
          </React.Fragment>
        ) : (
          <React.Fragment>
            <span style={{ color: ED_C.ink3 }}>{syncLabel || 'Synced 2m ago'}</span>
            <span style={{ color: ED_C.ink3 }}>↻</span>
          </React.Fragment>
        )}
      </div>
    </div>
  );
}

// ── Big mid-pane state (replaces list + reader) ───────────────────
function EdgeBigState({ eyebrow, title, body, mono, primary, secondary, hint }) {
  const ED_C = React.useContext(EdThemeContext);
  return (
    <div style={{
      flex: 1, height: '100%', background: ED_C.bg, color: ED_C.ink,
      display: 'flex', alignItems: 'center', justifyContent: 'center', padding: 40,
      overflow: 'hidden',
    }}>
      <div style={{ maxWidth: 460, textAlign: 'center' }}>
        {eyebrow ? (
          <div style={{
            fontFamily: 'ui-monospace, monospace', fontSize: 10.5, letterSpacing: '.14em',
            textTransform: 'uppercase', color: ED_C.ink3, marginBottom: 16,
          }}>{eyebrow}</div>
        ) : null}
        <div style={{
          fontFamily: edSerifFont, fontSize: 28, fontWeight: 500,
          letterSpacing: '-.02em', lineHeight: 1.15, marginBottom: 12,
        }}>{title}</div>
        <div style={{
          fontFamily: edSerifFont, fontStyle: 'italic', fontSize: 15.5,
          color: ED_C.ink2, lineHeight: 1.55, marginBottom: mono ? 18 : 26, textWrap: 'pretty',
        }}>{body}</div>
        {mono ? (
          <div style={{
            fontFamily: 'ui-monospace, monospace', fontSize: 11, color: ED_C.ink2,
            padding: '10px 14px', border: `1px solid ${ED_C.border}`,
            background: ED_C.panel, borderRadius: 3, marginBottom: 26,
            textAlign: 'left', whiteSpace: 'pre-wrap', lineHeight: 1.55,
          }}>{mono}</div>
        ) : null}
        <div style={{ display: 'inline-flex', gap: 8, flexWrap: 'wrap', justifyContent: 'center' }}>
          {primary ? (
            <button style={{
              all: 'unset', cursor: 'pointer', padding: '10px 18px', borderRadius: 4,
              background: ED_C.ink, color: ED_C.panel, fontSize: 12.5, fontFamily: edUiFont,
            }}>{primary}</button>
          ) : null}
          {secondary ? (
            <button style={edIconBtnStyle(ED_C)}>{secondary}</button>
          ) : null}
        </div>
        {hint ? (
          <div style={{
            marginTop: 22, fontSize: 11.5, color: ED_C.ink3, fontFamily: edUiFont,
            textWrap: 'pretty',
          }}>{hint}</div>
        ) : null}
      </div>
    </div>
  );
}

// ── Compact list-pane stub (used when banner sits on top of normal content) ─
function EdgeListStub({ title, subtitle, articles = ARTICLES.slice(0, 5), selectedId = 'a01' }) {
  const ED_C = React.useContext(EdThemeContext);
  return (
    <div style={{
      width: 380, flex: '0 0 380px', height: '100%', overflow: 'hidden',
      borderRight: `1px solid ${ED_C.border}`, background: ED_C.bg,
      fontFamily: edUiFont, color: ED_C.ink,
    }}>
      <div style={{ padding: '22px 22px 14px', borderBottom: `1px solid ${ED_C.border}` }}>
        <div style={{ fontFamily: edSerifFont, fontSize: 22, fontWeight: 500, letterSpacing: '-.015em' }}>{title}</div>
        <div style={{ fontSize: 12, color: ED_C.ink3, marginTop: 4 }}>{subtitle}</div>
      </div>
      <div>
        {articles.map((a, i) => {
          const feed = FEED_BY_ID[a.feed];
          const sel = a.id === selectedId;
          return (
            <div key={a.id} style={{
              padding: '14px 20px',
              borderBottom: i < articles.length - 1 ? `1px solid ${ED_C.border}` : 'none',
              background: sel ? ED_C.panel : 'transparent',
              position: 'relative', display: 'flex', flexDirection: 'column', gap: 6,
            }}>
              {sel ? <div style={{ position: 'absolute', left: 0, top: 0, bottom: 0, width: 2, background: ED_C.accent }} /> : null}
              <div style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: 11, color: ED_C.ink3 }}>
                <span style={{ width: 6, height: 6, borderRadius: '50%', background: `oklch(0.65 0.12 ${feed.hue})` }} />
                <span style={{ fontWeight: 500, color: ED_C.ink2 }}>{feed.name}</span>
                <span>·</span>
                <span>{edTimeAgo(a.ts)}</span>
                <span style={{ flex: 1 }} />
                {a.unread ? <span style={{ width: 6, height: 6, borderRadius: '50%', background: ED_C.accent }} /> : null}
              </div>
              <div style={{
                fontFamily: edSerifFont, fontSize: 17, lineHeight: 1.25,
                fontWeight: 500, color: ED_C.ink, letterSpacing: '-.01em',
              }}>{a.title}</div>
              <div style={{
                fontSize: 12, color: ED_C.ink2, lineHeight: 1.4,
                display: '-webkit-box', WebkitLineClamp: 2, WebkitBoxOrient: 'vertical', overflow: 'hidden',
              }}>{a.excerpt}</div>
              <div style={{ fontSize: 10.5, color: ED_C.ink3, marginTop: 2 }}>
                {a.minutes} min read
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
}

// ── Reader stub (used when banner sits atop a working reader) ─────
function EdgeReaderStub({ article = ARTICLES[0], note = null }) {
  const ED_C = React.useContext(EdThemeContext);
  const feed = FEED_BY_ID[article.feed];
  return (
    <div style={{ flex: 1, height: '100%', overflow: 'hidden', background: ED_C.bg, position: 'relative' }}>
      <div style={{ maxWidth: 620, margin: '0 auto', padding: '52px 48px 80px', fontFamily: edSerifFont, color: ED_C.ink }}>
        <div style={{
          fontFamily: edUiFont, fontSize: 11.5, letterSpacing: '.06em', textTransform: 'uppercase',
          color: ED_C.ink3, display: 'flex', alignItems: 'center', gap: 10, marginBottom: 24,
        }}>
          <span style={{ width: 6, height: 6, borderRadius: '50%', background: `oklch(0.65 0.12 ${feed.hue})` }} />
          <span style={{ color: ED_C.ink2 }}>{feed.name}</span>
          <span>·</span>
          <span>{feed.author}</span>
          <span style={{ marginLeft: 'auto', textTransform: 'none', letterSpacing: 0, color: ED_C.ink3 }}>
            {edTimeAgo(article.ts)} ago · {article.minutes} min read
          </span>
        </div>
        <h1 style={{ fontSize: 36, fontWeight: 500, lineHeight: 1.12, letterSpacing: '-.02em', margin: '0 0 14px' }}>
          {article.title}
        </h1>
        <div style={{ fontStyle: 'italic', fontSize: 18, lineHeight: 1.45, color: ED_C.ink2, marginBottom: 28 }}>
          {article.excerpt}
        </div>
        {note}
        <div style={{ fontSize: 17, lineHeight: 1.65, color: ED_C.ink }}>
          <p style={{ margin: '0 0 1.1em', textWrap: 'pretty' }}>{ARTICLE_BODY[0]}</p>
          <p style={{ margin: '0 0 1.1em', textWrap: 'pretty' }}>{ARTICLE_BODY[1]}</p>
        </div>
      </div>
    </div>
  );
}

// ── Subscriptions stub (used for Add-feed errors + no-search-results) ─
function EdgeSubsStub({
  searchValue = '',
  addOpen = false,
  addUrl = '',
  addError = null,        // { kind: 'error'|'warn', text }
  feeds = FEEDS,
  emptyText = null,
}) {
  const ED_C = React.useContext(EdThemeContext);
  const rows = feeds.filter(f => f.name.toLowerCase().includes(searchValue.trim().toLowerCase()));
  return (
    <div style={{ flex: 1, height: '100%', overflow: 'hidden', background: ED_C.bg, fontFamily: edUiFont, color: ED_C.ink }}>
      <div style={{ maxWidth: 720, margin: '0 auto', padding: '48px 40px 60px' }}>
        <div style={{ display: 'flex', alignItems: 'baseline', justifyContent: 'space-between', marginBottom: 28 }}>
          <h1 style={{ fontFamily: edSerifFont, fontSize: 28, fontWeight: 500, letterSpacing: '-.02em', margin: 0 }}>Subscriptions</h1>
          <button style={{
            ...edIconBtnStyle(ED_C),
            background: addOpen ? ED_C.panel : ED_C.accent,
            color: addOpen ? ED_C.ink2 : ED_C.onAccent,
            border: addOpen ? `1px solid ${ED_C.border}` : 'none', padding: '8px 14px',
          }}>{addOpen ? 'Cancel' : '+ Add feed'}</button>
        </div>

        {addOpen ? (
          <div style={{ marginBottom: 16 }}>
            <div style={{
              display: 'flex', gap: 8, padding: '12px 14px',
              border: `1px solid ${addError ? EDGE_TOK.errBd : ED_C.borderStrong}`,
              borderRadius: 4, background: ED_C.panel,
            }}>
              <input defaultValue={addUrl}
                style={{ all: 'unset', flex: 1, fontSize: 13, color: ED_C.ink, fontFamily: edUiFont }} />
              <button style={{ ...edIconBtnStyle(ED_C), background: ED_C.ink, color: ED_C.panel, border: 'none' }}>
                Subscribe
              </button>
            </div>
            {addError ? (
              <div style={{
                display: 'flex', alignItems: 'flex-start', gap: 8, marginTop: 8,
                fontSize: 12, color: addError.kind === 'warn' ? EDGE_TOK.warnFg : EDGE_TOK.errFg,
                fontFamily: edUiFont, lineHeight: 1.45,
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
        ) : null}

        <div style={{
          display: 'flex', alignItems: 'center', gap: 8, padding: '10px 14px',
          border: `1px solid ${ED_C.border}`, borderRadius: 4, background: ED_C.panel, marginBottom: 24,
        }}>
          <span style={{ color: ED_C.ink3 }}>⌕</span>
          <input defaultValue={searchValue} placeholder="Search subscriptions…"
            style={{ all: 'unset', flex: 1, fontSize: 13, color: ED_C.ink }} />
          <span style={{ fontSize: 11, color: ED_C.ink3 }}>{rows.length} of {feeds.length}</span>
        </div>

        {rows.length === 0 ? (
          <div style={{
            padding: '60px 20px', textAlign: 'center',
            fontFamily: edSerifFont, fontStyle: 'italic',
            fontSize: 16, color: ED_C.ink3, lineHeight: 1.5,
          }}>
            {emptyText || `No subscriptions match “${searchValue}”.`}
          </div>
        ) : (
          <div style={{ display: 'flex', flexDirection: 'column' }}>
            {rows.slice(0, 5).map((f, i) => (
              <div key={f.id} style={{
                display: 'flex', alignItems: 'center', gap: 14, padding: '14px 0',
                borderBottom: i < Math.min(4, rows.length - 1) ? `1px solid ${ED_C.border}` : 'none',
              }}>
                <div style={{
                  width: 36, height: 36, borderRadius: 4, flex: '0 0 auto',
                  background: `oklch(0.85 0.05 ${f.hue})`, color: `oklch(0.35 0.08 ${f.hue})`,
                  display: 'flex', alignItems: 'center', justifyContent: 'center',
                  fontFamily: edSerifFont, fontWeight: 500, fontSize: 16,
                }}>{f.name[0]}</div>
                <div style={{ flex: 1, minWidth: 0 }}>
                  <div style={{ fontFamily: edSerifFont, fontSize: 16, fontWeight: 500 }}>{f.name}</div>
                  <div style={{ fontSize: 11.5, color: ED_C.ink3, marginTop: 2 }}>{f.url}</div>
                </div>
                <div style={{ fontSize: 11, color: ED_C.ink3, width: 74, textAlign: 'right' }}>{f.folder}</div>
                <div style={{ fontSize: 11, color: ED_C.ink3, width: 60, textAlign: 'right', fontVariantNumeric: 'tabular-nums' }}>
                  {f.unread} new
                </div>
                <button style={{ all: 'unset', cursor: 'default', color: ED_C.ink3, padding: '4px 8px' }}>⋯</button>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}

// ── Modal overlay (for session-expired) ────────────────────────────
function EdgeModal({ eyebrow, title, body, primary, secondary, children }) {
  const ED_C = React.useContext(EdThemeContext);
  return (
    <div style={{
      position: 'absolute', inset: 0, zIndex: 50,
      background: 'rgba(20,25,40,.32)', backdropFilter: 'blur(2px)',
      display: 'flex', alignItems: 'center', justifyContent: 'center',
    }}>
      <div style={{
        background: ED_C.bg, border: `1px solid ${ED_C.borderStrong}`,
        boxShadow: '0 24px 60px rgba(0,0,0,.18)',
        width: 420, padding: '32px 32px 28px', fontFamily: edUiFont,
        color: ED_C.ink, textAlign: 'left',
      }}>
        {eyebrow ? (
          <div style={{
            fontFamily: 'ui-monospace, monospace', fontSize: 10.5, letterSpacing: '.14em',
            textTransform: 'uppercase', color: EDGE_TOK.warnFg, marginBottom: 14,
          }}>{eyebrow}</div>
        ) : null}
        <div style={{
          fontFamily: edSerifFont, fontSize: 24, fontWeight: 500, letterSpacing: '-.02em',
          lineHeight: 1.2, marginBottom: 10,
        }}>{title}</div>
        <div style={{
          fontFamily: edSerifFont, fontStyle: 'italic', fontSize: 14.5,
          color: ED_C.ink2, lineHeight: 1.5, marginBottom: 22, textWrap: 'pretty',
        }}>{body}</div>
        {children}
        <div style={{ display: 'flex', gap: 8, marginTop: 20 }}>
          {primary ? (
            <button style={{
              all: 'unset', cursor: 'pointer', padding: '10px 18px', borderRadius: 4,
              background: ED_C.ink, color: ED_C.panel, fontSize: 12.5, fontFamily: edUiFont,
            }}>{primary}</button>
          ) : null}
          {secondary ? <button style={edIconBtnStyle(ED_C)}>{secondary}</button> : null}
        </div>
      </div>
    </div>
  );
}

// ─── Top-level shell ────────────────────────────────────────────────
function EdgeShell({ sidebar, banner, children, modal }) {
  const ED_C = React.useContext(EdThemeContext);
  return (
    <EdThemeContext.Provider value={ED_C}>
      <div style={{
        width: '100%', height: '100%', display: 'flex',
        background: ED_C.bg, fontFamily: edUiFont, position: 'relative',
      }}>
        {sidebar}
        <div style={{ flex: 1, display: 'flex', flexDirection: 'column', minWidth: 0 }}>
          {banner}
          <div style={{ flex: 1, display: 'flex', minHeight: 0 }}>
            {children}
          </div>
        </div>
        {modal}
      </div>
    </EdThemeContext.Provider>
  );
}

// ════════════════════════════════════════════════════════════════════
// SCENARIOS
// ════════════════════════════════════════════════════════════════════

// 1 · OFFLINE — global condition, app still functional from cache.
function EdgeOffline() {
  return (
    <EdgeShell
      sidebar={<EdgeSidebar syncState="offline" syncLabel="Offline · cache only" />}
      banner={
        <EdgeBanner kind="warn" label="OFFLINE">
          You're offline. Showing <strong>24 cached articles</strong> from your last sync at 09:42.
          New items and read-state changes will sync when you reconnect.{' '}
          <EdgeBannerLink kind="warn">Try again now</EdgeBannerLink>
        </EdgeBanner>
      }
    >
      <EdgeListStub title="Unread" subtitle="24 unread · cached" />
      <EdgeReaderStub />
    </EdgeShell>
  );
}

// 2 · SERVER UNREACHABLE — app can't proceed, show full-pane.
function EdgeServerDown() {
  return (
    <EdgeShell
      sidebar={<EdgeSidebar syncState="failed" syncLabel="Last sync failed" />}
    >
      <EdgeBigState
        eyebrow="ERR · CONN_REFUSED"
        title="Couldn't reach the server."
        body="We've tried 3 times in the last minute. Your local cache is intact — we'll resume the moment the server comes back."
        mono={`GET https://feed.app/sync
ECONNREFUSED · attempt 3 of 5
next retry in 28s`}
        primary="Retry now"
        secondary="Check service status ↗"
        hint="If this keeps happening, the server may be down for maintenance."
      />
    </EdgeShell>
  );
}

// 3 · RATE-LIMITED — soft block, banner + paused state.
function EdgeRateLimited() {
  return (
    <EdgeShell
      sidebar={<EdgeSidebar syncState="paused" syncLabel="Paused · 4m 38s" />}
      banner={
        <EdgeBanner kind="warn" label="RATE LIMIT"
          action={<EdgeBannerLink kind="warn">Open Sync settings</EdgeBannerLink>}>
          You hit the per-hour sync limit. Auto-refresh is paused for <strong>4m 38s</strong>.
          Reading and marking articles still works.
        </EdgeBanner>
      }
    >
      <EdgeListStub title="Unread" subtitle="24 unread · refresh paused" />
      <EdgeReaderStub />
    </EdgeShell>
  );
}

// 4 · FEED GONE (410) — per-feed, dead-state.
function EdgeFeedGone() {
  const ED_C = React.useContext(EdThemeContext);
  return (
    <EdgeShell
      sidebar={<EdgeSidebar
        active=""
        highlightFeed="coldtake"
        feedStatus={{ coldtake: 'dead', frequencies: 'error' }}
      />}
    >
      <EdgeBigState
        eyebrow="ERR · HTTP 410 GONE"
        title="“Cold Take” is gone."
        body="The publisher returned “410 Gone” every time we've checked for 14 days running. The 23 articles you've already read are kept in your cache."
        mono={`coldtake.blog/feed.xml
410 Gone · since 06 May 2026
14 consecutive failures`}
        primary="Unsubscribe"
        secondary="Keep watching"
        hint="If they move to a new URL, you can add it from Subscriptions → + Add feed."
      />
    </EdgeShell>
  );
}

// 5 · FEED PARSE ERROR — per-feed, banner + stale list.
function EdgeFeedParseError() {
  return (
    <EdgeShell
      sidebar={<EdgeSidebar
        active=""
        highlightFeed="frequencies"
        feedStatus={{ frequencies: 'error' }}
      />}
      banner={
        <EdgeBanner kind="error" label="PARSE FAIL"
          action={<EdgeBannerLink kind="error">View raw response ↗</EdgeBannerLink>}>
          We can't read the feed XML for <strong>Frequencies</strong>. Showing the
          last successful read from <strong>6h ago</strong>. New items aren't appearing.
        </EdgeBanner>
      }
    >
      <EdgeListStub
        title="Frequencies"
        subtitle="L. Hara · 5 articles · stale"
        articles={ARTICLES.filter(a => a.feed === 'frequencies').slice(0, 5)}
        selectedId="a06"
      />
      <EdgeReaderStub article={ARTICLES.find(a => a.id === 'a06')} />
    </EdgeShell>
  );
}

// 5b · FEED PARSE ERROR · raw response inspector
// Where the "View raw response ↗" link on the parse-error banner lands.
// Surface map: full-pane inspector inside the editorial shell — sidebar
// stays visible so the user keeps app context. Pattern is the same one
// browser devtools use (top bar / metadata strip / source view with line
// numbers + error highlight / detail strip).
function EdgeRawResponse() {
  const ED_C = React.useContext(EdThemeContext);
  // The most plausible parse failure isn't malformed XML — it's the server
  // returning HTML where we expected RSS (maintenance page, login wall,
  // CDN error). Mocking that path; the error highlight sits on line 1.
  const rawLines = [
    '<!DOCTYPE html>',
    '<html lang="en">',
    '<head>',
    '  <meta charset="utf-8">',
    '  <title>Frequencies \u2014 brief maintenance</title>',
    '</head>',
    '<body class="maintenance">',
    '  <main>',
    '    <h1>We\'re upgrading the server.</h1>',
    '    <p>The site is briefly offline while we move hosts.',
    '       Subscriptions and articles will return shortly.</p>',
    '    <p>\u2014 L. Hara</p>',
    '  </main>',
    '</body>',
    '</html>',
  ];
  const errorLine = 1;

  const monoFont = '"SF Mono", "JetBrains Mono", ui-monospace, monospace';
  const metaRow = (label, value) => (
    <React.Fragment>
      <div style={{
        color: ED_C.ink3, fontSize: 10, letterSpacing: '.14em',
        textTransform: 'uppercase', fontWeight: 500,
        alignSelf: 'baseline', paddingTop: 3,
      }}>{label}</div>
      <div style={{ color: ED_C.ink2, fontSize: 12.5, lineHeight: 1.5 }}>{value}</div>
    </React.Fragment>
  );

  return (
    <EdgeShell
      sidebar={<EdgeSidebar
        active=""
        highlightFeed="frequencies"
        feedStatus={{ frequencies: 'error' }}
      />}
    >
      <div style={{
        flex: 1, height: '100%', overflow: 'hidden',
        background: ED_C.bg, fontFamily: edUiFont, color: ED_C.ink,
        display: 'flex', flexDirection: 'column',
      }}>
        {/* Top bar — back link + actions */}
        <div style={{
          display: 'flex', alignItems: 'center', gap: 14,
          padding: '12px 22px', borderBottom: `1px solid ${ED_C.border}`,
          background: ED_C.bg, flex: '0 0 auto',
        }}>
          <a href="#" style={{
            color: ED_C.accent, fontSize: 13, textDecoration: 'none',
            display: 'inline-flex', alignItems: 'center', gap: 4,
          }}>‹ Frequencies</a>
          <span style={{ color: ED_C.ink3 }}>·</span>
          <span style={{ fontSize: 13, color: ED_C.ink, fontWeight: 500 }}>Raw response</span>
          <span style={{ flex: 1 }} />
          <button style={edIconBtnStyle(ED_C)}>Copy</button>
          <a href="#" style={{ ...edIconBtnStyle(ED_C), textDecoration: 'none' }}>↗ Open URL</a>
        </div>

        {/* Metadata strip — what we fetched, what we got, where parsing died */}
        <div style={{
          padding: '14px 22px', borderBottom: `1px solid ${ED_C.border}`,
          background: ED_C.panel, flex: '0 0 auto',
          display: 'grid', gridTemplateColumns: 'auto 1fr', columnGap: 22, rowGap: 8,
        }}>
          {metaRow('URL', <span style={{ fontFamily: monoFont, color: ED_C.ink }}>https://frequencies.fm/rss</span>)}
          {metaRow('Fetched', '6h ago · Sat 24 May 2026, 02:14 UTC · attempt 4 of 5')}
          {metaRow('Response', (
            <span>
              <span style={{ fontFamily: monoFont, color: ED_C.ink }}>200 OK</span>
              {' · 1.4 KB · '}
              <span style={{ color: EDGE_TOK.errFg, fontFamily: monoFont }}>text/html</span>
              {' (expected '}
              <span style={{ fontFamily: monoFont }}>application/rss+xml</span>
              {')'}
            </span>
          ))}
          {metaRow('Parser', (
            <span style={{ color: EDGE_TOK.errFg }}>
              <span style={{
                fontFamily: 'ui-monospace, monospace', fontSize: 9.5, letterSpacing: '.14em',
                textTransform: 'uppercase',
                padding: '1px 5px', border: `1px solid ${EDGE_TOK.errBd}`, borderRadius: 2,
                background: EDGE_TOK.errBg, marginRight: 8,
              }}>ERR</span>
              Expected{' '}
              <span style={{ fontFamily: monoFont }}>{'<?xml … ?>'}</span>
              {' at start of stream, got '}
              <span style={{ fontFamily: monoFont }}>{'<!DOCTYPE html>'}</span>
              {' at line 1, col 1.'}
            </span>
          ))}
        </div>

        {/* Source view — line numbers + error highlight on errorLine */}
        <div style={{
          flex: 1, minHeight: 0, overflow: 'auto', background: ED_C.bg,
          fontFamily: monoFont, fontSize: 12.5, lineHeight: 1.7,
        }}>
          <div style={{ padding: '14px 0' }}>
            {rawLines.map((line, i) => {
              const num = i + 1;
              const isErr = num === errorLine;
              return (
                <React.Fragment key={i}>
                  <div style={{
                    display: 'grid', gridTemplateColumns: '56px 1fr',
                    background: isErr ? EDGE_TOK.errBg : 'transparent',
                    borderLeft: isErr ? `2px solid ${EDGE_TOK.errFg}` : '2px solid transparent',
                    color: isErr ? ED_C.ink : ED_C.ink,
                  }}>
                    <span style={{
                      textAlign: 'right', paddingRight: 18,
                      color: isErr ? EDGE_TOK.errFg : ED_C.ink3,
                      fontVariantNumeric: 'tabular-nums', userSelect: 'none',
                      fontWeight: isErr ? 600 : 400,
                    }}>{num}</span>
                    <span style={{ paddingRight: 24, whiteSpace: 'pre' }}>{line}</span>
                  </div>
                  {isErr ? (
                    /* Caret annotation sits directly under the error line. */
                    <div style={{
                      display: 'grid', gridTemplateColumns: '56px 1fr',
                      color: EDGE_TOK.errFg, fontSize: 11,
                      marginTop: 2, marginBottom: 6,
                    }}>
                      <span />
                      <span style={{ paddingRight: 24, whiteSpace: 'pre', fontFamily: monoFont, lineHeight: 1.2 }}>
                        {'^^^^^^^^^^^^^^^  HTML doctype where XML declaration was expected'}
                      </span>
                    </div>
                  ) : null}
                </React.Fragment>
              );
            })}
          </div>
        </div>

        {/* Detail footer — what the user can do about it */}
        <div style={{
          padding: '12px 22px', borderTop: `1px solid ${ED_C.border}`,
          background: ED_C.panel, flex: '0 0 auto',
          fontSize: 12, color: ED_C.ink3, fontFamily: edUiFont,
          display: 'flex', alignItems: 'center', gap: 12,
        }}>
          <span style={{ flex: 1, lineHeight: 1.5 }}>
            Cached articles still display in the feed. We'll retry every <strong style={{ color: ED_C.ink2 }}>6h</strong>;
            after 14 consecutive failures the feed will be marked <em>Gone</em>.
          </span>
          <a href="#" style={{ color: ED_C.accent, fontSize: 12, textDecoration: 'underline', textUnderlineOffset: 2 }}>
            Retry now
          </a>
        </div>
      </div>
    </EdgeShell>
  );
}

// 6 · ARTICLE LINK ROT — reader has cached body, source URL is 404.
function EdgeArticleLinkRot() {
  const ED_C = React.useContext(EdThemeContext);
  const article = ARTICLES.find(a => a.id === 'a09');  // atlas / cartographer
  const note = (
    <div style={{
      display: 'flex', alignItems: 'flex-start', gap: 10,
      padding: '12px 14px', marginBottom: 28,
      background: EDGE_TOK.warnBg, border: `1px solid ${EDGE_TOK.warnBd}`,
      color: EDGE_TOK.warnFg,
      fontFamily: edUiFont, fontSize: 12.5, lineHeight: 1.5,
    }}>
      <span style={{
        fontFamily: 'ui-monospace, monospace', fontSize: 9.5, letterSpacing: '.14em',
        textTransform: 'uppercase',
        padding: '2px 6px', border: `1px solid ${EDGE_TOK.warnBd}`, borderRadius: 2,
        background: 'rgba(255,255,255,.5)', flex: '0 0 auto',
      }}>WARN</span>
      <span>
        The original page at <code>atlasessays.org/cartographer</code> now returns
        <strong> 404</strong>. You're reading the cached copy from <strong>30h ago</strong>.
        <a href="#" style={{ marginLeft: 6, color: EDGE_TOK.warnFg, textDecoration: 'underline', textUnderlineOffset: 2 }}>
          Try Wayback ↗
        </a>
      </span>
    </div>
  );
  return (
    <EdgeShell
      sidebar={<EdgeSidebar active="" highlightFeed="atlas" />}
    >
      <EdgeListStub
        title="Atlas"
        subtitle="Various · 3 articles"
        articles={ARTICLES.filter(a => a.feed === 'atlas')}
        selectedId="a09"
      />
      <EdgeReaderStub article={article} note={note} />
    </EdgeShell>
  );
}

// 7 · FIRST RUN — no feeds. Onboarding mid-pane.
function EdgeFirstRun() {
  const ED_C = React.useContext(EdThemeContext);
  return (
    <EdgeShell
      sidebar={<EdgeSidebar
        feeds={[]}
        unreadCount={0}
        syncState="ok"
        syncLabel="Nothing to sync yet"
      />}
    >
      <EdgeBigState
        eyebrow="WELCOME"
        title="Start by adding a feed."
        body="Paste any blog URL and we'll find its RSS/Atom feed for you — or import an OPML backup if you're moving over from another reader."
        primary="Paste a URL…"
        secondary="Import OPML…"
        hint="No starter pack. No suggestions. The feed is yours."
      />
    </EdgeShell>
  );
}

// 8 · INBOX ZERO — all caught up.
function EdgeInboxZero() {
  const ED_C = React.useContext(EdThemeContext);
  return (
    <EdgeShell
      sidebar={<EdgeSidebar unreadCount={0} />}
    >
      <EdgeBigState
        eyebrow="INBOX ZERO"
        title="You're caught up."
        body="No unread articles across 7 feeds."
        secondary="Browse all articles"
      />
    </EdgeShell>
  );
}

// 9 · NO SEARCH RESULTS — subscriptions list, empty filter.
function EdgeNoSearch() {
  return (
    <EdgeShell
      sidebar={<EdgeSidebar active="subs" />}
    >
      <EdgeSubsStub searchValue="matrix" />
    </EdgeShell>
  );
}

// 10 · ADD FEED — URL is not a feed.
function EdgeAddInvalid() {
  return (
    <EdgeShell
      sidebar={<EdgeSidebar active="subs" />}
    >
      <EdgeSubsStub
        addOpen
        addUrl="https://nytimes.com"
        addError={{ kind: 'error', text: "This URL didn't return a valid feed. Paste the feed URL directly (e.g. nytimes.com/rss/feed.xml), not the site's homepage." }}
      />
    </EdgeShell>
  );
}

// 11 · ADD FEED — duplicate.
function EdgeAddDuplicate() {
  return (
    <EdgeShell
      sidebar={<EdgeSidebar active="subs" />}
    >
      <EdgeSubsStub
        addOpen
        addUrl="https://theloop.cc/rss"
        addError={{ kind: 'warn', text: "You're already subscribed to The Loop — it's in the Tech folder. Open it instead, or change the URL above." }}
      />
    </EdgeShell>
  );
}

// 12 · SESSION EXPIRED — modal interrupt.
function EdgeSessionExpired() {
  const ED_C = React.useContext(EdThemeContext);
  return (
    <EdgeShell
      sidebar={<EdgeSidebar syncState="failed" syncLabel="Signed out" />}
      modal={
        <EdgeModal
          eyebrow="SESSION EXPIRED"
          title="You've been signed out."
          body="Your session expired after 30 days of inactivity. Sign in again to resume syncing — nothing in your cache has been lost."
          primary="Sign in again"
          secondary="Forget this device"
        >
          <div style={{
            padding: '10px 14px',
            background: ED_C.panel, border: `1px solid ${ED_C.border}`,
            borderRadius: 3,
            fontFamily: edUiFont, fontSize: 12, color: ED_C.ink2,
            display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 12,
          }}>
            <span style={{ color: ED_C.ink3 }}>Signed in as</span>
            <span style={{ fontFamily: 'ui-monospace, monospace', fontSize: 12, color: ED_C.ink }}>admin@feed.app</span>
          </div>
        </EdgeModal>
      }
    >
      <EdgeListStub title="Unread" subtitle="24 unread" />
      <EdgeReaderStub />
    </EdgeShell>
  );
}

Object.assign(window, {
  EdgeOffline, EdgeServerDown, EdgeRateLimited,
  EdgeFeedGone, EdgeFeedParseError, EdgeRawResponse, EdgeArticleLinkRot,
  EdgeFirstRun, EdgeInboxZero, EdgeNoSearch,
  EdgeAddInvalid, EdgeAddDuplicate, EdgeSessionExpired,
});

// Editorial / Paper — desktop RSS reader prototype.
// Per FEATURES.md: Paper is the only palette. No starring, no per-feed tag,
// no priority sort. The in-product Settings page is the source of truth for
// font size + density + refresh + retention + account.

const ED_PALETTES = {
  paper: {
    label: 'Paper',
    blurb: 'Brightest, most neutral cool-grey. The closest to printer-paper — least colour, easiest on the eye for long reading.',
    bg:           '#f3f5f7',
    panel:        '#f9fafb',
    border:       'rgba(20, 25, 40, 0.08)',
    borderStrong: 'rgba(20, 25, 40, 0.16)',
    ink:          '#1a1f28',
    ink2:         '#4a5160',
    ink3:         '#7c8290',
    muted:        'rgba(20, 25, 40, 0.5)',
    accent:       '#566073',
    accentSoft:   'rgba(86, 96, 115, 0.10)',
    accentStrong: '#3e4658',
    onAccent:     '#f9fafb',
    danger:       '#a05050',
  },
};

const ED_C = ED_PALETTES.paper;
const EdThemeContext = React.createContext(ED_PALETTES.paper);

const edUiFont = '"IBM Plex Sans", ui-sans-serif, system-ui, sans-serif';
const edSerifFont = '"Source Serif 4", "Source Serif Pro", "Iowan Old Style", Georgia, serif';

function edTimeAgo(h) {
  if (h < 1)  return 'just now';
  if (h < 24) return `${h}h`;
  const d = Math.floor(h / 24);
  if (d < 7)  return `${d}d`;
  return `${Math.floor(d / 7)}w`;
}

function detectDevice() {
  const ua = navigator.userAgent;
  let browser = 'Browser';
  if (/Chrome\//.test(ua) && !/Edg\//.test(ua) && !/OPR\//.test(ua)) browser = 'Chrome';
  else if (/Edg\//.test(ua)) browser = 'Edge';
  else if (/Firefox\//.test(ua)) browser = 'Firefox';
  else if (/Safari\//.test(ua) && !/Chrome\//.test(ua)) browser = 'Safari';
  let device = 'unknown device';
  if (/Android/.test(ua)) {
    const m = ua.match(/Android [0-9.]+; ([^;)]+)/);
    device = m ? m[1].trim() : 'Android';
  } else if (/iPhone/.test(ua)) { device = 'iPhone';
  } else if (/iPad/.test(ua)) { device = 'iPad';
  } else if (/Macintosh/.test(ua)) { device = 'Mac';
  } else if (/Windows/.test(ua)) { device = 'Windows PC';
  } else if (/Linux/.test(ua)) { device = 'Linux'; }
  return `${browser} / ${device}`;
}

function EdThumb({ hue, w = 64, h = 64, label }) {
  const stripeA = `oklch(0.90 0.03 ${hue})`;
  const stripeB = `oklch(0.85 0.04 ${hue})`;
  return (
    <div style={{
      width: w, height: h, flex: '0 0 auto',
      background: `repeating-linear-gradient(135deg, ${stripeA} 0 6px, ${stripeB} 6px 12px)`,
      borderRadius: 2, border: `1px solid ${ED_C.border}`, position: 'relative', overflow: 'hidden',
    }}>
      {label ? (
        <div style={{
          position: 'absolute', inset: 0, display: 'flex', alignItems: 'center', justifyContent: 'center',
          fontFamily: 'ui-monospace, monospace', fontSize: 9, letterSpacing: '.08em',
          color: 'rgba(40,30,20,.45)', textTransform: 'uppercase',
        }}>{label}</div>
      ) : null}
    </div>
  );
}

// ─── Sidebar ─────────────────────────────────────────────────────────
function EdSidebar({ screen, setScreen, selectedFeed, setSelectedFeed, syncState, feeds, unreadCount }) {
  const ED_C = React.useContext(EdThemeContext);
  const folders = [...new Set(feeds.map(f => f.folder))];
  const NavItem = ({ id, label, count }) => {
    const active = screen === id && !selectedFeed;
    return (
      <button onClick={() => { setScreen(id); setSelectedFeed(null); }}
        style={{
          all: 'unset', display: 'flex', alignItems: 'center', justifyContent: 'space-between',
          padding: '6px 10px', borderRadius: 4, cursor: 'pointer',
          background: active ? ED_C.accentSoft : 'transparent',
          color: active ? ED_C.accent : ED_C.ink,
          fontSize: 13, fontWeight: 500,
        }}>
        <span>{label}</span>
        {count != null ? <span style={{ fontSize: 11, color: ED_C.muted, fontVariantNumeric: 'tabular-nums' }}>{count}</span> : null}
      </button>
    );
  };
  const totalUnread = unreadCount != null ? unreadCount : feeds.reduce((a, f) => a + f.unread, 0);
  const totalArticles = ARTICLES.length;
  return (
    <div style={{
      width: 220, flex: '0 0 220px', height: '100%',
      background: ED_C.panel, borderRight: `1px solid ${ED_C.border}`,
      display: 'flex', flexDirection: 'column', fontFamily: edUiFont, color: ED_C.ink,
    }}>
      {/* "Feed." wordmark — canonical brand mark (story-board/feed-icon-set.jsx):
          serif 17/500 −0.01em, trailing accent dot = 15% of font-size,
          gap 0.07em, bottoms flush. No ringed-circle icon. */}
      <div style={{ padding: '20px 18px 14px' }}>
        <div style={{ display: 'inline-flex', alignItems: 'flex-end', gap: 17 * 0.07 }}>
          <span style={{ fontFamily: edSerifFont, fontSize: 17, fontWeight: 500,
            letterSpacing: '-.01em', lineHeight: 1, color: ED_C.ink }}>Feed</span>
          <span style={{ width: 3, height: 3, borderRadius: '50%',
            background: ED_C.accent, flex: '0 0 auto' }} />
        </div>
      </div>

      {/* primary nav — order per FEATURES.md §Navigation: Unread / All / Subscriptions / Settings */}
      <div style={{ padding: '4px 10px', display: 'flex', flexDirection: 'column', gap: 1 }}>
        <NavItem id="unread"   label="Unread" count={totalUnread} />
        <NavItem id="all"      label="All articles" count={totalArticles} />
        <NavItem id="subs"     label="Subscriptions" count={feeds.length} />
        <NavItem id="settings" label="Settings" />
      </div>

      <div style={{ height: 1, background: ED_C.border, margin: '14px 18px' }} />

      <div style={{ padding: '0 10px', flex: 1, overflow: 'auto' }}>
        {folders.map(folder => (
          <div key={folder} style={{ marginBottom: 14 }}>
            <div style={{
              fontSize: 10, letterSpacing: '.1em', textTransform: 'uppercase',
              color: ED_C.ink3, padding: '4px 10px',
            }}>{folder}</div>
            {feeds.filter(f => f.folder === folder).map(f => {
              const active = selectedFeed === f.id;
              return (
                <button key={f.id}
                  onClick={() => { setSelectedFeed(f.id); setScreen('feed'); }}
                  style={{
                    all: 'unset', display: 'flex', alignItems: 'center', justifyContent: 'space-between',
                    padding: '5px 10px', borderRadius: 4, cursor: 'pointer', width: '100%', boxSizing: 'border-box',
                    background: active ? ED_C.accentSoft : 'transparent',
                    color: active ? ED_C.accent : ED_C.ink2, fontSize: 12.5,
                  }}>
                  <span style={{ display: 'flex', alignItems: 'center', gap: 8, overflow: 'hidden' }}>
                    <span style={{ width: 6, height: 6, borderRadius: '50%', background: `oklch(0.65 0.12 ${f.hue})`, flex: '0 0 auto' }} />
                    <span style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{f.name}</span>
                  </span>
                  {f.unread > 0 ? <span style={{ fontSize: 10.5, color: ED_C.muted, fontVariantNumeric: 'tabular-nums' }}>{f.unread}</span> : null}
                </button>
              );
            })}
          </div>
        ))}
      </div>

      {/* footer — two states: normal "Synced …" + sync-failed (ERR-1) */}
      <div style={{
        padding: '12px 18px', borderTop: `1px solid ${ED_C.border}`,
        fontSize: 11, display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 8,
      }}>
        {syncState === 'failed' ? (
          <React.Fragment>
            <span style={{ color: ED_C.ink2 }}>
              Last sync failed · <a href="#" style={{ color: ED_C.accent, textDecoration: 'underline', textUnderlineOffset: 2 }}>retry</a>
            </span>
            <span style={{ color: ED_C.accent }}>!</span>
          </React.Fragment>
        ) : (
          <React.Fragment>
            <span style={{ color: ED_C.ink3 }}>Synced 2m ago</span>
            <button style={{ all: 'unset', cursor: 'pointer', color: ED_C.ink3, padding: '0 4px' }}>↻</button>
          </React.Fragment>
        )}
      </div>
    </div>
  );
}

// ─── Article list ────────────────────────────────────────────────────
function EdArticleList({ articles, selectedId, setSelectedId, viewMode, density, title, subtitle, unreadSet = null, onMarkRead = null }) {
  const ED_C = React.useContext(EdThemeContext);
  const pad = density === 'compact' ? '10px 18px' : density === 'comfy' ? '20px 22px' : '14px 20px';
  const [hoveredId, setHoveredId] = React.useState(null);
  return (
    <div style={{
      width: 380, flex: '0 0 380px', height: '100%', overflow: 'auto',
      borderRight: `1px solid ${ED_C.border}`, background: ED_C.bg,
      fontFamily: edUiFont, color: ED_C.ink,
    }}>
      <div style={{ padding: '22px 22px 14px', borderBottom: `1px solid ${ED_C.border}`,
        position: 'sticky', top: 0, background: ED_C.bg, zIndex: 2 }}>
        <div style={{ fontFamily: edSerifFont, fontSize: 22, fontWeight: 500, letterSpacing: '-.015em' }}>{title}</div>
        <div style={{ fontSize: 12, color: ED_C.ink3, marginTop: 4 }}>{subtitle}</div>
      </div>

      {articles.length === 0 ? (
        <div style={{ padding: '80px 22px', textAlign: 'center', fontFamily: edSerifFont,
          fontStyle: 'italic', fontSize: 16, color: ED_C.ink3 }}>
          Nothing here yet.
        </div>
      ) : (
      <div style={{ display: 'flex', flexDirection: 'column' }}>
        {articles.map((a, i) => {
          const feed = FEED_BY_ID[a.feed];
          const isSelected = selectedId === a.id;
          return (
            <div key={a.id} onClick={() => setSelectedId(a.id)}
              style={{
                cursor: 'pointer', padding: pad,
                borderBottom: i < articles.length - 1 ? `1px solid ${ED_C.border}` : 'none',
                background: isSelected ? ED_C.panel : 'transparent',
                position: 'relative',
                display: 'flex', flexDirection: 'column', gap: 6,
              }}>
              {isSelected ? <div style={{ position: 'absolute', left: 0, top: 0, bottom: 0, width: 2, background: ED_C.accent }} /> : null}

              <div style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: 11, color: ED_C.ink3 }}>
                <span style={{ width: 6, height: 6, borderRadius: '50%', background: `oklch(0.65 0.12 ${feed.hue})` }} />
                <span style={{ fontWeight: 500, color: ED_C.ink2 }}>{feed.name}</span>
                <span>·</span>
                <span>{edTimeAgo(a.ts)}</span>
                <span style={{ flex: 1 }} />
                <span style={{ width: 52, display: 'flex', justifyContent: 'flex-end', alignItems: 'center', gap: 6, flexShrink: 0 }}>
                  {(unreadSet ? unreadSet.has(a.id) : a.unread) ? (
                    <React.Fragment>
                      <span style={{ width: 6, height: 6, borderRadius: '50%', background: ED_C.accent, flexShrink: 0 }} />
                      {onMarkRead ? (
                        <button
                          onMouseEnter={() => setHoveredId(a.id)}
                          onMouseLeave={() => setHoveredId(null)}
                          onClick={(e) => { e.stopPropagation(); onMarkRead(a.id); }}
                          style={{
                            all: 'unset', cursor: 'pointer',
                            width: 22, height: 22, borderRadius: 3,
                            border: `1px solid ${hoveredId === a.id ? ED_C.borderStrong : ED_C.border}`,
                            background: hoveredId === a.id ? ED_C.panel : 'transparent',
                            display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
                            color: hoveredId === a.id ? ED_C.ink2 : ED_C.ink3, fontSize: 11,
                            transition: 'border-color .1s, color .1s, background .1s',
                          }}>✓</button>
                      ) : null}
                    </React.Fragment>
                  ) : null}
                </span>
              </div>

              <div style={{
                fontFamily: edSerifFont, fontSize: density === 'compact' ? 15 : 17,
                lineHeight: 1.25, fontWeight: 500, color: ED_C.ink, letterSpacing: '-.01em',
              }}>{a.title}</div>

              {density !== 'compact' && (viewMode === 'card' || density === 'comfy') ? (
                <div style={{ display: 'flex', gap: 12, alignItems: 'flex-start', marginTop: 4 }}>
                  <EdThumb hue={feed.hue} w={64} h={64} />
                  <div style={{ fontSize: 12.5, color: ED_C.ink2, lineHeight: 1.45, flex: 1,
                    display: '-webkit-box', WebkitLineClamp: 2, WebkitBoxOrient: 'vertical', overflow: 'hidden' }}>{a.excerpt}</div>
                </div>
              ) : density !== 'compact' ? (
                <div style={{ fontSize: 12, color: ED_C.ink2, lineHeight: 1.4,
                  display: '-webkit-box', WebkitLineClamp: 2, WebkitBoxOrient: 'vertical', overflow: 'hidden' }}>{a.excerpt}</div>
              ) : null}

              <div style={{ fontSize: 10.5, color: ED_C.ink3, marginTop: 2, fontVariantNumeric: 'tabular-nums' }}>
                {a.minutes} min read
              </div>
            </div>
          );
        })}
      </div>
      )}
    </div>
  );
}

// ─── Reader ──────────────────────────────────────────────────────────
function EdReader({ article, fontSize, onFontSize, isUnread = false, onMarkUnread = null }) {
  const ED_C = React.useContext(EdThemeContext);
  const [fsOpen, setFsOpen] = React.useState(false);
  if (!article) {
    return (
      <div style={{
        flex: 1, height: '100%', display: 'flex', alignItems: 'center', justifyContent: 'center',
        background: ED_C.bg, color: ED_C.ink3, fontFamily: edSerifFont,
        fontSize: 16, fontStyle: 'italic', textAlign: 'center', padding: 40,
      }}>
        <div>
          <div style={{ fontSize: 32, letterSpacing: '-.02em', marginBottom: 12, color: ED_C.ink2 }}>—</div>
          Select an article to begin reading.
        </div>
      </div>
    );
  }
  const feed = FEED_BY_ID[article.feed];
  return (
    <div style={{ flex: 1, height: '100%', overflow: 'auto', background: ED_C.bg, position: 'relative' }}>
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

        <div style={{ fontFamily: edSerifFont, fontStyle: 'italic', fontSize: 18, lineHeight: 1.45, color: ED_C.ink2, marginBottom: 32 }}>
          {article.excerpt}
        </div>

        <div style={{ display: 'flex', gap: 8, marginBottom: 36, position: 'relative' }}>
          <a href={article.link} target="_blank" rel="noopener noreferrer" style={{ ...edIconBtnStyle(ED_C), textDecoration: 'none' }}>↗ Open</a>
          <button style={edIconBtnStyle(ED_C)}>⎙ Share</button>
          {onMarkUnread ? (
            <button onClick={onMarkUnread} style={edIconBtnStyle(ED_C)}>↩ Mark unread</button>
          ) : null}
          <div style={{ flex: 1 }} />
          <button onClick={() => setFsOpen(v => !v)} style={edIconBtnStyle(ED_C)}>Aa</button>
          {fsOpen ? <div style={{ position: 'absolute', right: 0, top: 38, zIndex: 10 }}>
            <EdFontSizePicker size={fontSize} setSize={(v) => { onFontSize(v); setFsOpen(false); }} />
          </div> : null}
        </div>

        <div style={{ fontSize: fontSize, lineHeight: 1.65, color: ED_C.ink }}>
          {ARTICLE_BODY.map((p, i) => (
            <p key={i} style={{ margin: '0 0 1.1em', textWrap: 'pretty' }}>{p}</p>
          ))}
        </div>

        <div style={{
          marginTop: 44, paddingTop: 24, borderTop: `1px solid ${ED_C.border}`,
          fontFamily: edUiFont, fontSize: 12, color: ED_C.ink3,
          display: 'flex', justifyContent: 'space-between', gap: 12,
        }}>
          <span>End of article</span>
          <a href={article.link} target="_blank" rel="noopener noreferrer"
            style={{ color: ED_C.ink3, textDecoration: 'underline', textUnderlineOffset: 2,
              overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', maxWidth: '70%' }}>
            {article.link}
          </a>
        </div>
      </div>
    </div>
  );
}

function EdFontSizePicker({ size, setSize }) {
  const ED_C = React.useContext(EdThemeContext);
  return (
    <div style={{
      background: ED_C.panel, border: `1px solid ${ED_C.borderStrong}`, borderRadius: 6,
      padding: 8, display: 'flex', gap: 4, fontFamily: edUiFont,
      boxShadow: '0 8px 24px rgba(0,0,0,.08)',
    }}>
      {[14,16,18,20,22,24].map(v => (
        <button key={v} onClick={() => setSize(v)} style={{
          all: 'unset', cursor: 'pointer',
          width: 30, height: 30, borderRadius: 4,
          display: 'grid', placeItems: 'center',
          background: v === size ? ED_C.ink : 'transparent',
          color: v === size ? ED_C.panel : ED_C.ink2,
          fontSize: 12, fontVariantNumeric: 'tabular-nums',
        }}>{v}</button>
      ))}
    </div>
  );
}

const edIconBtnStyle = (ED_C) => ({
  all: 'unset', cursor: 'pointer', padding: '6px 12px', borderRadius: 4,
  border: `1px solid ${ED_C.border}`, background: ED_C.panel,
  fontFamily: edUiFont, fontSize: 12, color: ED_C.ink2,
});

// ─── Subscriptions screen ────────────────────────────────────────────
function EdSubsScreen({ feeds, setFeeds }) {
  const ED_C = React.useContext(EdThemeContext);
  const [q, setQ] = React.useState('');
  const [addOpen, setAddOpen] = React.useState(false);
  const [addUrl, setAddUrl] = React.useState('');
  const [menuFor, setMenuFor] = React.useState(null);   // feed id whose ⋯ is open
  const [renameFor, setRenameFor] = React.useState(null);
  const [renameVal, setRenameVal] = React.useState('');

  const rows = feeds.filter(f => f.name.toLowerCase().includes(q.trim().toLowerCase()));

  const onRename = (f) => {
    setMenuFor(null);
    setRenameFor(f.id);
    setRenameVal(f.name);
    setTimeout(() => {
      const el = document.querySelector('input[data-rename-input]');
      if (el) { el.focus(); el.select(); }
    }, 0);
  };
  const commitRename = (f) => {
    setFeeds(feeds.map(x => x.id === f.id ? { ...x, name: renameVal || x.name } : x));
    setRenameFor(null);
  };
  const onDelete = (f) => {
    setMenuFor(null);
    if (confirm(`Delete subscription “${f.name}”?`)) {
      setFeeds(feeds.filter(x => x.id !== f.id));
    }
  };
  const submitAdd = (e) => {
    e && e.preventDefault();
    if (!addUrl.trim()) return;
    const id = 'new' + Math.random().toString(36).slice(2, 7);
    setFeeds([...feeds, {
      id, name: addUrl.replace(/^https?:\/\//, '').split('/')[0],
      author: '—', url: addUrl, hue: Math.floor(Math.random() * 360),
      folder: 'Reading', unread: 0,
    }]);
    setAddUrl(''); setAddOpen(false);
  };

  return (
    <div style={{ flex: 1, height: '100%', overflow: 'auto', background: ED_C.bg, fontFamily: edUiFont, color: ED_C.ink }}>
      <div style={{ maxWidth: 720, margin: '0 auto', padding: '48px 40px 60px' }}>
        <div style={{ display: 'flex', alignItems: 'baseline', justifyContent: 'space-between', marginBottom: 28 }}>
          <h1 style={{ fontFamily: edSerifFont, fontSize: 28, fontWeight: 500, letterSpacing: '-.02em', margin: 0 }}>Subscriptions</h1>
          <button onClick={() => setAddOpen(v => !v)} style={{
            ...edIconBtnStyle(ED_C),
            background: addOpen ? ED_C.panel : ED_C.accent,
            color: addOpen ? ED_C.ink2 : ED_C.onAccent,
            border: addOpen ? `1px solid ${ED_C.border}` : 'none', padding: '8px 14px',
          }}>{addOpen ? 'Cancel' : '+ Add feed'}</button>
        </div>

        {addOpen ? (
          <form onSubmit={submitAdd} style={{
            display: 'flex', gap: 8, padding: '12px 14px', marginBottom: 16,
            border: `1px solid ${ED_C.borderStrong}`, borderRadius: 4, background: ED_C.panel,
          }}>
            <input autoFocus value={addUrl} onChange={(e) => setAddUrl(e.target.value)}
              placeholder="https://example.com/feed.xml"
              style={{ all: 'unset', flex: 1, fontSize: 13, color: ED_C.ink, fontFamily: edUiFont }} />
            <button type="submit" style={{ ...edIconBtnStyle(ED_C), background: ED_C.ink, color: ED_C.panel, border: 'none' }}>
              Subscribe
            </button>
          </form>
        ) : null}

        <div style={{
          display: 'flex', alignItems: 'center', gap: 8, padding: '10px 14px',
          border: `1px solid ${ED_C.border}`, borderRadius: 4, background: ED_C.panel, marginBottom: 24,
        }}>
          <span style={{ color: ED_C.ink3 }}>⌕</span>
          <input value={q} onChange={(e) => setQ(e.target.value)}
            placeholder="Search subscriptions…"
            style={{ all: 'unset', flex: 1, fontSize: 13, color: ED_C.ink }} />
          <span style={{ fontSize: 11, color: ED_C.ink3 }}>{rows.length} of {feeds.length}</span>
        </div>

        {rows.length === 0 ? (
          <div style={{ padding: '60px 0', textAlign: 'center', fontFamily: edSerifFont,
            fontStyle: 'italic', fontSize: 16, color: ED_C.ink3 }}>
            Nothing here yet.
          </div>
        ) : (
        <div style={{ display: 'flex', flexDirection: 'column' }}>
          {rows.map((f, i) => (
            <div key={f.id} style={{
              display: 'flex', alignItems: 'center', gap: 14, padding: '14px 0',
              borderBottom: i < rows.length - 1 ? `1px solid ${ED_C.border}` : 'none',
              position: 'relative',
            }}>
              <div style={{
                width: 36, height: 36, borderRadius: 4, flex: '0 0 auto',
                background: `oklch(0.85 0.05 ${f.hue})`, color: `oklch(0.35 0.08 ${f.hue})`,
                display: 'flex', alignItems: 'center', justifyContent: 'center',
                fontFamily: edSerifFont, fontWeight: 500, fontSize: 16,
              }}>{f.name[0]}</div>

              <div style={{ flex: 1, minWidth: 0 }}>
                {renameFor === f.id ? (
                  <input data-rename-input value={renameVal}
                    onChange={(e) => setRenameVal(e.target.value)}
                    onBlur={() => commitRename(f)}
                    onKeyDown={(e) => { if (e.key === 'Enter') commitRename(f); if (e.key === 'Escape') setRenameFor(null); }}
                    style={{
                      all: 'unset', fontFamily: edSerifFont, fontSize: 16, fontWeight: 500, color: ED_C.ink,
                      borderBottom: `1px solid ${ED_C.borderStrong}`, padding: '0 0 2px', width: '70%',
                    }} />
                ) : (
                  <div style={{ fontFamily: edSerifFont, fontSize: 16, fontWeight: 500 }}>{f.name}</div>
                )}
                <div style={{ fontSize: 11.5, color: ED_C.ink3, marginTop: 2 }}>{f.url}</div>
              </div>

              <div style={{ fontSize: 11, color: ED_C.ink3, width: 74, textAlign: 'right' }}>
                {f.folder}
              </div>
              <div style={{ fontSize: 11, color: ED_C.ink3, width: 60, textAlign: 'right', fontVariantNumeric: 'tabular-nums' }}>
                {f.unread} new
              </div>
              <div style={{ position: 'relative' }}>
                <button onClick={() => setMenuFor(menuFor === f.id ? null : f.id)}
                  style={{ all: 'unset', cursor: 'pointer', color: ED_C.ink3, padding: '4px 8px' }}>⋯</button>
                {menuFor === f.id ? (
                  <div style={{
                    position: 'absolute', right: 0, top: 28, zIndex: 50,
                    background: ED_C.panel, border: `1px solid ${ED_C.borderStrong}`, borderRadius: 4,
                    boxShadow: '0 8px 24px rgba(0,0,0,.10)',
                    minWidth: 140, padding: 4,
                  }}>
                    <button onClick={() => onRename(f)} style={menuItemStyle(ED_C)}>Rename…</button>
                    <button onClick={() => onDelete(f)} style={{ ...menuItemStyle(ED_C), color: ED_C.danger }}>Delete</button>
                  </div>
                ) : null}
              </div>
            </div>
          ))}
        </div>
        )}
      </div>

      {/* click-away catcher for menu */}
      {menuFor ? <div onClick={() => setMenuFor(null)} style={{ position: 'fixed', inset: 0, zIndex: 40 }} /> : null}
    </div>
  );
}

const menuItemStyle = (ED_C) => ({
  all: 'unset', cursor: 'pointer', display: 'block', width: '100%', boxSizing: 'border-box',
  padding: '7px 12px', fontFamily: edUiFont, fontSize: 13, color: ED_C.ink, borderRadius: 3,
});

// ─── Settings ────────────────────────────────────────────────────────
function EdSettings({ tweak, setTweak, onLogout }) {
  const ED_C = React.useContext(EdThemeContext);
  const Row = ({ label, hint, children }) => (
    <div style={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between',
      gap: 24, padding: '18px 0', borderBottom: `1px solid ${ED_C.border}` }}>
      <div style={{ maxWidth: 360 }}>
        <div style={{ fontSize: 14, fontWeight: 500, color: ED_C.ink }}>{label}</div>
        {hint ? <div style={{ fontSize: 12, color: ED_C.ink3, marginTop: 4, lineHeight: 1.45 }}>{hint}</div> : null}
      </div>
      <div style={{ flex: '0 0 auto' }}>{children}</div>
    </div>
  );
  const Seg = ({ value, options, onChange, format }) => (
    <div style={{ display: 'flex', border: `1px solid ${ED_C.border}`, borderRadius: 4,
      background: ED_C.panel, overflow: 'hidden' }}>
      {options.map(o => {
        const active = o === value;
        return (
          <button key={String(o)} onClick={() => onChange(o)} style={{
            all: 'unset', cursor: 'pointer', padding: '6px 12px', fontSize: 12,
            background: active ? ED_C.ink : 'transparent',
            color: active ? ED_C.panel : ED_C.ink2,
            fontVariantNumeric: 'tabular-nums',
          }}>{format ? format(o) : o}</button>
        );
      })}
    </div>
  );
  const Section = ({ label }) => (
    <div style={{ fontSize: 11, letterSpacing: '.1em', textTransform: 'uppercase',
      color: ED_C.ink3, marginTop: 32, marginBottom: 4 }}>{label}</div>
  );
  return (
    <div style={{ flex: 1, height: '100%', overflow: 'auto', background: ED_C.bg, fontFamily: edUiFont, color: ED_C.ink }}>
      <div style={{ maxWidth: 640, margin: '0 auto', padding: '48px 40px 60px' }}>
        <h1 style={{ fontFamily: edSerifFont, fontSize: 28, fontWeight: 500, letterSpacing: '-.02em', margin: '0 0 28px' }}>Settings</h1>

        <div style={{ fontSize: 11, letterSpacing: '.1em', textTransform: 'uppercase', color: ED_C.ink3, marginBottom: 4 }}>Reading</div>
        <Row label="Reader font size" hint="Applies to the article body. Live-updates the open reader without reload.">
          <Seg value={tweak.fontSize} options={[14,16,18,20,22,24]}
            onChange={(v) => setTweak('fontSize', v)} />
        </Row>
        <Row label="Article-list density" hint="Compact hides excerpts; Comfy shows thumbnails.">
          <Seg value={tweak.density} options={['compact','regular','comfy']}
            onChange={(v) => setTweak('density', v)}
            format={(o) => o[0].toUpperCase() + o.slice(1)} />
        </Row>

        <Section label="Sync" />
        <Row label="Refresh interval" hint="Client-side auto-poll cadence for the article list.">
          <Seg value={tweak.refresh} options={['15m','1h','6h','Manual']}
            onChange={(v) => setTweak('refresh', v)} />
        </Row>
        <Row label="Keep articles" hint="Retention window. ∞ disables retention.">
          <Seg value={tweak.retention} options={['30d','90d','1y','∞']}
            onChange={(v) => setTweak('retention', v)} />
        </Row>

        <Section label="Account" />
        <Row label="Import OPML" hint="Bring in a backup or export from another reader.">
          <button style={edIconBtnStyle(ED_C)}>Choose file…</button>
        </Row>
        <Row label="About" hint="Client v1.0.0 · Server v0.7.2">
          <span style={{ fontSize: 12, color: ED_C.ink3 }}>—</span>
        </Row>
        <Row label="Logout" hint="Clears the local session and returns to the login screen.">
          <button onClick={onLogout} style={{ ...edIconBtnStyle(ED_C), color: ED_C.danger, borderColor: ED_C.danger }}>
            Sign out
          </button>
        </Row>
      </div>
    </div>
  );
}

// ─── Top-level prototype ─────────────────────────────────────────────
function EditorialPrototype({ tweak, setTweak, theme = 'paper' }) {
  const ED_C = ED_PALETTES[theme] || ED_PALETTES.paper;
  const density  = tweak?.density  || 'regular';
  const viewMode = tweak?.viewMode || 'list';
  const fontSize = tweak?.fontSize || 18;
  const state    = tweak?.state    || 'normal';

  const [screen, setScreen] = React.useState('unread');         // unread | all | feed | subs | settings
  const [selectedFeed, setSelectedFeed] = React.useState(null);
  const [selectedId, setSelectedId] = React.useState('a01');
  const [feeds, setFeeds] = React.useState(FEEDS);
  const [loggedIn, setLoggedIn] = React.useState(true);
  const [unreadSet, setUnreadSet] = React.useState(() => new Set(ARTICLES.filter(a => a.unread).map(a => a.id)));
  const markAsRead = (id) => setUnreadSet(prev => { const n = new Set(prev); n.delete(id); return n; });
  const markAsUnread = (id) => setUnreadSet(prev => new Set([...prev, id]));

  if (!loggedIn) {
    return (
      <EdThemeContext.Provider value={ED_C}>
        <LoginDesktop theme={theme} authError={state === 'auth-error'}
          onSignIn={() => setLoggedIn(true)} />
      </EdThemeContext.Provider>
    );
  }

  let articles = ARTICLES, title = 'All articles', subtitle = `${ARTICLES.length} total · ${unreadSet.size} unread`;
  if (selectedFeed) {
    const f = FEED_BY_ID[selectedFeed] || feeds.find(x => x.id === selectedFeed);
    articles = ARTICLES.filter(a => a.feed === selectedFeed);
    title = f ? f.name : '—';
    subtitle = f ? `${f.author} · ${articles.length} articles` : '';
  } else if (screen === 'unread') {
    articles = ARTICLES.filter(a => unreadSet.has(a.id));
    title = 'Unread'; subtitle = `${articles.length} unread articles`;
  } else if (screen === 'all') {
    articles = ARTICLES;
    title = 'All articles'; subtitle = `${ARTICLES.length} total · ${unreadSet.size} unread`;
  }
  if (state === 'empty') articles = [];

  const article = ARTICLES.find(a => a.id === selectedId);

  return (
    <EdThemeContext.Provider value={ED_C}>
    <div style={{
      width: '100%', height: '100%', display: 'flex',
      background: ED_C.bg, fontFamily: edUiFont,
    }}>
      <EdSidebar screen={screen} setScreen={setScreen}
        selectedFeed={selectedFeed} setSelectedFeed={setSelectedFeed}
        syncState={state === 'sync-failed' ? 'failed' : 'ok'}
        feeds={feeds} unreadCount={unreadSet.size} />

      {screen === 'subs' && !selectedFeed ? (
        <EdSubsScreen feeds={feeds} setFeeds={setFeeds} />
      ) : screen === 'settings' && !selectedFeed ? (
        <EdSettings tweak={tweak} setTweak={setTweak}
          onLogout={() => setLoggedIn(false)} />
      ) : (
        <React.Fragment>
          <EdArticleList articles={articles} selectedId={selectedId} setSelectedId={setSelectedId}
            viewMode={viewMode} density={density} title={title} subtitle={subtitle}
            unreadSet={unreadSet} onMarkRead={markAsRead} />
          <EdReader article={article} fontSize={fontSize}
            onFontSize={(v) => setTweak('fontSize', v)}
            isUnread={article ? unreadSet.has(article.id) : false}
            onMarkUnread={article ? () => markAsUnread(article.id) : null} />
        </React.Fragment>
      )}
    </div>
    </EdThemeContext.Provider>
  );
}

Object.assign(window, { EditorialPrototype, EdThemeContext, ED_PALETTES, EdFontSizePicker,
  edUiFont, edSerifFont, edTimeAgo, EdThumb, detectDevice });

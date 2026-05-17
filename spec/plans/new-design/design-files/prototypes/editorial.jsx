// Vibe 1 — Editorial / Minimal
// Source Serif 4 headlines + IBM Plex Sans UI.
// Themeable through ED_PALETTES + <EdThemeContext.Provider>.

const ED_PALETTES = {
  terracotta: {
    label: 'Terracotta',
    blurb: 'Warm cream paper, deep brown ink, muted terracotta accent. Earthy and magazine-like.',
    bg:           '#f6f2ea',
    panel:        '#fbf8f1',
    border:       'rgba(60, 45, 30, 0.10)',
    borderStrong: 'rgba(60, 45, 30, 0.18)',
    ink:          '#241d15',
    ink2:         '#5a4f42',
    ink3:         '#8a7e6e',
    muted:        'rgba(60, 45, 30, 0.5)',
    accent:       '#b6593c',
    accentSoft:   'rgba(182, 89, 60, 0.10)',
    onAccent:     '#fbf8f1',
  },
  sage: {
    label: 'Sage',
    blurb: 'Faintest green-tinted paper, soft moss ink, muted sage accent. Quiet and pastoral.',
    bg:           '#eef1e8',
    panel:        '#f5f7ef',
    border:       'rgba(40, 50, 30, 0.10)',
    borderStrong: 'rgba(40, 50, 30, 0.18)',
    ink:          '#1f2418',
    ink2:         '#4d5644',
    ink3:         '#7c856e',
    muted:        'rgba(40, 50, 30, 0.5)',
    accent:       '#6b8754',
    accentSoft:   'rgba(107, 135, 84, 0.12)',
    onAccent:     '#f5f7ef',
  },
  slate: {
    label: 'Slate',
    blurb: 'Cool grey-blue paper, near-black ink, muted indigo accent. Crisp, evening newspaper.',
    bg:           '#eef0f3',
    panel:        '#f5f6f8',
    border:       'rgba(30, 40, 60, 0.10)',
    borderStrong: 'rgba(30, 40, 60, 0.18)',
    ink:          '#1a1f28',
    ink2:         '#4a5160',
    ink3:         '#7a8090',
    muted:        'rgba(30, 40, 60, 0.5)',
    accent:       '#4f6789',
    accentSoft:   'rgba(79, 103, 137, 0.12)',
    onAccent:     '#f5f6f8',
  },
  plum: {
    label: 'Plum',
    blurb: 'Soft mauve paper, dark wine ink, muted plum accent. Quiet, dusk-time.',
    bg:           '#f1ecee',
    panel:        '#f7f3f4',
    border:       'rgba(60, 40, 50, 0.10)',
    borderStrong: 'rgba(60, 40, 50, 0.18)',
    ink:          '#241820',
    ink2:         '#564048',
    ink3:         '#867078',
    muted:        'rgba(60, 40, 50, 0.5)',
    accent:       '#8a5a73',
    accentSoft:   'rgba(138, 90, 115, 0.12)',
    onAccent:     '#f7f3f4',
  },

  // ─── Slate declinations ─────────────────────────────────────────────
  // All in the same cool-grey-blue family, varying paper weight, blueness,
  // and accent strength. "Slate" is the original; the rest move around it.

  paper: {
    label: 'Paper',
    blurb: 'Brightest, most neutral cool-grey. The closest to a printer-paper feel — least colour, easiest on the eye for long reading.',
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
    onAccent:     '#f9fafb',
  },

  frost: {
    label: 'Frost',
    blurb: 'A touch cooler and bluer than Slate — paper feels closer to a clear winter morning. Same ink, slightly brighter accent.',
    bg:           '#e9edf2',
    panel:        '#f0f3f8',
    border:       'rgba(30, 50, 80, 0.10)',
    borderStrong: 'rgba(30, 50, 80, 0.18)',
    ink:          '#171c27',
    ink2:         '#475066',
    ink3:         '#788294',
    muted:        'rgba(30, 50, 80, 0.5)',
    accent:       '#5276a0',
    accentSoft:   'rgba(82, 118, 160, 0.12)',
    onAccent:     '#f0f3f8',
  },

  graphite: {
    label: 'Graphite',
    blurb: 'Slightly darker, weightier paper. Stronger contrast between paper and ink — feels solid, less ethereal than Slate.',
    bg:           '#e3e6eb',
    panel:        '#ecf0f4',
    border:       'rgba(20, 30, 50, 0.12)',
    borderStrong: 'rgba(20, 30, 50, 0.22)',
    ink:          '#13181f',
    ink2:         '#424955',
    ink3:         '#707684',
    muted:        'rgba(20, 30, 50, 0.55)',
    accent:       '#3e587a',
    accentSoft:   'rgba(62, 88, 122, 0.12)',
    onAccent:     '#ecf0f4',
  },

  mist: {
    label: 'Mist',
    blurb: 'Slate with the blue rotated towards sea-glass. Same lightness, the smallest hint of green for a calmer overall feel.',
    bg:           '#eaf0f0',
    panel:        '#f2f6f6',
    border:       'rgba(30, 60, 60, 0.10)',
    borderStrong: 'rgba(30, 60, 60, 0.18)',
    ink:          '#152125',
    ink2:         '#465858',
    ink3:         '#7a8688',
    muted:        'rgba(30, 60, 60, 0.5)',
    accent:       '#4b7a7e',
    accentSoft:   'rgba(75, 122, 126, 0.12)',
    onAccent:     '#f2f6f6',
  },
};

// Default — preserves existing module-level usage that was scoped to terracotta.
const ED_C = ED_PALETTES.terracotta;

// Context lets nested components pull the active palette without prop-drilling.
const EdThemeContext = React.createContext(ED_PALETTES.terracotta);

const edUiFont = '"IBM Plex Sans", ui-sans-serif, system-ui, sans-serif';
const edSerifFont = '"Source Serif 4", "Source Serif Pro", "Iowan Old Style", Georgia, serif';

function edTimeAgo(h) {
  if (h < 1)  return 'just now';
  if (h < 24) return `${h}h`;
  const d = Math.floor(h / 24);
  if (d < 7)  return `${d}d`;
  return `${Math.floor(d / 7)}w`;
}

function EdThumb({ hue, w = 64, h = 64, label }) {
  // subtle striped placeholder, tinted by feed hue
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

function EdSidebar({ screen, setScreen, selectedFeed, setSelectedFeed }) {
  const ED_C = React.useContext(EdThemeContext);
  const folders = [...new Set(FEEDS.map(f => f.folder))];
  const NavItem = ({ id, label, count }) => (
    <button onClick={() => { setScreen(id); setSelectedFeed(null); }}
      style={{
        all: 'unset', display: 'flex', alignItems: 'center', justifyContent: 'space-between',
        padding: '6px 10px', borderRadius: 4, cursor: 'pointer',
        background: screen === id && !selectedFeed ? ED_C.accentSoft : 'transparent',
        color: screen === id && !selectedFeed ? ED_C.accent : ED_C.ink,
        fontSize: 13, fontWeight: 500,
      }}>
      <span>{label}</span>
      {count != null ? <span style={{ fontSize: 11, color: ED_C.muted, fontVariantNumeric: 'tabular-nums' }}>{count}</span> : null}
    </button>
  );
  const totalUnread = FEEDS.reduce((a, f) => a + f.unread, 0);
  return (
    <div style={{
      width: 220, flex: '0 0 220px', height: '100%',
      background: ED_C.panel, borderRight: `1px solid ${ED_C.border}`,
      display: 'flex', flexDirection: 'column', fontFamily: edUiFont, color: ED_C.ink,
    }}>
      {/* brand */}
      <div style={{ padding: '20px 18px 14px', display: 'flex', alignItems: 'center', gap: 10 }}>
        <div style={{
          width: 22, height: 22, borderRadius: '50%', border: `1.5px solid ${ED_C.ink}`,
          display: 'flex', alignItems: 'center', justifyContent: 'center',
        }}>
          <div style={{ width: 6, height: 6, borderRadius: '50%', background: ED_C.accent }} />
        </div>
        <div style={{ fontFamily: edSerifFont, fontSize: 17, fontWeight: 500, letterSpacing: '-.01em' }}>Margin</div>
      </div>

      {/* primary nav */}
      <div style={{ padding: '4px 10px', display: 'flex', flexDirection: 'column', gap: 1 }}>
        <NavItem id="feed"   label="All articles"   count={totalUnread} />
        <NavItem id="starred" label="Starred" count={ARTICLES.filter(a => a.starred).length} />
        <NavItem id="subs"   label="Subscriptions" count={FEEDS.length} />
        <NavItem id="settings" label="Settings" />
      </div>

      <div style={{ height: 1, background: ED_C.border, margin: '14px 18px' }} />

      {/* folders */}
      <div style={{ padding: '0 10px', flex: 1, overflow: 'auto' }}>
        {folders.map(folder => (
          <div key={folder} style={{ marginBottom: 14 }}>
            <div style={{
              fontSize: 10, letterSpacing: '.1em', textTransform: 'uppercase',
              color: ED_C.ink3, padding: '4px 10px',
            }}>{folder}</div>
            {FEEDS.filter(f => f.folder === folder).map(f => {
              const active = selectedFeed === f.id;
              return (
                <button key={f.id}
                  onClick={() => { setSelectedFeed(f.id); setScreen('feed'); }}
                  style={{
                    all: 'unset', display: 'flex', alignItems: 'center', justifyContent: 'space-between',
                    padding: '5px 10px', borderRadius: 4, cursor: 'pointer', width: 'calc(100% - 0px)',
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

      {/* footer */}
      <div style={{
        padding: '12px 18px', borderTop: `1px solid ${ED_C.border}`,
        fontSize: 11, color: ED_C.ink3, display: 'flex', justifyContent: 'space-between',
      }}>
        <span>Synced 2m ago</span>
        <span>↻</span>
      </div>
    </div>
  );
}

function EdArticleList({ articles, selectedId, setSelectedId, viewMode, density, title, subtitle }) {
  const ED_C = React.useContext(EdThemeContext);
  const pad = density === 'compact' ? '10px 18px' : density === 'comfy' ? '20px 22px' : '14px 20px';
  const gap = density === 'compact' ? 8 : density === 'comfy' ? 16 : 12;
  return (
    <div style={{
      width: 380, flex: '0 0 380px', height: '100%', overflow: 'auto',
      borderRight: `1px solid ${ED_C.border}`, background: ED_C.bg,
      fontFamily: edUiFont, color: ED_C.ink,
    }}>
      <div style={{ padding: '22px 22px 14px', borderBottom: `1px solid ${ED_C.border}`, position: 'sticky', top: 0, background: ED_C.bg, zIndex: 2 }}>
        <div style={{ fontFamily: edSerifFont, fontSize: 22, fontWeight: 500, letterSpacing: '-.015em' }}>{title}</div>
        <div style={{ fontSize: 12, color: ED_C.ink3, marginTop: 4 }}>{subtitle}</div>
      </div>

      <div style={{ display: 'flex', flexDirection: 'column' }}>
        {articles.map((a, i) => {
          const feed = FEED_BY_ID[a.feed];
          const isSelected = selectedId === a.id;
          return (
            <button key={a.id} onClick={() => setSelectedId(a.id)}
              style={{
                all: 'unset', cursor: 'pointer', padding: pad,
                borderBottom: i < articles.length - 1 ? `1px solid ${ED_C.border}` : 'none',
                background: isSelected ? ED_C.panel : 'transparent',
                position: 'relative',
                display: 'flex', flexDirection: 'column', gap: 6,
              }}>
              {/* selected accent bar */}
              {isSelected ? <div style={{ position: 'absolute', left: 0, top: 0, bottom: 0, width: 2, background: ED_C.accent }} /> : null}

              <div style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: 11, color: ED_C.ink3 }}>
                <span style={{ width: 6, height: 6, borderRadius: '50%', background: `oklch(0.65 0.12 ${feed.hue})` }} />
                <span style={{ fontWeight: 500, color: ED_C.ink2 }}>{feed.name}</span>
                <span>·</span>
                <span>{edTimeAgo(a.ts)}</span>
                {a.starred ? <span style={{ marginLeft: 'auto', color: ED_C.accent }}>★</span>
                  : a.unread ? <span style={{ marginLeft: 'auto', width: 6, height: 6, borderRadius: '50%', background: ED_C.accent }} /> : null}
              </div>

              <div style={{
                fontFamily: edSerifFont, fontSize: density === 'compact' ? 15 : 17,
                lineHeight: 1.25, fontWeight: 500, color: ED_C.ink, letterSpacing: '-.01em',
              }}>{a.title}</div>

              {viewMode === 'card' || density === 'comfy' ? (
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
            </button>
          );
        })}
      </div>
    </div>
  );
}

function EdReader({ article, fontSize }) {
  const ED_C = React.useContext(EdThemeContext);
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
    <div style={{ flex: 1, height: '100%', overflow: 'auto', background: ED_C.bg }}>
      <div style={{ maxWidth: 620, margin: '0 auto', padding: '52px 48px 80px', fontFamily: edSerifFont, color: ED_C.ink }}>
        {/* meta line */}
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

        <div style={{ display: 'flex', gap: 8, marginBottom: 36 }}>
          <button style={edIconBtnStyle(ED_C)}>★ Star</button>
          <button style={edIconBtnStyle(ED_C)}>↗ Open</button>
          <button style={edIconBtnStyle(ED_C)}>⎙ Share</button>
          <div style={{ flex: 1 }} />
          <button style={edIconBtnStyle(ED_C)}>Aa</button>
        </div>

        <div style={{ fontSize, lineHeight: 1.65, color: ED_C.ink }}>
          {ARTICLE_BODY.map((p, i) => (
            <p key={i} style={{ margin: '0 0 1.1em', textWrap: 'pretty' }}>{p}</p>
          ))}
        </div>

        <div style={{
          marginTop: 44, paddingTop: 24, borderTop: `1px solid ${ED_C.border}`,
          fontFamily: edUiFont, fontSize: 12, color: ED_C.ink3,
          display: 'flex', justifyContent: 'space-between',
        }}>
          <span>End of article</span>
          <span>{feed.url}</span>
        </div>
      </div>
    </div>
  );
}

const edIconBtnStyle = (ED_C) => ({
  all: 'unset', cursor: 'pointer', padding: '6px 12px', borderRadius: 4,
  border: `1px solid ${ED_C.border}`, background: ED_C.panel,
  fontFamily: edUiFont, fontSize: 12, color: ED_C.ink2,
});

function EdSubsScreen() {
  const ED_C = React.useContext(EdThemeContext);
  return (
    <div style={{ flex: 1, height: '100%', overflow: 'auto', background: ED_C.bg, fontFamily: edUiFont, color: ED_C.ink }}>
      <div style={{ maxWidth: 720, margin: '0 auto', padding: '48px 40px 60px' }}>
        <div style={{ display: 'flex', alignItems: 'baseline', justifyContent: 'space-between', marginBottom: 28 }}>
          <h1 style={{ fontFamily: edSerifFont, fontSize: 28, fontWeight: 500, letterSpacing: '-.02em', margin: 0 }}>Subscriptions</h1>
          <button style={{ ...edIconBtnStyle(ED_C), background: ED_C.accent, color: ED_C.onAccent, border: 'none', padding: '8px 14px' }}>+ Add feed</button>
        </div>

        <div style={{
          display: 'flex', alignItems: 'center', gap: 8, padding: '10px 14px',
          border: `1px solid ${ED_C.border}`, borderRadius: 4, background: ED_C.panel, marginBottom: 24,
        }}>
          <span style={{ color: ED_C.ink3 }}>⌕</span>
          <input placeholder="Search subscriptions or paste a URL…"
            style={{ all: 'unset', flex: 1, fontSize: 13, color: ED_C.ink }} />
          <span style={{ fontSize: 11, color: ED_C.ink3 }}>{FEEDS.length} feeds</span>
        </div>

        <div style={{ display: 'flex', flexDirection: 'column' }}>
          {FEEDS.map((f, i) => (
            <div key={f.id} style={{
              display: 'flex', alignItems: 'center', gap: 14, padding: '14px 0',
              borderBottom: i < FEEDS.length - 1 ? `1px solid ${ED_C.border}` : 'none',
            }}>
              <div style={{
                width: 36, height: 36, borderRadius: 4, flex: '0 0 auto',
                background: `oklch(0.85 0.05 ${f.hue})`, color: `oklch(0.35 0.08 ${f.hue})`,
                display: 'flex', alignItems: 'center', justifyContent: 'center',
                fontFamily: edSerifFont, fontWeight: 500, fontSize: 16,
              }}>{f.name[0]}</div>

              <div style={{ flex: 1, minWidth: 0 }}>
                <div style={{ display: 'flex', alignItems: 'baseline', gap: 8 }}>
                  <span style={{ fontFamily: edSerifFont, fontSize: 16, fontWeight: 500 }}>{f.name}</span>
                  <span style={{ fontSize: 11, color: ED_C.ink3 }}>· {f.tag}</span>
                </div>
                <div style={{ fontSize: 11.5, color: ED_C.ink3, marginTop: 2 }}>{f.url}</div>
              </div>

              <div style={{ fontSize: 11, color: ED_C.ink3, width: 64, textAlign: 'right' }}>
                {f.folder}
              </div>
              <div style={{ fontSize: 11, color: ED_C.ink3, width: 60, textAlign: 'right', fontVariantNumeric: 'tabular-nums' }}>
                {f.unread} new
              </div>
              <button style={{ all: 'unset', cursor: 'pointer', color: ED_C.ink3, padding: '4px 8px' }}>⋯</button>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}

function EdSettings() {
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
  const Seg = ({ options, value }) => (
    <div style={{ display: 'flex', border: `1px solid ${ED_C.border}`, borderRadius: 4, background: ED_C.panel }}>
      {options.map(o => (
        <div key={o} style={{
          padding: '6px 12px', fontSize: 12,
          background: o === value ? ED_C.ink : 'transparent',
          color: o === value ? '#fbf8f1' : ED_C.ink2,
        }}>{o}</div>
      ))}
    </div>
  );
  return (
    <div style={{ flex: 1, height: '100%', overflow: 'auto', background: ED_C.bg, fontFamily: edUiFont, color: ED_C.ink }}>
      <div style={{ maxWidth: 640, margin: '0 auto', padding: '48px 40px 60px' }}>
        <h1 style={{ fontFamily: edSerifFont, fontSize: 28, fontWeight: 500, letterSpacing: '-.02em', margin: '0 0 28px' }}>Settings</h1>

        <div style={{ fontSize: 11, letterSpacing: '.1em', textTransform: 'uppercase', color: ED_C.ink3, marginBottom: 4 }}>Reading</div>
        <Row label="Mark as read on scroll" hint="Automatically mark articles as read once they pass the fold.">
          <Seg options={['Off', 'On']} value="On" />
        </Row>
        <Row label="Reader theme" hint="Background and contrast of the article reader.">
          <Seg options={['Paper', 'Soft', 'Dim']} value="Paper" />
        </Row>
        <Row label="Default sort" hint="Order new articles by publish time or by feed priority.">
          <Seg options={['Newest', 'Priority']} value="Newest" />
        </Row>

        <div style={{ fontSize: 11, letterSpacing: '.1em', textTransform: 'uppercase', color: ED_C.ink3, marginTop: 32, marginBottom: 4 }}>Sync</div>
        <Row label="Refresh interval" hint="How often Margin checks every subscribed feed.">
          <Seg options={['15m', '1h', '6h', 'Manual']} value="1h" />
        </Row>
        <Row label="Keep articles" hint="Delete articles older than this, even if unread.">
          <Seg options={['30d', '90d', '1y', '∞']} value="90d" />
        </Row>

        <div style={{ fontSize: 11, letterSpacing: '.1em', textTransform: 'uppercase', color: ED_C.ink3, marginTop: 32, marginBottom: 4 }}>Account</div>
        <Row label="Signed in as" hint="Personal library, not synced anywhere.">
          <span style={{ fontSize: 13, color: ED_C.ink2 }}>local · this device</span>
        </Row>
        <Row label="Import OPML" hint="Bring in a backup or export from another reader.">
          <button style={edIconBtnStyle(ED_C)}>Choose file…</button>
        </Row>
      </div>
    </div>
  );
}

function EditorialPrototype({ density = 'regular', viewMode = 'list', fontSize = 18, theme = 'terracotta' }) {
  const ED_C = ED_PALETTES[theme] || ED_PALETTES.terracotta;
  const [screen, setScreen] = React.useState('feed');         // feed | starred | subs | settings
  const [selectedFeed, setSelectedFeed] = React.useState(null);
  const [selectedId, setSelectedId] = React.useState('a01');

  // filter articles by current view
  let articles = ARTICLES;
  let title = 'All articles', subtitle = '';
  if (selectedFeed) {
    const f = FEED_BY_ID[selectedFeed];
    articles = ARTICLES.filter(a => a.feed === selectedFeed);
    title = f.name; subtitle = `${f.author} · ${articles.length} articles`;
  } else if (screen === 'starred') {
    articles = ARTICLES.filter(a => a.starred);
    title = 'Starred'; subtitle = `${articles.length} saved`;
  } else if (screen === 'feed') {
    subtitle = `${articles.filter(a => a.unread).length} unread · ${articles.length} total`;
  }
  const article = ARTICLES.find(a => a.id === selectedId);

  return (
    <EdThemeContext.Provider value={ED_C}>
    <div style={{
      width: '100%', height: '100%', display: 'flex',
      background: ED_C.bg, fontFamily: edUiFont,
      // local font weights for Source Serif look on system fallback
    }}>
      <EdSidebar screen={screen} setScreen={setScreen}
        selectedFeed={selectedFeed} setSelectedFeed={setSelectedFeed} />

      {screen === 'subs' && !selectedFeed ? (
        <EdSubsScreen />
      ) : screen === 'settings' && !selectedFeed ? (
        <EdSettings />
      ) : (
        <React.Fragment>
          <EdArticleList articles={articles} selectedId={selectedId} setSelectedId={setSelectedId}
            viewMode={viewMode} density={density} title={title} subtitle={subtitle} />
          <EdReader article={article} fontSize={fontSize} />
        </React.Fragment>
      )}
    </div>
    </EdThemeContext.Provider>
  );
}

Object.assign(window, { EditorialPrototype, EdThemeContext, ED_PALETTES });

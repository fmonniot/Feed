// Editorial — Mobile (Android, fits inside <AndroidDevice>)
// Per FEATURES.md: no top header (the tab bar IS the nav), no Saved tab,
// no star button in reader, no filter chips above the list. Tabs are:
// Unread / All / Feeds / Settings. The reader pushes on top, hides the bar.
//
// Reuses ED_C palette + fonts from editorial.jsx. Reads its preference state
// from the same in-product store as the desktop editorial (density, font
// size, refresh cadence, retention).

function EdMHeader({ title, subtitle, topInset = 14 }) {
  const ED_C = React.useContext(EdThemeContext);
  return (
    <div style={{
      paddingTop: topInset + 14,
      paddingLeft: 22, paddingRight: 22, paddingBottom: 18,
      background: ED_C.bg, borderBottom: `1px solid ${ED_C.border}`,
      fontFamily: edUiFont, color: ED_C.ink,
    }}>
      <h1 style={{ fontFamily: edSerifFont, fontSize: 30, fontWeight: 500,
        letterSpacing: '-.02em', lineHeight: 1.05, margin: 0 }}>{title}</h1>
      {subtitle ? <div style={{ fontSize: 12, color: ED_C.ink3, marginTop: 6 }}>{subtitle}</div> : null}
    </div>
  );
}

function EdMArticleCard({ a, onOpen, density, viewMode, isUnread = true, onMarkRead = null }) {
  const ED_C = React.useContext(EdThemeContext);
  const feed = FEED_BY_ID[a.feed];
  const pad = density === 'compact' ? '12px 22px' : density === 'comfy' ? '20px 22px' : '16px 22px';
  return (
    <div onClick={() => onOpen(a.id)} style={{
      cursor: 'pointer', display: 'flex', flexDirection: 'column', gap: 8,
      padding: pad, borderBottom: `1px solid ${ED_C.border}`, background: ED_C.bg,
      width: '100%', boxSizing: 'border-box',
    }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: 11, color: ED_C.ink3 }}>
        <span style={{ width: 6, height: 6, borderRadius: '50%', background: `oklch(0.65 0.12 ${feed.hue})` }} />
        <span style={{ fontWeight: 500, color: ED_C.ink2 }}>{feed.name}</span>
        <span>·</span>
        <span>{edTimeAgo(a.ts)}</span>
        <span style={{ flex: 1 }} />
        <span style={{ width: 52, display: 'flex', justifyContent: 'flex-end', alignItems: 'center', gap: 6, flexShrink: 0 }}>
          {isUnread ? (
            <React.Fragment>
              <span style={{ width: 6, height: 6, borderRadius: '50%', background: ED_C.accent, flexShrink: 0 }} />
              {onMarkRead ? (
                <button
                  onClick={(e) => { e.stopPropagation(); onMarkRead(a.id); }}
                  style={{
                    all: 'unset', cursor: 'pointer',
                    width: 28, height: 28, borderRadius: 3,
                    border: `1px solid ${ED_C.border}`, background: ED_C.panel,
                    display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
                    color: ED_C.ink3, fontSize: 12,
                  }}>✓</button>
              ) : null}
            </React.Fragment>
          ) : null}
        </span>
      </div>
      <div style={{ fontFamily: edSerifFont, fontSize: density === 'compact' ? 16 : 18,
        lineHeight: 1.2, fontWeight: 500, color: ED_C.ink, letterSpacing: '-.01em' }}>
        {a.title}
      </div>
      {density !== 'compact' && (viewMode === 'card' || density === 'comfy') ? (
        <div style={{ display: 'flex', gap: 12, alignItems: 'flex-start', marginTop: 2 }}>
          <EdThumb hue={feed.hue} w={56} h={56} />
          <div style={{ fontSize: 12.5, color: ED_C.ink2, lineHeight: 1.45, flex: 1,
            display: '-webkit-box', WebkitLineClamp: 2, WebkitBoxOrient: 'vertical', overflow: 'hidden' }}>
            {a.excerpt}
          </div>
        </div>
      ) : density !== 'compact' ? (
        <div style={{ fontSize: 12.5, color: ED_C.ink2, lineHeight: 1.45,
          display: '-webkit-box', WebkitLineClamp: 2, WebkitBoxOrient: 'vertical', overflow: 'hidden' }}>
          {a.excerpt}
        </div>
      ) : null}
      <div style={{ fontSize: 10.5, color: ED_C.ink3, fontVariantNumeric: 'tabular-nums' }}>{a.minutes} min read</div>
    </div>
  );
}

// Android bottom nav: Unread · All · Feeds · Settings.
// Active tab gets a soft accent pill behind the icon + accent-coloured label.
function EdMTabBar({ tab, setTab }) {
  const ED_C = React.useContext(EdThemeContext);
  const items = [
    { id: 'unread',   label: 'Unread',  icon: '◉' },
    { id: 'all',      label: 'All',     icon: '☰' },
    { id: 'subs',     label: 'Feeds',   icon: '⌒' },
    { id: 'settings', label: 'Settings', icon: '◌' },
  ];
  return (
    <div style={{
      position: 'absolute', left: 0, right: 0, bottom: 0, paddingBottom: 30, paddingTop: 6,
      background: `${ED_C.panel}f0`, borderTop: `1px solid ${ED_C.border}`,
      backdropFilter: 'blur(24px)', WebkitBackdropFilter: 'blur(24px)',
      display: 'flex', fontFamily: edUiFont, zIndex: 20,
    }}>
      {items.map(it => {
        const active = tab === it.id;
        return (
          <button key={it.id} onClick={() => setTab(it.id)} style={{
            all: 'unset', cursor: 'pointer', flex: 1, padding: '6px 0',
            display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 3,
            color: active ? ED_C.accent : ED_C.ink3,
          }}>
            <span style={{
              fontSize: 18, fontFamily: edSerifFont, lineHeight: 1,
              padding: '4px 18px', borderRadius: 999,
              background: active ? ED_C.accentSoft : 'transparent',
              transition: 'background .15s',
            }}>{it.icon}</span>
            <span style={{ fontSize: 10, fontWeight: active ? 600 : 500 }}>{it.label}</span>
          </button>
        );
      })}
    </div>
  );
}

function EdMReaderScreen({ article, fontSize, onBack, topInset = 14, isUnread = false, onMarkUnread = null }) {
  const ED_C = React.useContext(EdThemeContext);
  const feed = FEED_BY_ID[article.feed];
  const [fsOpen, setFsOpen] = React.useState(false);
  const [size, setSize] = React.useState(fontSize);
  React.useEffect(() => setSize(fontSize), [fontSize]);
  return (
    <div style={{ height: '100%', display: 'flex', flexDirection: 'column', background: ED_C.bg, position: 'relative' }}>
      {/* sticky reader header */}
      <div style={{
        padding: `${topInset + 10}px 16px 10px`,
        display: 'flex', alignItems: 'center', justifyContent: 'space-between',
        background: ED_C.bg, borderBottom: `1px solid ${ED_C.border}`,
        fontFamily: edUiFont,
      }}>
        <button onClick={onBack} style={{
          all: 'unset', cursor: 'pointer', display: 'flex', alignItems: 'center', gap: 4,
          color: ED_C.accent, fontSize: 14, padding: '4px 6px',
        }}>‹ {feed.name}</button>
        <div style={{ display: 'flex', gap: 4 }}>
          {onMarkUnread ? (
            <button onClick={onMarkUnread} style={edMIconBtn(ED_C)}>↩</button>
          ) : null}
          <button onClick={() => setFsOpen(v => !v)} style={edMIconBtn(ED_C)}>Aa</button>
          <a href={article.link} target="_blank" rel="noopener noreferrer" style={{ ...edMIconBtn(ED_C), textDecoration: 'none' }}>↗</a>
        </div>
      </div>

      {fsOpen ? (
        <EdFontSizePicker size={size} setSize={setSize} onDismiss={() => setFsOpen(false)} />
      ) : null}

      <div style={{ flex: 1, overflow: 'auto', padding: '24px 24px 80px',
        fontFamily: edSerifFont, color: ED_C.ink }}>
        <div style={{
          fontFamily: edUiFont, fontSize: 10.5, letterSpacing: '.08em',
          textTransform: 'uppercase', color: ED_C.ink3, marginBottom: 12,
        }}>
          {feed.name} · {feed.author} · {edTimeAgo(article.ts)}
        </div>
        <h1 style={{ fontSize: 26, fontWeight: 500, lineHeight: 1.15, letterSpacing: '-.02em', margin: '0 0 12px' }}>
          {article.title}
        </h1>
        <div style={{ fontStyle: 'italic', fontSize: 16, lineHeight: 1.5, color: ED_C.ink2, marginBottom: 22 }}>
          {article.excerpt}
        </div>
        <div style={{ fontSize: size, lineHeight: 1.65 }}>
          {ARTICLE_BODY.map((p, i) => (
            <p key={i} style={{ margin: '0 0 1.1em', textWrap: 'pretty' }}>{p}</p>
          ))}
        </div>
        <div style={{
          marginTop: 28, paddingTop: 18, borderTop: `1px solid ${ED_C.border}`,
          fontFamily: edUiFont, fontSize: 11, color: ED_C.ink3,
          display: 'flex', justifyContent: 'space-between', gap: 10,
        }}>
          <span>End of article</span>
          <a href={article.link} target="_blank" rel="noopener noreferrer"
            style={{ color: ED_C.ink3, textDecoration: 'underline', textUnderlineOffset: 2,
              overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', maxWidth: '60%' }}>
            {article.link}
          </a>
        </div>
      </div>
    </div>
  );
}

const edMIconBtn = (ED_C) => ({
  all: 'unset', cursor: 'pointer', padding: '6px 10px', borderRadius: 4,
  border: `1px solid ${ED_C.border}`, background: ED_C.panel,
  fontSize: 12, color: ED_C.ink2,
});

function EdMSubsScreen({ topInset = 14 }) {
  const ED_C = React.useContext(EdThemeContext);
  const [q, setQ] = React.useState('');
  const matches = (f) => f.name.toLowerCase().includes(q.trim().toLowerCase());
  const folders = [...new Set(FEEDS.map(f => f.folder))];
  return (
    <div style={{ height: '100%', display: 'flex', flexDirection: 'column' }}>
      <EdMHeader title="Feeds" subtitle={`${FEEDS.length} subscriptions`} topInset={topInset} />
      <div style={{ flex: 1, overflow: 'auto', paddingBottom: 100, background: ED_C.bg }}>
        <div style={{ padding: '14px 22px' }}>
          <div style={{
            display: 'flex', alignItems: 'center', gap: 8, padding: '10px 14px',
            border: `1px solid ${ED_C.border}`, borderRadius: 4, background: ED_C.panel,
          }}>
            <span style={{ color: ED_C.ink3 }}>⌕</span>
            <input value={q} onChange={(e) => setQ(e.target.value)}
              placeholder="Search or paste a URL…"
              style={{ all: 'unset', flex: 1, fontSize: 13, color: ED_C.ink, fontFamily: edUiFont }} />
          </div>
        </div>

        {folders.map(folder => {
          const rows = FEEDS.filter(f => f.folder === folder && matches(f));
          if (rows.length === 0) return null;
          return (
            <div key={folder}>
              <div style={{
                padding: '14px 22px 6px', fontSize: 10, letterSpacing: '.1em', textTransform: 'uppercase',
                color: ED_C.ink3, fontFamily: edUiFont,
              }}>{folder}</div>
              {rows.map((f, i, arr) => (
                <div key={f.id} style={{
                  display: 'flex', alignItems: 'center', gap: 14,
                  padding: '12px 22px', background: ED_C.bg,
                  borderBottom: `1px solid ${ED_C.border}`,
                }}>
                  <div style={{
                    width: 34, height: 34, borderRadius: 4, flex: '0 0 auto',
                    background: `oklch(0.85 0.05 ${f.hue})`, color: `oklch(0.35 0.08 ${f.hue})`,
                    display: 'flex', alignItems: 'center', justifyContent: 'center',
                    fontFamily: edSerifFont, fontWeight: 500, fontSize: 15,
                  }}>{f.name[0]}</div>
                  <div style={{ flex: 1, minWidth: 0 }}>
                    <div style={{ fontFamily: edSerifFont, fontSize: 15, fontWeight: 500, color: ED_C.ink }}>{f.name}</div>
                    <div style={{ fontSize: 11, color: ED_C.ink3, marginTop: 2,
                      overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{f.url}</div>
                  </div>
                  <div style={{ fontSize: 11, color: ED_C.ink3, fontVariantNumeric: 'tabular-nums' }}>{f.unread}</div>
                </div>
              ))}
            </div>
          );
        })}

        {q && FEEDS.filter(matches).length === 0 ? (
          <div style={{ padding: '40px 22px', textAlign: 'center', fontFamily: edSerifFont,
            fontStyle: 'italic', fontSize: 14, color: ED_C.ink3 }}>
            Nothing here yet.
          </div>
        ) : null}
      </div>
    </div>
  );
}

function EdMSettingsScreen({ topInset = 14, tweak, setTweak, onLogout }) {
  const ED_C = React.useContext(EdThemeContext);
  const Seg = ({ value, options, onChange, format }) => (
    <div style={{ display: 'flex', border: `1px solid ${ED_C.border}`, borderRadius: 4,
      background: ED_C.panel, overflow: 'hidden' }}>
      {options.map(o => {
        const active = o === value;
        return (
          <button key={String(o)} onClick={() => onChange(o)} style={{
            all: 'unset', cursor: 'pointer', padding: '6px 10px', fontSize: 11.5,
            background: active ? ED_C.ink : 'transparent',
            color: active ? ED_C.panel : ED_C.ink2,
            fontVariantNumeric: 'tabular-nums',
          }}>{format ? format(o) : o}</button>
        );
      })}
    </div>
  );
  const Row = ({ label, hint, children, last = false, onClick, danger }) => (
    <button onClick={onClick} disabled={!onClick} style={{
      all: 'unset', cursor: onClick ? 'pointer' : 'default', display: 'block',
      width: '100%', boxSizing: 'border-box',
    }}>
      <div style={{
        display: 'flex', alignItems: 'center', justifyContent: 'space-between',
        gap: 16, padding: '12px 22px', background: ED_C.bg,
        borderBottom: last ? 'none' : `1px solid ${ED_C.border}`,
        fontFamily: edUiFont,
      }}>
        <div style={{ minWidth: 0, flex: 1 }}>
          <div style={{ fontSize: 14, color: danger ? ED_C.accent : ED_C.ink }}>{label}</div>
          {hint ? <div style={{ fontSize: 11.5, color: ED_C.ink3, marginTop: 2,
            overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{hint}</div> : null}
        </div>
        <div style={{ flex: '0 0 auto', fontSize: 12, color: ED_C.ink3,
          display: 'flex', alignItems: 'center', gap: 6 }}>{children}</div>
      </div>
    </button>
  );
  const Group = ({ title, children }) => (
    <div>
      <div style={{
        padding: '18px 22px 6px', fontSize: 10, letterSpacing: '.1em', textTransform: 'uppercase',
        color: ED_C.ink3, fontFamily: edUiFont,
      }}>{title}</div>
      {children}
    </div>
  );
  return (
    <div style={{ height: '100%', display: 'flex', flexDirection: 'column' }}>
      <EdMHeader title="Settings" subtitle="Signed in as admin" topInset={topInset} />
      <div style={{ flex: 1, overflow: 'auto', paddingBottom: 100, background: ED_C.bg }}>
        <Group title="Reading">
          <Row label="Reader font size" hint="Applies to article body — live.">
            <Seg value={tweak.fontSize} options={[14,16,18,20,22,24]}
              onChange={(v) => setTweak('fontSize', v)} />
          </Row>
          <Row label="Article-list density" hint="Compact hides excerpts. Comfy shows thumbnails." last>
            <Seg value={tweak.density} options={['compact','regular','comfy']}
              onChange={(v) => setTweak('density', v)}
              format={(o) => o[0].toUpperCase() + o.slice(1)} />
          </Row>
        </Group>

        <Group title="Sync">
          <Row label="Refresh interval" hint="Client-side auto-poll cadence.">
            <Seg value={tweak.refresh} options={['15m','1h','6h','Manual']}
              onChange={(v) => setTweak('refresh', v)} />
          </Row>
          <Row label="Keep articles" hint="Retention window for the server sweep.">
            <Seg value={tweak.retention} options={['30d','90d','1y','∞']}
              onChange={(v) => setTweak('retention', v)} />
          </Row>
          <Row label="Server URL" hint={tweak.serverUrl} last>
            <span style={{ color: ED_C.ink3 }}>›</span>
          </Row>
        </Group>

        <Group title="Account">
          <Row label="Import OPML" hint="Upload a backup or another reader's export.">
            <span style={{ color: ED_C.ink2 }}>Choose…</span>
          </Row>
          <Row label="About" hint="Client v1.0.0 · Server v0.7.2">
            <span style={{ color: ED_C.ink3 }}>›</span>
          </Row>
          <Row label="Logout" onClick={onLogout} danger last>
            <span style={{ color: ED_C.accent }}>›</span>
          </Row>
        </Group>
      </div>
    </div>
  );
}

// Small popover bound to the reader's Aa button — works on both desktop & mobile,
// rendered inline as an absolutely-positioned card.
function EdFontSizePicker({ size, setSize, onDismiss }) {
  const ED_C = React.useContext(EdThemeContext);
  return (
    <div style={{
      position: 'absolute', top: 56, right: 16, zIndex: 30,
      background: ED_C.panel, border: `1px solid ${ED_C.borderStrong}`, borderRadius: 6,
      padding: 10, display: 'flex', gap: 6, fontFamily: edUiFont,
      boxShadow: '0 8px 24px rgba(0,0,0,.08)',
    }}>
      {[14,16,18,20,22,24].map(v => (
        <button key={v} onClick={() => { setSize(v); onDismiss && onDismiss(); }} style={{
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

// Lightweight pull-to-refresh affordance: a spinner above the list, visible only
// when `loading === true`. Driven by the State tweak in the canvas.
function EdMPullToRefresh({ loading }) {
  const ED_C = React.useContext(EdThemeContext);
  if (!loading) return null;
  return (
    <div style={{
      display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 8,
      padding: '12px 0', fontFamily: edUiFont, fontSize: 11, color: ED_C.ink3,
    }}>
      <span style={{
        display: 'inline-block', width: 14, height: 14, borderRadius: '50%',
        border: `2px solid ${ED_C.border}`, borderTopColor: ED_C.accent,
        animation: 'edSpin .8s linear infinite',
      }} />
      Refreshing…
      <style>{`@keyframes edSpin { to { transform: rotate(360deg); } }`}</style>
    </div>
  );
}

function EditorialMobilePrototype({ tweak, setTweak, fontSize = 17, theme = 'paper', topInset = 14 }) {
  const ED_C = ED_PALETTES[theme] || ED_PALETTES.paper;
  const density  = tweak?.density  || 'regular';
  const viewMode = tweak?.viewMode || 'list';
  const state    = tweak?.state    || 'normal';
  const [tab, setTab] = React.useState('unread');           // unread | all | subs | settings
  const [openId, setOpenId] = React.useState(null);
  const [loggedIn, setLoggedIn] = React.useState(true);
  const [unreadSet, setUnreadSet] = React.useState(() => new Set(ARTICLES.filter(a => a.unread).map(a => a.id)));
  const markAsRead = (id) => setUnreadSet(prev => { const n = new Set(prev); n.delete(id); return n; });
  const markAsUnread = (id) => setUnreadSet(prev => new Set([...prev, id]));

  if (!loggedIn) {
    return <LoginMobile theme={theme} topInset={topInset} authError={state === 'auth-error'}
      onSignIn={() => setLoggedIn(true)} />;
  }

  let articles = [], title = '', subtitle = '';
  if (tab === 'all') {
    articles = ARTICLES;
    title = 'All';
    subtitle = `${unreadSet.size} unread · ${ARTICLES.length} total`;
  } else if (tab === 'unread') {
    articles = ARTICLES.filter(a => unreadSet.has(a.id));
    title = 'Unread';
    subtitle = `${articles.length} articles`;
  }
  if (state === 'empty') articles = [];
  const article = ARTICLES.find(a => a.id === openId);

  return (
    <EdThemeContext.Provider value={ED_C}>
    <div style={{ position: 'relative', width: '100%', height: '100%', background: ED_C.bg }}>
      {article ? (
        <EdMReaderScreen article={article} fontSize={fontSize} onBack={() => setOpenId(null)} topInset={topInset}
          isUnread={unreadSet.has(article.id)} onMarkUnread={() => markAsUnread(article.id)} />
      ) : tab === 'subs' ? (
        <EdMSubsScreen topInset={topInset} />
      ) : tab === 'settings' ? (
        <EdMSettingsScreen topInset={topInset} tweak={tweak} setTweak={setTweak}
          onLogout={() => setLoggedIn(false)} />
      ) : (
        <div style={{ height: '100%', display: 'flex', flexDirection: 'column' }}>
          <EdMHeader title={title} subtitle={subtitle} topInset={topInset} />
          <div style={{ flex: 1, overflow: 'auto', paddingBottom: 100 }}>
            <EdMPullToRefresh loading={state === 'loading'} />
            {articles.length === 0 ? (
              <div style={{ padding: '64px 22px', textAlign: 'center', fontFamily: edSerifFont,
                fontStyle: 'italic', fontSize: 16, color: ED_C.ink3 }}>
                Nothing here yet.
              </div>
            ) : articles.map(a => (
              <EdMArticleCard key={a.id} a={a} onOpen={setOpenId} density={density} viewMode={viewMode}
              isUnread={unreadSet.has(a.id)} onMarkRead={markAsRead} />
            ))}
          </div>
        </div>
      )}

      {!article ? <EdMTabBar tab={tab} setTab={setTab} /> : null}
    </div>
    </EdThemeContext.Provider>
  );
}

Object.assign(window, { EditorialMobilePrototype });

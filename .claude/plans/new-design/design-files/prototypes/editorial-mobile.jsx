// Editorial — Mobile (iOS, fits inside <IOSDevice>)
// Same warm cream + terracotta system as the desktop EditorialPrototype,
// reflowed into a single-column flow with a bottom tab bar and push-style reader.

// Reuse the ED_C palette + fonts already defined by editorial.jsx.

function EdMHeader({ title, subtitle, onMenu, large = true, topInset = 56 }) {
  const ED_C = React.useContext(EdThemeContext);
  return (
    <div style={{
      paddingTop: topInset, // clear status bar / dynamic island
      paddingLeft: 22, paddingRight: 22, paddingBottom: large ? 18 : 12,
      background: ED_C.bg, borderBottom: `1px solid ${ED_C.border}`,
      fontFamily: edUiFont, color: ED_C.ink,
    }}>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: large ? 16 : 4 }}>
        <button onClick={onMenu} style={{
          all: 'unset', cursor: 'pointer', width: 36, height: 36, borderRadius: 4,
          display: 'flex', alignItems: 'center', justifyContent: 'center', marginLeft: -8,
          color: ED_C.ink2, fontSize: 18,
        }}>≡</button>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <div style={{
            width: 22, height: 22, borderRadius: '50%', border: `1.5px solid ${ED_C.ink}`,
            display: 'flex', alignItems: 'center', justifyContent: 'center',
          }}>
            <div style={{ width: 6, height: 6, borderRadius: '50%', background: ED_C.accent }} />
          </div>
          <div style={{ fontFamily: edSerifFont, fontSize: 16, fontWeight: 500, letterSpacing: '-.01em' }}>Margin</div>
        </div>
        <button style={{
          all: 'unset', cursor: 'pointer', width: 36, height: 36, borderRadius: 4,
          display: 'flex', alignItems: 'center', justifyContent: 'center', marginRight: -8,
          color: ED_C.ink2, fontSize: 16,
        }}>⌕</button>
      </div>

      {large ? (
        <React.Fragment>
          <h1 style={{ fontFamily: edSerifFont, fontSize: 30, fontWeight: 500,
            letterSpacing: '-.02em', lineHeight: 1.05, margin: 0 }}>{title}</h1>
          <div style={{ fontSize: 12, color: ED_C.ink3, marginTop: 6 }}>{subtitle}</div>
        </React.Fragment>
      ) : null}
    </div>
  );
}

function EdMArticleCard({ a, onOpen, density, viewMode }) {
  const ED_C = React.useContext(EdThemeContext);
  const feed = FEED_BY_ID[a.feed];
  const pad = density === 'compact' ? '12px 22px' : density === 'comfy' ? '20px 22px' : '16px 22px';
  return (
    <button onClick={() => onOpen(a.id)} style={{
      all: 'unset', cursor: 'pointer', display: 'flex', flexDirection: 'column', gap: 8,
      padding: pad, borderBottom: `1px solid ${ED_C.border}`, background: ED_C.bg,
      width: 'calc(100% - 44px)',
    }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: 11, color: ED_C.ink3 }}>
        <span style={{ width: 6, height: 6, borderRadius: '50%', background: `oklch(0.65 0.12 ${feed.hue})` }} />
        <span style={{ fontWeight: 500, color: ED_C.ink2 }}>{feed.name}</span>
        <span>·</span>
        <span>{edTimeAgo(a.ts)}</span>
        {a.starred ? <span style={{ marginLeft: 'auto', color: ED_C.accent }}>★</span>
          : a.unread ? <span style={{ marginLeft: 'auto', width: 6, height: 6, borderRadius: '50%', background: ED_C.accent }} /> : null}
      </div>
      <div style={{ fontFamily: edSerifFont, fontSize: density === 'compact' ? 16 : 18,
        lineHeight: 1.2, fontWeight: 500, color: ED_C.ink, letterSpacing: '-.01em' }}>
        {a.title}
      </div>
      {viewMode === 'card' || density === 'comfy' ? (
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
    </button>
  );
}

function EdMTabBar({ tab, setTab }) {
  const ED_C = React.useContext(EdThemeContext);
  const items = [
    { id: 'today', label: 'Today', icon: '◔' },
    { id: 'saved', label: 'Saved', icon: '★' },
    { id: 'subs',  label: 'Feeds', icon: '⌒' },
    { id: 'settings', label: 'Settings', icon: '◌' },
  ];
  return (
    <div style={{
      position: 'absolute', left: 0, right: 0, bottom: 0, paddingBottom: 30,
      background: `${ED_C.panel}f0`, borderTop: `1px solid ${ED_C.border}`,
      backdropFilter: 'blur(24px)', WebkitBackdropFilter: 'blur(24px)',
      display: 'flex', fontFamily: edUiFont, zIndex: 20,
    }}>
      {items.map(it => {
        const active = tab === it.id;
        return (
          <button key={it.id} onClick={() => setTab(it.id)} style={{
            all: 'unset', cursor: 'pointer', flex: 1, padding: '10px 0 6px',
            display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 3,
            color: active ? ED_C.accent : ED_C.ink3,
          }}>
            <span style={{ fontSize: 18, fontFamily: edSerifFont }}>{it.icon}</span>
            <span style={{ fontSize: 10, fontWeight: 500 }}>{it.label}</span>
          </button>
        );
      })}
    </div>
  );
}

function EdMReaderScreen({ article, fontSize, onBack, topInset = 56 }) {
  const ED_C = React.useContext(EdThemeContext);
  const feed = FEED_BY_ID[article.feed];
  return (
    <div style={{ height: '100%', display: 'flex', flexDirection: 'column', background: ED_C.bg }}>
      {/* sticky reader header */}
      <div style={{
        paddingTop: topInset, padding: `${topInset}px 16px 12px`,
        display: 'flex', alignItems: 'center', justifyContent: 'space-between',
        background: ED_C.bg, borderBottom: `1px solid ${ED_C.border}`,
        fontFamily: edUiFont,
      }}>
        <button onClick={onBack} style={{
          all: 'unset', cursor: 'pointer', display: 'flex', alignItems: 'center', gap: 4,
          color: ED_C.accent, fontSize: 14, padding: '4px 6px',
        }}>← {feed.name}</button>
        <div style={{ display: 'flex', gap: 4 }}>
          <button style={edMIconBtn(ED_C)}>Aa</button>
          <button style={edMIconBtn(ED_C)}>★</button>
          <button style={edMIconBtn(ED_C)}>⎙</button>
        </div>
      </div>

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
        <div style={{ fontSize, lineHeight: 1.65 }}>
          {ARTICLE_BODY.map((p, i) => (
            <p key={i} style={{ margin: '0 0 1.1em', textWrap: 'pretty' }}>{p}</p>
          ))}
        </div>
        <div style={{
          marginTop: 28, paddingTop: 18, borderTop: `1px solid ${ED_C.border}`,
          fontFamily: edUiFont, fontSize: 11, color: ED_C.ink3,
          display: 'flex', justifyContent: 'space-between',
        }}>
          <span>End of article</span>
          <span>{feed.url}</span>
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

function EdMSubsScreen({ topInset = 56 }) {
  const ED_C = React.useContext(EdThemeContext);
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
            <input placeholder="Search or paste a URL…"
              style={{ all: 'unset', flex: 1, fontSize: 13, color: ED_C.ink, fontFamily: edUiFont }} />
          </div>
        </div>

        {[...new Set(FEEDS.map(f => f.folder))].map(folder => (
          <div key={folder}>
            <div style={{
              padding: '14px 22px 6px', fontSize: 10, letterSpacing: '.1em', textTransform: 'uppercase',
              color: ED_C.ink3, fontFamily: edUiFont,
            }}>{folder}</div>
            {FEEDS.filter(f => f.folder === folder).map((f, i, arr) => (
              <div key={f.id} style={{
                display: 'flex', alignItems: 'center', gap: 14,
                padding: '12px 22px', background: ED_C.bg,
                borderBottom: i < arr.length - 1 ? `1px solid ${ED_C.border}` : `1px solid ${ED_C.border}`,
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
        ))}
      </div>
    </div>
  );
}

function EdMSettingsScreen({ topInset = 56 }) {
  const ED_C = React.useContext(EdThemeContext);
  const Row = ({ label, value, last = false }) => (
    <div style={{
      display: 'flex', alignItems: 'center', justifyContent: 'space-between',
      padding: '14px 22px', background: ED_C.bg,
      borderBottom: last ? 'none' : `1px solid ${ED_C.border}`,
      fontFamily: edUiFont,
    }}>
      <span style={{ fontSize: 14, color: ED_C.ink }}>{label}</span>
      <span style={{ fontSize: 13, color: ED_C.ink3, display: 'flex', alignItems: 'center', gap: 4 }}>
        {value} <span style={{ color: ED_C.ink4 }}>›</span>
      </span>
    </div>
  );
  const Group = ({ title, children }) => (
    <div>
      <div style={{
        padding: '14px 22px 6px', fontSize: 10, letterSpacing: '.1em', textTransform: 'uppercase',
        color: ED_C.ink3, fontFamily: edUiFont,
      }}>{title}</div>
      {children}
    </div>
  );
  return (
    <div style={{ height: '100%', display: 'flex', flexDirection: 'column' }}>
      <EdMHeader title="Settings" subtitle="Personal · this device" topInset={topInset} />
      <div style={{ flex: 1, overflow: 'auto', paddingBottom: 100, background: ED_C.bg }}>
        <Group title="Reading">
          <Row label="Mark as read on scroll" value="On" />
          <Row label="Reader theme" value="Paper" />
          <Row label="Default sort" value="Newest" last />
        </Group>
        <Group title="Sync">
          <Row label="Refresh interval" value="1h" />
          <Row label="Keep articles" value="90 days" last />
        </Group>
        <Group title="Account">
          <Row label="Import OPML" value="Choose…" />
          <Row label="About Margin" value="v1.0.0" last />
        </Group>
      </div>
    </div>
  );
}

function EditorialMobilePrototype({ density = 'regular', viewMode = 'list', fontSize = 17, theme = 'terracotta', topInset = 56 }) {
  const ED_C = ED_PALETTES[theme] || ED_PALETTES.terracotta;
  const [tab, setTab] = React.useState('today');           // today | saved | subs | settings
  const [openId, setOpenId] = React.useState(null);

  let articles, title, subtitle;
  if (tab === 'today') {
    articles = ARTICLES;
    title = 'Today';
    subtitle = `${ARTICLES.filter(a => a.unread).length} unread · ${ARTICLES.length} total`;
  } else if (tab === 'saved') {
    articles = ARTICLES.filter(a => a.starred);
    title = 'Saved';
    subtitle = `${articles.length} pieces in your library`;
  }
  const article = ARTICLES.find(a => a.id === openId);

  return (
    <EdThemeContext.Provider value={ED_C}>
    <div style={{ position: 'relative', width: '100%', height: '100%', background: ED_C.bg }}>
      {article ? (
        <EdMReaderScreen article={article} fontSize={fontSize} onBack={() => setOpenId(null)} topInset={topInset} />
      ) : tab === 'subs' ? (
        <EdMSubsScreen topInset={topInset} />
      ) : tab === 'settings' ? (
        <EdMSettingsScreen topInset={topInset} />
      ) : (
        <div style={{ height: '100%', display: 'flex', flexDirection: 'column' }}>
          <EdMHeader title={title} subtitle={subtitle} topInset={topInset} />
          {/* filter chips */}
          <div style={{
            display: 'flex', gap: 6, padding: '12px 22px',
            overflowX: 'auto', background: ED_C.bg, borderBottom: `1px solid ${ED_C.border}`,
          }}>
            {['All', 'Unread', 'Long reads', 'Short reads', 'Today'].map((c, i) => (
              <div key={c} style={{
                padding: '6px 12px', borderRadius: 99, fontSize: 12, whiteSpace: 'nowrap',
                background: i === 0 ? ED_C.ink : ED_C.panel,
                color: i === 0 ? ED_C.panel : ED_C.ink2,
                border: `1px solid ${i === 0 ? ED_C.ink : ED_C.border}`,
                fontFamily: edUiFont,
              }}>{c}</div>
            ))}
          </div>
          <div style={{ flex: 1, overflow: 'auto', paddingBottom: 100 }}>
            {articles.map(a => (
              <EdMArticleCard key={a.id} a={a} onOpen={setOpenId} density={density} viewMode={viewMode} />
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

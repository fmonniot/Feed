// Vibe 2 — Terminal / Power-user
// Dense, mono, keyboard-first. Near-black bg, off-white text, amber + cyan accents.

const TM_C = {
  bg: '#0e1014',
  panel: '#161922',
  panel2: '#1c2030',
  border: '#262a37',
  borderStrong: '#34394b',
  ink: '#d8dbe6',
  ink2: '#9aa0b2',
  ink3: '#5e6478',
  ink4: '#3d4256',
  amber: '#e8b75c',
  cyan: '#6cc9c0',
  magenta: '#cf7eb1',
  green: '#7fc086',
  red: '#d96b6b',
};

const tmFont = '"JetBrains Mono", "IBM Plex Mono", ui-monospace, SFMono-Regular, Menlo, monospace';

function tmTs(h) {
  if (h < 1) return ' <1h';
  if (h < 24) return `${String(h).padStart(2, ' ')}h `;
  const d = Math.floor(h / 24);
  return `${String(d).padStart(2, ' ')}d `;
}

function TmSidebar({ screen, setScreen, selectedFeed, setSelectedFeed }) {
  const folders = [...new Set(FEEDS.map(f => f.folder))];
  const Btn = ({ id, kbd, label, count, active, onClick }) => (
    <button onClick={onClick}
      style={{
        all: 'unset', display: 'flex', alignItems: 'center', gap: 8,
        padding: '4px 10px', cursor: 'pointer', fontSize: 12,
        color: active ? TM_C.amber : TM_C.ink2,
        background: active ? 'rgba(232,183,92,.07)' : 'transparent',
        borderLeft: active ? `2px solid ${TM_C.amber}` : '2px solid transparent',
      }}>
      <span style={{ width: 16, color: TM_C.ink4, fontSize: 10 }}>{kbd}</span>
      <span style={{ flex: 1 }}>{label}</span>
      {count != null ? <span style={{ color: TM_C.ink3, fontSize: 11 }}>{count}</span> : null}
    </button>
  );
  return (
    <div style={{
      width: 240, flex: '0 0 240px', height: '100%',
      background: TM_C.panel, borderRight: `1px solid ${TM_C.border}`,
      display: 'flex', flexDirection: 'column', fontFamily: tmFont, color: TM_C.ink,
    }}>
      {/* title bar */}
      <div style={{ padding: '14px 14px 10px', display: 'flex', alignItems: 'center', gap: 10 }}>
        <div style={{ display: 'flex', gap: 5 }}>
          <span style={{ width: 8, height: 8, borderRadius: '50%', background: TM_C.amber }} />
          <span style={{ width: 8, height: 8, borderRadius: '50%', background: TM_C.cyan }} />
          <span style={{ width: 8, height: 8, borderRadius: '50%', background: TM_C.magenta }} />
        </div>
        <div style={{ fontSize: 11, color: TM_C.ink3, letterSpacing: '.04em' }}>~/rss</div>
      </div>

      <div style={{ padding: '6px 0 4px' }}>
        <Btn kbd="1" label="inbox"     count={ARTICLES.filter(a => a.unread).length}
          active={screen === 'feed' && !selectedFeed}
          onClick={() => { setScreen('feed'); setSelectedFeed(null); }} />
        <Btn kbd="2" label="starred"   count={ARTICLES.filter(a => a.starred).length}
          active={screen === 'starred'}
          onClick={() => { setScreen('starred'); setSelectedFeed(null); }} />
        <Btn kbd="3" label="all"       count={ARTICLES.length}
          active={screen === 'all'}
          onClick={() => { setScreen('all'); setSelectedFeed(null); }} />
        <Btn kbd="g" label="subs"      active={screen === 'subs'}
          onClick={() => { setScreen('subs'); setSelectedFeed(null); }} />
        <Btn kbd="," label="settings"  active={screen === 'settings'}
          onClick={() => { setScreen('settings'); setSelectedFeed(null); }} />
      </div>

      <div style={{ borderTop: `1px solid ${TM_C.border}`, marginTop: 12, padding: '12px 14px 6px',
        fontSize: 10, letterSpacing: '.1em', textTransform: 'uppercase', color: TM_C.ink4 }}>
        feeds
      </div>

      <div style={{ flex: 1, overflow: 'auto', padding: '0 0 12px' }}>
        {folders.map(folder => (
          <div key={folder}>
            <div style={{ padding: '6px 14px 2px', fontSize: 10, color: TM_C.ink4 }}>
              ▾ {folder.toLowerCase()}
            </div>
            {FEEDS.filter(f => f.folder === folder).map(f => {
              const active = selectedFeed === f.id;
              return (
                <button key={f.id}
                  onClick={() => { setSelectedFeed(f.id); setScreen('feed'); }}
                  style={{
                    all: 'unset', display: 'flex', alignItems: 'center', gap: 6,
                    padding: '3px 14px 3px 26px', cursor: 'pointer', fontSize: 11.5,
                    color: active ? TM_C.amber : TM_C.ink2, width: 'calc(100% - 40px)',
                    background: active ? 'rgba(232,183,92,.07)' : 'transparent',
                  }}>
                  <span style={{ color: `oklch(0.7 0.12 ${f.hue})`, fontSize: 10 }}>●</span>
                  <span style={{ flex: 1, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{f.name.toLowerCase().replace(/ /g, '-')}</span>
                  {f.unread > 0 ? <span style={{ color: TM_C.ink3, fontSize: 10 }}>{f.unread}</span> : null}
                </button>
              );
            })}
          </div>
        ))}
      </div>

      {/* status bar */}
      <div style={{
        padding: '6px 14px', borderTop: `1px solid ${TM_C.border}`,
        fontSize: 10, color: TM_C.ink3, display: 'flex', justifyContent: 'space-between',
        background: TM_C.panel2,
      }}>
        <span><span style={{ color: TM_C.green }}>●</span> sync</span>
        <span>02:14</span>
      </div>
    </div>
  );
}

function TmCommandBar({ count }) {
  return (
    <div style={{
      height: 32, background: TM_C.panel2, borderBottom: `1px solid ${TM_C.border}`,
      display: 'flex', alignItems: 'center', padding: '0 12px', gap: 16, fontSize: 11,
      color: TM_C.ink3, fontFamily: tmFont,
    }}>
      <span style={{ color: TM_C.amber }}>$</span>
      <span style={{ color: TM_C.ink }}>rss --inbox</span>
      <span style={{ color: TM_C.ink4 }}>│</span>
      <span>{count} items</span>
      <span style={{ color: TM_C.ink4 }}>│</span>
      <span>sort:newest</span>
      <span style={{ color: TM_C.ink4 }}>│</span>
      <span style={{ marginLeft: 'auto' }}>
        <span style={{ color: TM_C.ink2 }}>j/k</span> nav <span style={{ marginLeft: 10, color: TM_C.ink2 }}>↵</span> open <span style={{ marginLeft: 10, color: TM_C.ink2 }}>s</span> star <span style={{ marginLeft: 10, color: TM_C.ink2 }}>?</span> help
      </span>
    </div>
  );
}

function TmArticleList({ articles, selectedId, setSelectedId, density, viewMode }) {
  const rowH = density === 'compact' ? 22 : density === 'comfy' ? 34 : 26;
  return (
    <div style={{
      width: 520, flex: '0 0 520px', height: '100%', overflow: 'auto',
      borderRight: `1px solid ${TM_C.border}`, background: TM_C.bg,
      fontFamily: tmFont, color: TM_C.ink, fontSize: 12,
    }}>
      {/* header row */}
      <div style={{
        display: 'flex', padding: '6px 12px', fontSize: 10,
        color: TM_C.ink4, borderBottom: `1px solid ${TM_C.border}`,
        position: 'sticky', top: 0, background: TM_C.bg, zIndex: 2,
        textTransform: 'uppercase', letterSpacing: '.08em',
      }}>
        <span style={{ width: 24 }}>·</span>
        <span style={{ width: 90 }}>feed</span>
        <span style={{ flex: 1 }}>title</span>
        <span style={{ width: 36, textAlign: 'right' }}>min</span>
        <span style={{ width: 44, textAlign: 'right' }}>age</span>
      </div>

      {articles.map((a, i) => {
        const feed = FEED_BY_ID[a.feed];
        const isSelected = selectedId === a.id;
        if (viewMode === 'card') {
          return (
            <button key={a.id} onClick={() => setSelectedId(a.id)}
              style={{
                all: 'unset', cursor: 'pointer', display: 'block',
                padding: '10px 12px', borderBottom: `1px solid ${TM_C.border}`,
                background: isSelected ? TM_C.panel2 : 'transparent',
                borderLeft: isSelected ? `2px solid ${TM_C.amber}` : '2px solid transparent',
              }}>
              <div style={{ display: 'flex', gap: 10, fontSize: 10, color: TM_C.ink3, marginBottom: 4 }}>
                <span style={{ color: `oklch(0.7 0.12 ${feed.hue})` }}>●</span>
                <span>{feed.name.toLowerCase()}</span>
                <span style={{ marginLeft: 'auto' }}>{tmTs(a.ts)} · {a.minutes}m</span>
              </div>
              <div style={{ color: a.unread ? TM_C.ink : TM_C.ink2, fontSize: 12.5, lineHeight: 1.35, marginBottom: 4 }}>
                {a.starred ? <span style={{ color: TM_C.amber, marginRight: 6 }}>★</span> : null}
                {a.title}
              </div>
              <div style={{ color: TM_C.ink3, fontSize: 11, lineHeight: 1.4,
                display: '-webkit-box', WebkitLineClamp: 2, WebkitBoxOrient: 'vertical', overflow: 'hidden' }}>{a.excerpt}</div>
            </button>
          );
        }
        return (
          <button key={a.id} onClick={() => setSelectedId(a.id)}
            style={{
              all: 'unset', cursor: 'pointer', display: 'flex', alignItems: 'center',
              height: rowH, padding: '0 12px', fontSize: density === 'compact' ? 11.5 : 12,
              background: isSelected ? TM_C.panel2 : 'transparent',
              color: isSelected ? TM_C.ink : (a.unread ? TM_C.ink : TM_C.ink2),
              borderLeft: isSelected ? `2px solid ${TM_C.amber}` : '2px solid transparent',
            }}>
            <span style={{ width: 24, color: a.starred ? TM_C.amber : (a.unread ? TM_C.cyan : TM_C.ink4) }}>
              {a.starred ? '★' : a.unread ? '●' : '·'}
            </span>
            <span style={{ width: 90, color: `oklch(0.7 0.12 ${feed.hue})`, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
              {feed.name.toLowerCase().split(' ')[0]}
            </span>
            <span style={{ flex: 1, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{a.title}</span>
            <span style={{ width: 36, textAlign: 'right', color: TM_C.ink3 }}>{a.minutes}m</span>
            <span style={{ width: 44, textAlign: 'right', color: TM_C.ink3, fontVariantNumeric: 'tabular-nums' }}>{tmTs(a.ts).trim()}</span>
          </button>
        );
      })}
    </div>
  );
}

function TmReader({ article, fontSize }) {
  if (!article) {
    return (
      <div style={{
        flex: 1, height: '100%', display: 'flex', alignItems: 'center', justifyContent: 'center',
        background: TM_C.bg, color: TM_C.ink4, fontFamily: tmFont, fontSize: 12, padding: 40,
      }}>
        <div style={{ textAlign: 'center' }}>
          <div style={{ color: TM_C.ink3, marginBottom: 12 }}>{`{ no article selected }`}</div>
          <div style={{ fontSize: 11 }}>press <span style={{ color: TM_C.amber }}>j</span> or <span style={{ color: TM_C.amber }}>↵</span> to open the first item</div>
        </div>
      </div>
    );
  }
  const feed = FEED_BY_ID[article.feed];
  return (
    <div style={{ flex: 1, height: '100%', overflow: 'auto', background: TM_C.bg, fontFamily: tmFont, color: TM_C.ink }}>
      {/* breadcrumb */}
      <div style={{
        display: 'flex', alignItems: 'center', padding: '8px 18px',
        fontSize: 11, color: TM_C.ink3, borderBottom: `1px solid ${TM_C.border}`,
        position: 'sticky', top: 0, background: TM_C.bg, zIndex: 2, gap: 8,
      }}>
        <span style={{ color: TM_C.amber }}>$</span>
        <span>cat</span>
        <span style={{ color: `oklch(0.7 0.12 ${feed.hue})` }}>{feed.url}/</span>
        <span style={{ color: TM_C.ink }}>{article.id}</span>
        <span style={{ marginLeft: 'auto', color: TM_C.ink4 }}>{tmTs(article.ts).trim()} · {article.minutes}m</span>
      </div>

      <div style={{ maxWidth: 640, margin: '0 auto', padding: '32px 36px 60px' }}>
        <div style={{ fontSize: 10, color: TM_C.ink3, marginBottom: 14, letterSpacing: '.08em' }}>
          ── {feed.name.toUpperCase()} ── {feed.author} ── {tmTs(article.ts).trim()}
        </div>

        <h1 style={{ fontSize: 22, fontWeight: 600, lineHeight: 1.25, margin: '0 0 16px', color: TM_C.ink }}>
          {article.title}
        </h1>

        <div style={{ fontSize: 12.5, color: TM_C.ink2, fontStyle: 'italic', marginBottom: 28, lineHeight: 1.5 }}>
          &gt; {article.excerpt}
        </div>

        <div style={{ display: 'flex', gap: 6, marginBottom: 28, flexWrap: 'wrap' }}>
          {['[s] star', '[a] archive', '[o] open', '[c] copy-url', '[/] search'].map(b => (
            <span key={b} style={{
              padding: '3px 8px', fontSize: 10.5, color: TM_C.ink2,
              border: `1px solid ${TM_C.border}`, background: TM_C.panel,
            }}>{b}</span>
          ))}
        </div>

        <div style={{ fontSize, lineHeight: 1.6, color: TM_C.ink }}>
          {ARTICLE_BODY.map((p, i) => (
            <p key={i} style={{ margin: '0 0 1.1em', textWrap: 'pretty' }}>{p}</p>
          ))}
        </div>

        <div style={{ marginTop: 32, paddingTop: 16, borderTop: `1px dashed ${TM_C.border}`,
          fontSize: 10, color: TM_C.ink3, display: 'flex', justifyContent: 'space-between' }}>
          <span>── EOF ──</span>
          <span>{feed.url}</span>
        </div>
      </div>
    </div>
  );
}

function TmSubsScreen() {
  return (
    <div style={{ flex: 1, height: '100%', overflow: 'auto', background: TM_C.bg, fontFamily: tmFont, color: TM_C.ink }}>
      <div style={{ padding: '20px 24px 40px' }}>
        <div style={{ fontSize: 11, color: TM_C.ink3, marginBottom: 4 }}>$ rss subs --list</div>
        <div style={{ fontSize: 18, color: TM_C.ink, marginBottom: 24 }}>{FEEDS.length} subscriptions</div>

        <div style={{
          border: `1px solid ${TM_C.border}`, background: TM_C.panel2,
          padding: '6px 10px', display: 'flex', alignItems: 'center', gap: 8,
          fontSize: 11, marginBottom: 18,
        }}>
          <span style={{ color: TM_C.amber }}>›</span>
          <input placeholder="paste URL or search…" style={{ all: 'unset', flex: 1, color: TM_C.ink, fontFamily: tmFont, fontSize: 11 }} />
          <span style={{ color: TM_C.ink4 }}>↵ to add</span>
        </div>

        <div style={{ border: `1px solid ${TM_C.border}` }}>
          <div style={{
            display: 'flex', padding: '6px 12px', fontSize: 10, color: TM_C.ink4,
            background: TM_C.panel2, textTransform: 'uppercase', letterSpacing: '.08em',
            borderBottom: `1px solid ${TM_C.border}`,
          }}>
            <span style={{ width: 24 }}></span>
            <span style={{ width: 140 }}>name</span>
            <span style={{ flex: 1 }}>url</span>
            <span style={{ width: 80 }}>folder</span>
            <span style={{ width: 60, textAlign: 'right' }}>unread</span>
            <span style={{ width: 40, textAlign: 'right' }}>ok</span>
          </div>
          {FEEDS.map((f, i) => (
            <div key={f.id} style={{
              display: 'flex', alignItems: 'center', padding: '8px 12px', fontSize: 12,
              background: i % 2 ? 'transparent' : 'rgba(255,255,255,.01)',
              borderBottom: i < FEEDS.length - 1 ? `1px solid ${TM_C.border}` : 'none',
            }}>
              <span style={{ width: 24, color: `oklch(0.7 0.12 ${f.hue})` }}>●</span>
              <span style={{ width: 140, color: TM_C.ink }}>{f.name}</span>
              <span style={{ flex: 1, color: TM_C.ink3, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{f.url}</span>
              <span style={{ width: 80, color: TM_C.ink2 }}>{f.folder.toLowerCase()}</span>
              <span style={{ width: 60, textAlign: 'right', color: f.unread > 0 ? TM_C.cyan : TM_C.ink4 }}>{f.unread}</span>
              <span style={{ width: 40, textAlign: 'right', color: TM_C.green }}>200</span>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}

function TmSettings() {
  const Row = ({ k, v, hint }) => (
    <div style={{
      display: 'flex', alignItems: 'flex-start', padding: '10px 0',
      borderBottom: `1px dashed ${TM_C.border}`, fontSize: 12,
    }}>
      <span style={{ width: 200, color: TM_C.ink2 }}>{k}</span>
      <div style={{ flex: 1 }}>
        <span style={{ color: TM_C.amber }}>{v}</span>
        {hint ? <div style={{ fontSize: 10.5, color: TM_C.ink4, marginTop: 3 }}>{hint}</div> : null}
      </div>
    </div>
  );
  return (
    <div style={{ flex: 1, height: '100%', overflow: 'auto', background: TM_C.bg, fontFamily: tmFont, color: TM_C.ink }}>
      <div style={{ padding: '20px 24px 60px', maxWidth: 720 }}>
        <div style={{ fontSize: 11, color: TM_C.ink3, marginBottom: 4 }}>$ cat ~/.rssrc</div>
        <div style={{ fontSize: 18, color: TM_C.ink, marginBottom: 22 }}>config</div>

        <div style={{ fontSize: 10, color: TM_C.ink4, letterSpacing: '.1em', marginBottom: 6 }}># [reading]</div>
        <Row k="reader.font-size"   v="18px"        hint="overridden by tweaks panel" />
        <Row k="reader.theme"       v="dim"         hint="paper | soft | dim" />
        <Row k="reader.mark-read"   v="on-scroll" />
        <Row k="reader.sort"        v="newest" />

        <div style={{ fontSize: 10, color: TM_C.ink4, letterSpacing: '.1em', margin: '20px 0 6px' }}># [sync]</div>
        <Row k="sync.interval"      v="3600s"       hint="next refresh in 14m" />
        <Row k="sync.keep-days"     v="90" />
        <Row k="sync.concurrent"    v="4" />

        <div style={{ fontSize: 10, color: TM_C.ink4, letterSpacing: '.1em', margin: '20px 0 6px' }}># [keys]</div>
        <Row k="bind.next-item"     v="j" />
        <Row k="bind.prev-item"     v="k" />
        <Row k="bind.star"          v="s" />
        <Row k="bind.archive"       v="a" />
        <Row k="bind.open-external" v="o" />
        <Row k="bind.command-pal"   v="cmd+k" />
      </div>
    </div>
  );
}

function TerminalPrototype({ density = 'regular', viewMode = 'list', fontSize = 14 }) {
  const [screen, setScreen] = React.useState('feed');
  const [selectedFeed, setSelectedFeed] = React.useState(null);
  const [selectedId, setSelectedId] = React.useState('a02');

  let articles = ARTICLES;
  if (selectedFeed) articles = ARTICLES.filter(a => a.feed === selectedFeed);
  else if (screen === 'starred') articles = ARTICLES.filter(a => a.starred);
  else if (screen === 'feed') articles = ARTICLES.filter(a => a.unread);
  // 'all' uses ARTICLES

  const article = ARTICLES.find(a => a.id === selectedId);
  const isListView = screen === 'feed' || screen === 'starred' || screen === 'all' || selectedFeed;

  return (
    <div style={{ width: '100%', height: '100%', display: 'flex', flexDirection: 'column', background: TM_C.bg }}>
      <div style={{ display: 'flex', flex: 1, minHeight: 0 }}>
        <TmSidebar screen={screen} setScreen={setScreen}
          selectedFeed={selectedFeed} setSelectedFeed={setSelectedFeed} />

        {screen === 'subs' && !selectedFeed ? (
          <TmSubsScreen />
        ) : screen === 'settings' && !selectedFeed ? (
          <TmSettings />
        ) : (
          <div style={{ flex: 1, display: 'flex', flexDirection: 'column', minWidth: 0 }}>
            <TmCommandBar count={articles.length} />
            <div style={{ flex: 1, display: 'flex', minHeight: 0 }}>
              <TmArticleList articles={articles} selectedId={selectedId} setSelectedId={setSelectedId}
                density={density} viewMode={viewMode} />
              <TmReader article={article} fontSize={fontSize} />
            </div>
          </div>
        )}
      </div>
    </div>
  );
}

Object.assign(window, { TerminalPrototype });

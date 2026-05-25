// Vibe 3 — Magazine / Warm
// Newsreader serif + Work Sans, paper background, deep ink, ochre accent.
// Image-led, with a hero "today" card.

const MG_C = {
  bg: '#f1e9d6',
  paper: '#faf4e3',
  paperDeep: '#ede4cd',
  ink: '#1f1a12',
  ink2: '#4d4537',
  ink3: '#8a7e62',
  ink4: '#b8ad8e',
  rule: 'rgba(31,26,18,.14)',
  ruleSoft: 'rgba(31,26,18,.08)',
  accent: '#9c5a1f',     // burnt sienna / ochre
  accentSoft: 'rgba(156,90,31,.10)',
  navy: '#23364e',
};

const mgUiFont = '"Work Sans", ui-sans-serif, system-ui, sans-serif';
const mgSerifFont = '"Newsreader", "Source Serif 4", "Iowan Old Style", Georgia, serif';

function mgTimeAgo(h) {
  if (h < 24) return `${h}h ago`;
  const d = Math.floor(h / 24);
  if (d < 7) return `${d}d ago`;
  return `${Math.floor(d / 7)}w ago`;
}

function MgCover({ hue, w, h, kind = 'stripe' }) {
  // image placeholders with feed-tinted palette
  const a = `oklch(0.78 0.08 ${hue})`;
  const b = `oklch(0.55 0.10 ${hue})`;
  const c = `oklch(0.32 0.06 ${hue})`;
  const styles = {
    stripe: { background: `repeating-linear-gradient(115deg, ${a} 0 14px, ${b} 14px 28px)` },
    block:  { background: `linear-gradient(135deg, ${b} 0%, ${a} 60%, ${c} 100%)` },
    grid:   { background: `
      linear-gradient(${a} 1px, transparent 1px) 0 0 / 18px 18px,
      linear-gradient(90deg, ${a} 1px, transparent 1px) 0 0 / 18px 18px,
      ${b}` },
    dots:   { background: `radial-gradient(${c} 1.5px, transparent 2px) 0 0 / 12px 12px, ${a}` },
  };
  return (
    <div style={{
      width: w, height: h, ...styles[kind],
      position: 'relative', overflow: 'hidden', flex: '0 0 auto',
    }}>
      <div style={{
        position: 'absolute', bottom: 6, left: 8,
        fontFamily: 'ui-monospace, monospace', fontSize: 9, letterSpacing: '.1em',
        color: 'rgba(255,255,255,.7)', textTransform: 'uppercase',
        background: 'rgba(0,0,0,.2)', padding: '2px 6px', borderRadius: 1,
      }}>cover</div>
    </div>
  );
}

const COVER_KINDS = ['stripe', 'block', 'grid', 'dots'];

function MgSidebar({ screen, setScreen, selectedFeed, setSelectedFeed }) {
  const folders = [...new Set(FEEDS.map(f => f.folder))];
  const NavItem = ({ id, label, icon, active, onClick }) => (
    <button onClick={onClick} style={{
      all: 'unset', cursor: 'pointer', display: 'flex', alignItems: 'center', gap: 12,
      padding: '8px 16px', fontSize: 13, fontWeight: active ? 600 : 500,
      color: active ? MG_C.accent : MG_C.ink2,
      background: active ? MG_C.accentSoft : 'transparent',
      borderRadius: 0,
    }}>
      <span style={{ fontFamily: mgSerifFont, fontStyle: 'italic', fontSize: 13, width: 18, color: active ? MG_C.accent : MG_C.ink3 }}>{icon}</span>
      <span>{label}</span>
    </button>
  );
  return (
    <div style={{
      width: 200, flex: '0 0 200px', height: '100%',
      background: MG_C.paper, borderRight: `1px solid ${MG_C.rule}`,
      display: 'flex', flexDirection: 'column', fontFamily: mgUiFont, color: MG_C.ink,
    }}>
      <div style={{ padding: '22px 20px 16px' }}>
        <div style={{ fontFamily: mgSerifFont, fontSize: 22, fontWeight: 500, letterSpacing: '-.02em', lineHeight: 1 }}>
          <span style={{ color: MG_C.accent, fontStyle: 'italic' }}>The</span>
          <br />
          Slow Reader
        </div>
        <div style={{ fontSize: 10, color: MG_C.ink3, letterSpacing: '.15em', textTransform: 'uppercase', marginTop: 8 }}>
          № 412 · May 26
        </div>
      </div>

      <div style={{ display: 'flex', flexDirection: 'column' }}>
        <NavItem icon="i" label="Today"        active={screen === 'feed' && !selectedFeed}
          onClick={() => { setScreen('feed'); setSelectedFeed(null); }} />
        <NavItem icon="★" label="Saved"        active={screen === 'starred'}
          onClick={() => { setScreen('starred'); setSelectedFeed(null); }} />
        <NavItem icon="ƒ" label="Subscriptions" active={screen === 'subs'}
          onClick={() => { setScreen('subs'); setSelectedFeed(null); }} />
        <NavItem icon="◌" label="Settings"     active={screen === 'settings'}
          onClick={() => { setScreen('settings'); setSelectedFeed(null); }} />
      </div>

      <div style={{ height: 1, background: MG_C.rule, margin: '14px 20px' }} />

      <div style={{ padding: '0 0 12px', flex: 1, overflow: 'auto' }}>
        {folders.map(folder => (
          <div key={folder} style={{ marginBottom: 10 }}>
            <div style={{
              fontFamily: mgSerifFont, fontStyle: 'italic', fontSize: 12,
              padding: '6px 20px 2px', color: MG_C.ink3,
            }}>{folder}</div>
            {FEEDS.filter(f => f.folder === folder).map(f => {
              const active = selectedFeed === f.id;
              return (
                <button key={f.id}
                  onClick={() => { setSelectedFeed(f.id); setScreen('feed'); }}
                  style={{
                    all: 'unset', display: 'flex', alignItems: 'center', gap: 10,
                    padding: '4px 20px', cursor: 'pointer', fontSize: 12,
                    color: active ? MG_C.accent : MG_C.ink2, width: 'calc(100% - 40px)',
                    background: active ? MG_C.accentSoft : 'transparent',
                  }}>
                  <span style={{ width: 8, height: 8, borderRadius: '50%', background: `oklch(0.65 0.13 ${f.hue})`, flex: '0 0 auto' }} />
                  <span style={{ flex: 1, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{f.name}</span>
                  {f.unread > 0 ? <span style={{ fontSize: 10, color: MG_C.ink3 }}>{f.unread}</span> : null}
                </button>
              );
            })}
          </div>
        ))}
      </div>
    </div>
  );
}

function MgFeedScreen({ articles, setSelectedId, density, viewMode, title, kicker }) {
  const [hero, ...rest] = articles;
  if (!hero) {
    return (
      <div style={{ flex: 1, padding: 40, color: MG_C.ink3, fontFamily: mgSerifFont, fontStyle: 'italic' }}>
        Nothing here yet.
      </div>
    );
  }
  const heroFeed = FEED_BY_ID[hero.feed];
  const cols = viewMode === 'card' ? 2 : 1;
  const cardPad = density === 'compact' ? 12 : density === 'comfy' ? 22 : 16;

  return (
    <div style={{ flex: 1, height: '100%', overflow: 'auto', background: MG_C.bg, fontFamily: mgUiFont, color: MG_C.ink }}>
      <div style={{ maxWidth: 980, margin: '0 auto', padding: '36px 40px 60px' }}>
        {/* masthead */}
        <div style={{ display: 'flex', alignItems: 'baseline', justifyContent: 'space-between',
          paddingBottom: 14, borderBottom: `2px solid ${MG_C.ink}`, marginBottom: 28 }}>
          <div>
            <div style={{ fontSize: 10, letterSpacing: '.2em', textTransform: 'uppercase', color: MG_C.ink3 }}>
              {kicker}
            </div>
            <h1 style={{ fontFamily: mgSerifFont, fontSize: 36, fontWeight: 500, letterSpacing: '-.02em',
              margin: '4px 0 0', lineHeight: 1.05 }}>
              <span style={{ fontStyle: 'italic', color: MG_C.accent }}>{title.split(' ')[0]} </span>
              {title.split(' ').slice(1).join(' ')}
            </h1>
          </div>
          <div style={{ textAlign: 'right', fontSize: 11, color: MG_C.ink3, fontFamily: mgSerifFont, fontStyle: 'italic' }}>
            <div>Tuesday, May 26</div>
            <div>{articles.length} pieces · {articles.reduce((a,x)=>a+x.minutes,0)} min</div>
          </div>
        </div>

        {/* hero */}
        <button onClick={() => setSelectedId(hero.id)} style={{
          all: 'unset', cursor: 'pointer', display: 'grid',
          gridTemplateColumns: '1.2fr 1fr', gap: 28, marginBottom: 36,
          paddingBottom: 36, borderBottom: `1px solid ${MG_C.rule}`,
        }}>
          <MgCover hue={heroFeed.hue} w="100%" h={260} kind="block" />
          <div>
            <div style={{ fontSize: 10, letterSpacing: '.15em', textTransform: 'uppercase', color: MG_C.accent, marginBottom: 8 }}>
              Featured · {heroFeed.tag}
            </div>
            <h2 style={{ fontFamily: mgSerifFont, fontSize: 32, fontWeight: 500, letterSpacing: '-.02em',
              lineHeight: 1.1, margin: '0 0 14px' }}>{hero.title}</h2>
            <p style={{ fontFamily: mgSerifFont, fontSize: 16, lineHeight: 1.5, color: MG_C.ink2,
              margin: '0 0 18px' }}>{hero.excerpt}</p>
            <div style={{ display: 'flex', alignItems: 'center', gap: 10, fontSize: 12, color: MG_C.ink3 }}>
              <span style={{ fontWeight: 600, color: MG_C.ink2 }}>{heroFeed.name}</span>
              <span>·</span>
              <span>{heroFeed.author}</span>
              <span>·</span>
              <span>{mgTimeAgo(hero.ts)}</span>
              <span>·</span>
              <span>{hero.minutes} min</span>
            </div>
          </div>
        </button>

        {/* grid of rest */}
        <div style={{
          display: 'grid', gridTemplateColumns: `repeat(${cols}, 1fr)`, gap: 28,
        }}>
          {rest.map(a => {
            const f = FEED_BY_ID[a.feed];
            const kind = COVER_KINDS[parseInt(a.id.slice(1)) % COVER_KINDS.length];
            return (
              <button key={a.id} onClick={() => setSelectedId(a.id)} style={{
                all: 'unset', cursor: 'pointer', display: 'flex', flexDirection: 'column',
                gap: 12, padding: cardPad, background: MG_C.paper,
                border: `1px solid ${MG_C.rule}`,
              }}>
                {viewMode === 'card' && <MgCover hue={f.hue} w="100%" h={130} kind={kind} />}
                <div style={{ display: 'flex', alignItems: 'center', gap: 8,
                  fontSize: 10, letterSpacing: '.12em', textTransform: 'uppercase', color: MG_C.ink3 }}>
                  <span style={{ width: 6, height: 6, borderRadius: '50%', background: `oklch(0.65 0.13 ${f.hue})` }} />
                  <span style={{ fontWeight: 600 }}>{f.name}</span>
                  <span style={{ marginLeft: 'auto', letterSpacing: 0, textTransform: 'none' }}>{mgTimeAgo(a.ts)}</span>
                </div>
                <h3 style={{ fontFamily: mgSerifFont, fontSize: cols === 1 ? 22 : 18, fontWeight: 500,
                  letterSpacing: '-.015em', lineHeight: 1.2, margin: 0,
                  display: '-webkit-box', WebkitLineClamp: 3, WebkitBoxOrient: 'vertical', overflow: 'hidden' }}>
                  {a.title}
                </h3>
                {density !== 'compact' ? (
                  <p style={{ fontFamily: mgSerifFont, fontSize: 14, lineHeight: 1.5,
                    color: MG_C.ink2, margin: 0,
                    display: '-webkit-box', WebkitLineClamp: viewMode === 'card' ? 2 : 3, WebkitBoxOrient: 'vertical', overflow: 'hidden' }}>
                    {a.excerpt}
                  </p>
                ) : null}
                <div style={{ marginTop: 'auto', fontSize: 11, color: MG_C.ink3, fontVariantNumeric: 'tabular-nums' }}>
                  {a.minutes} min read {a.starred ? <span style={{ color: MG_C.accent, marginLeft: 8 }}>★ saved</span> : null}
                </div>
              </button>
            );
          })}
        </div>
      </div>
    </div>
  );
}

function MgReader({ article, fontSize, onBack }) {
  if (!article) return null;
  const feed = FEED_BY_ID[article.feed];
  return (
    <div style={{ flex: 1, height: '100%', overflow: 'auto', background: MG_C.bg, fontFamily: mgUiFont, color: MG_C.ink }}>
      <div style={{ maxWidth: 660, margin: '0 auto', padding: '32px 44px 80px' }}>
        <button onClick={onBack} style={{
          all: 'unset', cursor: 'pointer', display: 'inline-flex', alignItems: 'center', gap: 8,
          fontSize: 12, color: MG_C.ink3, marginBottom: 28, padding: '6px 0',
        }}>
          <span style={{ fontFamily: mgSerifFont, fontStyle: 'italic' }}>← back to today</span>
        </button>

        <div style={{ fontSize: 10, letterSpacing: '.2em', textTransform: 'uppercase', color: MG_C.accent, marginBottom: 12 }}>
          {feed.tag}
        </div>
        <h1 style={{ fontFamily: mgSerifFont, fontSize: 40, fontWeight: 500, letterSpacing: '-.02em',
          lineHeight: 1.08, margin: '0 0 18px' }}>
          {article.title}
        </h1>

        <p style={{ fontFamily: mgSerifFont, fontStyle: 'italic', fontSize: 19, lineHeight: 1.5,
          color: MG_C.ink2, margin: '0 0 28px' }}>{article.excerpt}</p>

        <div style={{ display: 'flex', alignItems: 'center', gap: 12, paddingBottom: 20, marginBottom: 28,
          borderBottom: `1px solid ${MG_C.rule}` }}>
          <div style={{ width: 32, height: 32, borderRadius: '50%',
            background: `oklch(0.75 0.10 ${feed.hue})`, color: 'white',
            display: 'flex', alignItems: 'center', justifyContent: 'center',
            fontFamily: mgSerifFont, fontWeight: 500, fontSize: 14 }}>{feed.author[0]}</div>
          <div style={{ flex: 1, fontSize: 12, color: MG_C.ink2 }}>
            <div style={{ fontWeight: 600 }}>{feed.author}</div>
            <div style={{ color: MG_C.ink3 }}>in {feed.name} · {mgTimeAgo(article.ts)} · {article.minutes} min read</div>
          </div>
          <button style={mgChipStyle}>★ Save</button>
          <button style={mgChipStyle}>↗ Share</button>
        </div>

        <MgCover hue={feed.hue} w="100%" h={220} kind="block" />
        <div style={{ fontSize: 11, color: MG_C.ink3, fontStyle: 'italic', fontFamily: mgSerifFont,
          marginTop: 8, marginBottom: 32 }}>
          Illustration by the author.
        </div>

        <div style={{ fontFamily: mgSerifFont, fontSize, lineHeight: 1.65, color: MG_C.ink }}>
          {ARTICLE_BODY.map((p, i) => (
            <p key={i} style={{
              margin: '0 0 1.1em', textWrap: 'pretty',
              ...(i === 0 ? { textIndent: 0 } : { textIndent: '1.4em' }),
            }}>
              {i === 0 ? (
                <React.Fragment>
                  <span style={{ float: 'left', fontSize: '3.3em', lineHeight: 0.85, fontWeight: 500,
                    paddingRight: 8, paddingTop: 4, color: MG_C.accent, fontFamily: mgSerifFont }}>
                    {p[0]}
                  </span>
                  {p.slice(1)}
                </React.Fragment>
              ) : p}
            </p>
          ))}
        </div>

        <div style={{ marginTop: 40, paddingTop: 24, borderTop: `2px solid ${MG_C.ink}`,
          fontSize: 12, color: MG_C.ink3, display: 'flex', justifyContent: 'space-between' }}>
          <span style={{ fontFamily: mgSerifFont, fontStyle: 'italic' }}>— Fin.</span>
          <span>{feed.url}</span>
        </div>
      </div>
    </div>
  );
}

const mgChipStyle = {
  all: 'unset', cursor: 'pointer', padding: '5px 11px', fontSize: 11,
  color: MG_C.ink2, border: `1px solid ${MG_C.rule}`, borderRadius: 99,
  background: MG_C.paper,
};

function MgSubsScreen() {
  return (
    <div style={{ flex: 1, height: '100%', overflow: 'auto', background: MG_C.bg, fontFamily: mgUiFont, color: MG_C.ink }}>
      <div style={{ maxWidth: 880, margin: '0 auto', padding: '36px 40px 60px' }}>
        <div style={{ display: 'flex', alignItems: 'baseline', justifyContent: 'space-between',
          paddingBottom: 14, borderBottom: `2px solid ${MG_C.ink}`, marginBottom: 28 }}>
          <div>
            <div style={{ fontSize: 10, letterSpacing: '.2em', textTransform: 'uppercase', color: MG_C.ink3 }}>The Library</div>
            <h1 style={{ fontFamily: mgSerifFont, fontSize: 36, fontWeight: 500, letterSpacing: '-.02em', margin: '4px 0 0' }}>
              <span style={{ fontStyle: 'italic', color: MG_C.accent }}>Your</span> subscriptions
            </h1>
          </div>
          <button style={{ ...mgChipStyle, background: MG_C.ink, color: MG_C.paper, border: 'none', padding: '10px 18px', fontSize: 12 }}>
            + Add a new feed
          </button>
        </div>

        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(2, 1fr)', gap: 16 }}>
          {FEEDS.map(f => (
            <div key={f.id} style={{
              display: 'flex', gap: 14, padding: 16, background: MG_C.paper,
              border: `1px solid ${MG_C.rule}`,
            }}>
              <MgCover hue={f.hue} w={72} h={92} kind={COVER_KINDS[f.hue % 4]} />
              <div style={{ flex: 1, minWidth: 0, display: 'flex', flexDirection: 'column' }}>
                <div style={{ fontSize: 10, letterSpacing: '.15em', textTransform: 'uppercase', color: MG_C.ink3 }}>{f.tag}</div>
                <div style={{ fontFamily: mgSerifFont, fontSize: 19, fontWeight: 500, letterSpacing: '-.01em', marginTop: 2 }}>{f.name}</div>
                <div style={{ fontFamily: mgSerifFont, fontStyle: 'italic', fontSize: 13, color: MG_C.ink2, marginTop: 2 }}>by {f.author}</div>
                <div style={{ fontSize: 11, color: MG_C.ink3, marginTop: 'auto', display: 'flex', justifyContent: 'space-between', paddingTop: 8 }}>
                  <span>{f.folder}</span>
                  <span>{f.unread} new</span>
                </div>
              </div>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}

function MgSettings() {
  const Row = ({ label, hint, children }) => (
    <div style={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between',
      gap: 24, padding: '20px 0', borderBottom: `1px solid ${MG_C.rule}` }}>
      <div style={{ maxWidth: 380 }}>
        <div style={{ fontFamily: mgSerifFont, fontSize: 17, fontWeight: 500 }}>{label}</div>
        {hint ? <div style={{ fontFamily: mgSerifFont, fontStyle: 'italic', fontSize: 13, color: MG_C.ink3, marginTop: 4, lineHeight: 1.45 }}>{hint}</div> : null}
      </div>
      <div>{children}</div>
    </div>
  );
  const Seg = ({ options, value }) => (
    <div style={{ display: 'flex', gap: 0, border: `1px solid ${MG_C.ink}`, borderRadius: 0 }}>
      {options.map(o => (
        <div key={o} style={{
          padding: '6px 12px', fontSize: 12, borderRight: o === options.at(-1) ? 'none' : `1px solid ${MG_C.ink}`,
          background: o === value ? MG_C.ink : 'transparent',
          color: o === value ? MG_C.paper : MG_C.ink,
          fontFamily: mgUiFont,
        }}>{o}</div>
      ))}
    </div>
  );
  return (
    <div style={{ flex: 1, height: '100%', overflow: 'auto', background: MG_C.bg, fontFamily: mgUiFont, color: MG_C.ink }}>
      <div style={{ maxWidth: 700, margin: '0 auto', padding: '36px 40px 60px' }}>
        <div style={{ paddingBottom: 14, borderBottom: `2px solid ${MG_C.ink}`, marginBottom: 28 }}>
          <div style={{ fontSize: 10, letterSpacing: '.2em', textTransform: 'uppercase', color: MG_C.ink3 }}>The Desk</div>
          <h1 style={{ fontFamily: mgSerifFont, fontSize: 36, fontWeight: 500, letterSpacing: '-.02em', margin: '4px 0 0' }}>
            <span style={{ fontStyle: 'italic', color: MG_C.accent }}>Editorial</span> preferences
          </h1>
        </div>

        <Row label="Reading rhythm" hint="How quickly should new pieces be marked as finished?">
          <Seg options={['Manually', 'On scroll', 'On open']} value="On scroll" />
        </Row>
        <Row label="Reader mood" hint="The paper colour beneath your reading.">
          <Seg options={['Cream', 'Linen', 'Slate']} value="Cream" />
        </Row>
        <Row label="Typography" hint="Choose the body face for the reader.">
          <Seg options={['Serif', 'Sans']} value="Serif" />
        </Row>
        <Row label="Refresh interval" hint="The Slow Reader is, by design, slow.">
          <Seg options={['Hourly', 'Mornings', 'Manual']} value="Mornings" />
        </Row>
        <Row label="Keep archive" hint="Articles older than this are gently composted.">
          <Seg options={['1 mo', '3 mo', '1 yr', 'Forever']} value="3 mo" />
        </Row>
        <Row label="Reading log" hint="Quietly note what you have finished.">
          <Seg options={['Off', 'On']} value="On" />
        </Row>
      </div>
    </div>
  );
}

function MagazinePrototype({ density = 'regular', viewMode = 'card', fontSize = 18 }) {
  const [screen, setScreen] = React.useState('feed');
  const [selectedFeed, setSelectedFeed] = React.useState(null);
  const [selectedId, setSelectedId] = React.useState(null);

  let articles = ARTICLES;
  let title = 'Today\u2019s edition', kicker = 'The Slow Reader · Daily';
  if (selectedFeed) {
    const f = FEED_BY_ID[selectedFeed];
    articles = ARTICLES.filter(a => a.feed === selectedFeed);
    title = f.name; kicker = `By ${f.author} · ${f.tag}`;
  } else if (screen === 'starred') {
    articles = ARTICLES.filter(a => a.starred);
    title = 'Saved for later'; kicker = `${articles.length} pieces in your library`;
  }
  const article = ARTICLES.find(a => a.id === selectedId);

  return (
    <div style={{ width: '100%', height: '100%', display: 'flex', background: MG_C.bg, fontFamily: mgUiFont }}>
      <MgSidebar screen={screen} setScreen={setScreen}
        selectedFeed={selectedFeed} setSelectedFeed={setSelectedFeed} />

      {screen === 'subs' && !selectedFeed ? (
        <MgSubsScreen />
      ) : screen === 'settings' && !selectedFeed ? (
        <MgSettings />
      ) : article ? (
        <MgReader article={article} fontSize={fontSize} onBack={() => setSelectedId(null)} />
      ) : (
        <MgFeedScreen articles={articles} setSelectedId={setSelectedId}
          density={density} viewMode={viewMode} title={title} kicker={kicker} />
      )}
    </div>
  );
}

Object.assign(window, { MagazinePrototype });

// Vibe D — Material 3
// Recreating the Material 3 design language: filled/tonal surfaces, large
// rounded corners, chips, FAB, navigation rail (desktop) + bottom bar (mobile).
// Purple baseline tonal palette.

const M3 = {
  primary:                '#6750A4',
  onPrimary:              '#FFFFFF',
  primaryContainer:       '#EADDFF',
  onPrimaryContainer:     '#21005D',

  secondary:              '#625B71',
  secondaryContainer:     '#E8DEF8',
  onSecondaryContainer:   '#1D192B',

  tertiary:               '#7D5260',
  tertiaryContainer:      '#FFD8E4',
  onTertiaryContainer:    '#370B1E',

  surface:                '#FEF7FF',
  surfaceDim:             '#DED8E1',
  surfaceContainerLowest: '#FFFFFF',
  surfaceContainerLow:    '#F7F2FA',
  surfaceContainer:       '#F3EDF7',
  surfaceContainerHigh:   '#ECE6F0',
  surfaceContainerHighest:'#E6E0E9',

  onSurface:              '#1D1B20',
  onSurfaceVariant:       '#49454F',
  outline:                '#79747E',
  outlineVariant:         '#CAC4D0',

  error:                  '#B3261E',
};

const m3Font = '"DM Sans", "Roboto Flex", ui-sans-serif, system-ui, sans-serif';

function m3Time(h) {
  if (h < 1)  return 'just now';
  if (h < 24) return `${h}h`;
  const d = Math.floor(h / 24);
  if (d < 7)  return `${d}d`;
  return `${Math.floor(d / 7)}w`;
}

// Stand-in "Material Symbols" — geometric inline SVGs so we don't need an
// icon font. Keep them simple (24×24, 1.5px stroke), like the outlined set.
function M3Icon({ name, size = 22, color = 'currentColor' }) {
  const s = { width: size, height: size, fill: 'none', stroke: color,
    strokeWidth: 1.6, strokeLinecap: 'round', strokeLinejoin: 'round' };
  const paths = {
    home:     <React.Fragment><path d="M4 11 12 4l8 7" /><path d="M6 10v9h12v-9" /></React.Fragment>,
    bookmark: <path d="M7 4h10v16l-5-4-5 4Z" />,
    feed:     <React.Fragment><circle cx="6" cy="18" r="1.5" /><path d="M4 10a10 10 0 0 1 10 10" /><path d="M4 5a15 15 0 0 1 15 15" /></React.Fragment>,
    settings: <React.Fragment><circle cx="12" cy="12" r="3" /><path d="M19.4 13.6a7.6 7.6 0 0 0 0-3.2l2-1.5-2-3.5-2.4.8a7.6 7.6 0 0 0-2.8-1.6L13.5 2h-3l-.7 2.6a7.6 7.6 0 0 0-2.8 1.6l-2.4-.8-2 3.5 2 1.5a7.6 7.6 0 0 0 0 3.2l-2 1.5 2 3.5 2.4-.8a7.6 7.6 0 0 0 2.8 1.6l.7 2.6h3l.7-2.6a7.6 7.6 0 0 0 2.8-1.6l2.4.8 2-3.5Z"/></React.Fragment>,
    search:   <React.Fragment><circle cx="11" cy="11" r="6" /><path d="m20 20-4.3-4.3" /></React.Fragment>,
    menu:     <React.Fragment><path d="M4 7h16" /><path d="M4 12h16" /><path d="M4 17h16" /></React.Fragment>,
    plus:     <React.Fragment><path d="M12 5v14" /><path d="M5 12h14" /></React.Fragment>,
    arrow_back:<React.Fragment><path d="m14 6-6 6 6 6" /><path d="M8 12h12" /></React.Fragment>,
    more:     <React.Fragment><circle cx="5" cy="12" r="1" /><circle cx="12" cy="12" r="1" /><circle cx="19" cy="12" r="1" /></React.Fragment>,
    refresh:  <React.Fragment><path d="M21 12a9 9 0 1 1-3-6.7" /><path d="M21 4v5h-5" /></React.Fragment>,
    star:     <path d="m12 4 2.6 5.4 5.9.6-4.4 4.1 1.2 5.9L12 17l-5.3 3 1.2-5.9-4.4-4.1 5.9-.6Z" />,
    star_fill:<path d="m12 4 2.6 5.4 5.9.6-4.4 4.1 1.2 5.9L12 17l-5.3 3 1.2-5.9-4.4-4.1 5.9-.6Z" fill={color} />,
    bookmark_fill: <path d="M7 4h10v16l-5-4-5 4Z" fill={color} />,
    inbox:    <React.Fragment><path d="M4 4h16v10h-5l-1 3h-4l-1-3H4Z" /><path d="M4 14v6h16v-6" /></React.Fragment>,
    text_fields: <React.Fragment><path d="M4 6h7" /><path d="M7.5 6v12" /><path d="M14 10h6" /><path d="M17 10v8" /></React.Fragment>,
    text_decrease: <React.Fragment><path d="M3 18 8 6l5 12" /><path d="M5.2 14h5.6" /><path d="M16 12h5" /></React.Fragment>,
    text_increase: <React.Fragment><path d="M3 18 8 6l5 12" /><path d="M5.2 14h5.6" /><path d="M16 12h5" /><path d="M18.5 9.5v5" /></React.Fragment>,
    share:    <React.Fragment><circle cx="6" cy="12" r="2" /><circle cx="18" cy="6" r="2" /><circle cx="18" cy="18" r="2" /><path d="m8 11 8-4" /><path d="m8 13 8 4" /></React.Fragment>,
  };
  return <svg viewBox="0 0 24 24" style={s}>{paths[name] || null}</svg>;
}

// ─────────────────────────────────────────────────────────────────────────
// Shared building blocks
// ─────────────────────────────────────────────────────────────────────────
function M3Chip({ label, selected, leading, onClick }) {
  return (
    <button onClick={onClick} style={{
      all: 'unset', cursor: 'pointer', display: 'inline-flex', alignItems: 'center', gap: 6,
      height: 32, padding: leading ? '0 14px 0 10px' : '0 14px',
      border: `1px solid ${selected ? M3.secondaryContainer : M3.outline}`,
      borderRadius: 8, background: selected ? M3.secondaryContainer : 'transparent',
      color: selected ? M3.onSecondaryContainer : M3.onSurfaceVariant,
      fontFamily: m3Font, fontSize: 13, fontWeight: 500, whiteSpace: 'nowrap',
    }}>
      {leading ? <M3Icon name={leading} size={16} /> : null}
      {label}
    </button>
  );
}

function M3FAB({ icon = 'plus', label, size = 'regular', extended = false }) {
  const dim = size === 'large' ? 96 : 56;
  return (
    <button style={{
      all: 'unset', cursor: 'pointer',
      height: dim, minWidth: extended ? undefined : dim, padding: extended ? '0 20px' : 0,
      borderRadius: extended ? 16 : 16,
      background: M3.primaryContainer, color: M3.onPrimaryContainer,
      display: 'inline-flex', alignItems: 'center', justifyContent: 'center', gap: 10,
      boxShadow: '0 3px 8px rgba(0,0,0,.15), 0 1px 3px rgba(0,0,0,.1)',
      fontFamily: m3Font, fontSize: 14, fontWeight: 600,
    }}>
      <M3Icon name={icon} size={size === 'large' ? 26 : 22} />
      {extended ? label : null}
    </button>
  );
}

function M3FeedDot({ hue, size = 12 }) {
  return <span style={{ width: size, height: size, borderRadius: '50%',
    background: `oklch(0.65 0.13 ${hue})`, flex: '0 0 auto' }} />;
}

// ─────────────────────────────────────────────────────────────────────────
// DESKTOP
// ─────────────────────────────────────────────────────────────────────────
function M3TopAppBar({ title, subtitle, large = false }) {
  return (
    <div style={{
      background: M3.surface, color: M3.onSurface, fontFamily: m3Font,
      padding: large ? '20px 24px 24px' : '12px 16px',
      display: 'flex', alignItems: large ? 'flex-end' : 'center', gap: 12,
      minHeight: large ? 100 : 64, borderBottom: `1px solid ${M3.outlineVariant}`,
    }}>
      <button style={m3IconBtnStyle}><M3Icon name="menu" /></button>
      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={{
          fontSize: large ? 28 : 18, fontWeight: 500, letterSpacing: '-.01em', lineHeight: 1.15,
        }}>{title}</div>
        {subtitle ? <div style={{ fontSize: 13, color: M3.onSurfaceVariant, marginTop: 2 }}>{subtitle}</div> : null}
      </div>
      <button style={m3IconBtnStyle}><M3Icon name="search" /></button>
      <button style={m3IconBtnStyle}><M3Icon name="refresh" /></button>
      <button style={m3IconBtnStyle}><M3Icon name="more" /></button>
    </div>
  );
}

const m3IconBtnStyle = {
  all: 'unset', cursor: 'pointer', width: 40, height: 40, borderRadius: 20,
  display: 'flex', alignItems: 'center', justifyContent: 'center',
  color: M3.onSurfaceVariant,
};

function M3NavRail({ screen, setScreen, setSelectedFeed }) {
  const items = [
    { id: 'feed',     label: 'Home',    icon: 'home' },
    { id: 'starred',  label: 'Saved',   icon: 'bookmark' },
    { id: 'subs',     label: 'Feeds',   icon: 'feed' },
    { id: 'settings', label: 'Settings', icon: 'settings' },
  ];
  return (
    <div style={{
      width: 88, flex: '0 0 88px', height: '100%',
      background: M3.surface, paddingTop: 16, paddingBottom: 16,
      display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 12,
      fontFamily: m3Font,
    }}>
      {/* FAB at top */}
      <button style={{
        all: 'unset', cursor: 'pointer', width: 56, height: 56, borderRadius: 16,
        background: M3.primaryContainer, color: M3.onPrimaryContainer,
        display: 'flex', alignItems: 'center', justifyContent: 'center',
        boxShadow: '0 3px 8px rgba(0,0,0,.12)', marginBottom: 10,
      }}>
        <M3Icon name="plus" size={24} />
      </button>
      {items.map(it => {
        const active = screen === it.id;
        return (
          <button key={it.id} onClick={() => { setScreen(it.id); setSelectedFeed(null); }}
            style={{
              all: 'unset', cursor: 'pointer', display: 'flex', flexDirection: 'column',
              alignItems: 'center', gap: 4, width: 64,
            }}>
            <div style={{
              width: 56, height: 32, borderRadius: 16,
              background: active ? M3.secondaryContainer : 'transparent',
              color: active ? M3.onSecondaryContainer : M3.onSurfaceVariant,
              display: 'flex', alignItems: 'center', justifyContent: 'center',
            }}>
              <M3Icon name={it.icon} size={22} />
            </div>
            <span style={{
              fontSize: 11.5, fontWeight: active ? 600 : 500,
              color: active ? M3.onSurface : M3.onSurfaceVariant,
            }}>{it.label}</span>
          </button>
        );
      })}
    </div>
  );
}

function M3ArticleListItem({ a, selected, onClick, density, viewMode }) {
  const feed = FEED_BY_ID[a.feed];
  const pad = density === 'compact' ? '10px 16px' : density === 'comfy' ? '20px 20px' : '14px 18px';
  if (viewMode === 'card') {
    return (
      <button onClick={onClick} style={{
        all: 'unset', cursor: 'pointer', display: 'block', margin: '8px 12px',
        background: selected ? M3.secondaryContainer : M3.surfaceContainer,
        color: selected ? M3.onSecondaryContainer : M3.onSurface,
        borderRadius: 12, padding: 14, fontFamily: m3Font,
      }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: 11.5,
          color: M3.onSurfaceVariant, marginBottom: 8 }}>
          <M3FeedDot hue={feed.hue} size={10} />
          <span style={{ fontWeight: 600 }}>{feed.name}</span>
          <span>·</span>
          <span>{m3Time(a.ts)}</span>
          {a.starred ? <M3Icon name="star_fill" size={14} color={M3.primary} /> : null}
        </div>
        <div style={{ fontSize: 15, fontWeight: 600, lineHeight: 1.25, letterSpacing: '-.005em', marginBottom: 6 }}>{a.title}</div>
        <div style={{ fontSize: 12.5, color: M3.onSurfaceVariant, lineHeight: 1.45,
          display: '-webkit-box', WebkitLineClamp: 2, WebkitBoxOrient: 'vertical', overflow: 'hidden' }}>{a.excerpt}</div>
        <div style={{ fontSize: 11, color: M3.onSurfaceVariant, marginTop: 8 }}>{a.minutes} min · {feed.tag}</div>
      </button>
    );
  }
  return (
    <button onClick={onClick} style={{
      all: 'unset', cursor: 'pointer', display: 'flex', alignItems: 'flex-start', gap: 12,
      padding: pad, fontFamily: m3Font,
      background: selected ? M3.secondaryContainer : 'transparent',
      color: selected ? M3.onSecondaryContainer : M3.onSurface,
    }}>
      <div style={{
        width: 40, height: 40, borderRadius: 20, flex: '0 0 auto',
        background: `oklch(0.92 0.05 ${feed.hue})`, color: `oklch(0.35 0.10 ${feed.hue})`,
        display: 'flex', alignItems: 'center', justifyContent: 'center',
        fontSize: 15, fontWeight: 600,
      }}>{feed.name[0]}</div>
      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <span style={{ fontSize: 12.5, fontWeight: 600, color: selected ? M3.onSecondaryContainer : M3.onSurfaceVariant }}>{feed.name}</span>
          <span style={{ fontSize: 11.5, color: M3.onSurfaceVariant }}>· {m3Time(a.ts)} · {a.minutes}m</span>
          {a.starred ? <M3Icon name="star_fill" size={14} color={M3.primary} /> : null}
          {a.unread && !a.starred ? <span style={{ marginLeft: 'auto', width: 8, height: 8, borderRadius: '50%', background: M3.primary }} /> : null}
        </div>
        <div style={{ fontSize: density === 'compact' ? 14 : 15, fontWeight: 600,
          lineHeight: 1.3, letterSpacing: '-.005em', marginTop: 2 }}>{a.title}</div>
        {density !== 'compact' ? (
          <div style={{ fontSize: 12.5, color: selected ? M3.onSecondaryContainer : M3.onSurfaceVariant,
            lineHeight: 1.4, marginTop: 4,
            display: '-webkit-box', WebkitLineClamp: 2, WebkitBoxOrient: 'vertical', overflow: 'hidden' }}>
            {a.excerpt}
          </div>
        ) : null}
      </div>
    </button>
  );
}

function M3ArticleList({ articles, selectedId, setSelectedId, density, viewMode, title, count }) {
  const [chip, setChip] = React.useState('All');
  return (
    <div style={{
      width: 380, flex: '0 0 380px', height: '100%', overflow: 'auto',
      background: M3.surfaceContainerLow, borderRight: `1px solid ${M3.outlineVariant}`,
    }}>
      <div style={{ padding: '20px 18px 8px', position: 'sticky', top: 0, background: M3.surfaceContainerLow, zIndex: 2 }}>
        <div style={{ fontFamily: m3Font, fontSize: 22, fontWeight: 500, color: M3.onSurface, letterSpacing: '-.01em' }}>{title}</div>
        <div style={{ fontSize: 12.5, color: M3.onSurfaceVariant, marginTop: 2 }}>{count}</div>
        <div style={{ display: 'flex', gap: 8, marginTop: 14, overflowX: 'auto', paddingBottom: 4 }}>
          {['All', 'Unread', 'Starred', 'Long', 'Short'].map(c => (
            <M3Chip key={c} label={c} selected={chip === c} onClick={() => setChip(c)} />
          ))}
        </div>
      </div>
      <div style={{ display: 'flex', flexDirection: 'column' }}>
        {articles.map(a => (
          <M3ArticleListItem key={a.id} a={a}
            selected={selectedId === a.id} onClick={() => setSelectedId(a.id)}
            density={density} viewMode={viewMode} />
        ))}
      </div>
    </div>
  );
}

function M3ReaderToolbar() {
  const Btn = ({ icon, label }) => (
    <button style={{
      all: 'unset', cursor: 'pointer', display: 'inline-flex', alignItems: 'center', gap: 8,
      height: 40, padding: '0 16px 0 12px', borderRadius: 20,
      border: `1px solid ${M3.outline}`, color: M3.primary, fontFamily: m3Font,
      fontSize: 13, fontWeight: 600, background: M3.surface,
    }}>
      <M3Icon name={icon} size={18} color={M3.primary} /> {label}
    </button>
  );
  return (
    <div style={{ display: 'flex', gap: 10, marginBottom: 28, flexWrap: 'wrap' }}>
      <Btn icon="bookmark" label="Save" />
      <Btn icon="share" label="Share" />
      <Btn icon="text_fields" label="Reader" />
      <div style={{ flex: 1 }} />
      <button style={m3IconBtnStyle}><M3Icon name="more" /></button>
    </div>
  );
}

function M3Reader({ article, fontSize }) {
  if (!article) {
    return (
      <div style={{ flex: 1, height: '100%', display: 'flex', alignItems: 'center', justifyContent: 'center',
        background: M3.surface, color: M3.onSurfaceVariant, fontFamily: m3Font }}>
        <div style={{ textAlign: 'center' }}>
          <div style={{
            width: 96, height: 96, borderRadius: 48, background: M3.surfaceContainerHigh,
            display: 'flex', alignItems: 'center', justifyContent: 'center', margin: '0 auto 16px',
          }}>
            <M3Icon name="inbox" size={36} color={M3.onSurfaceVariant} />
          </div>
          <div style={{ fontSize: 16, fontWeight: 500, color: M3.onSurface }}>Nothing selected</div>
          <div style={{ fontSize: 13, marginTop: 4 }}>Choose an article to read it here.</div>
        </div>
      </div>
    );
  }
  const feed = FEED_BY_ID[article.feed];
  return (
    <div style={{ flex: 1, height: '100%', overflow: 'auto', background: M3.surface, fontFamily: m3Font }}>
      <div style={{ maxWidth: 660, margin: '0 auto', padding: '40px 44px 80px', color: M3.onSurface }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 24 }}>
          <div style={{
            width: 40, height: 40, borderRadius: 20, background: `oklch(0.92 0.05 ${feed.hue})`,
            color: `oklch(0.35 0.10 ${feed.hue})`, display: 'flex', alignItems: 'center', justifyContent: 'center',
            fontSize: 15, fontWeight: 600,
          }}>{feed.name[0]}</div>
          <div style={{ flex: 1 }}>
            <div style={{ fontSize: 14, fontWeight: 600 }}>{feed.name}</div>
            <div style={{ fontSize: 12, color: M3.onSurfaceVariant }}>{feed.author} · {m3Time(article.ts)} · {article.minutes} min read</div>
          </div>
          <M3Chip label={feed.tag} selected leading={null} />
        </div>

        <h1 style={{ fontSize: 32, fontWeight: 600, lineHeight: 1.15, letterSpacing: '-.015em', margin: '0 0 14px' }}>
          {article.title}
        </h1>
        <p style={{ fontSize: 17, lineHeight: 1.55, color: M3.onSurfaceVariant, margin: '0 0 28px' }}>{article.excerpt}</p>

        <M3ReaderToolbar />

        <div style={{ fontSize, lineHeight: 1.7, color: M3.onSurface }}>
          {ARTICLE_BODY.map((p, i) => (
            <p key={i} style={{ margin: '0 0 1.1em', textWrap: 'pretty' }}>{p}</p>
          ))}
        </div>

        <div style={{
          marginTop: 40, padding: '16px 20px', background: M3.surfaceContainer, borderRadius: 16,
          display: 'flex', alignItems: 'center', gap: 12, fontSize: 13,
        }}>
          <div style={{ flex: 1 }}>
            <div style={{ fontWeight: 600, color: M3.onSurface }}>More from {feed.name}</div>
            <div style={{ color: M3.onSurfaceVariant, marginTop: 2 }}>{ARTICLES.filter(x => x.feed === feed.id).length} articles · {feed.unread} unread</div>
          </div>
          <button style={{ ...m3IconBtnStyle, padding: '0 16px', width: 'auto', color: M3.primary, fontFamily: m3Font, fontWeight: 600 }}>
            Open feed
          </button>
        </div>
      </div>
    </div>
  );
}

function M3SubsScreen({ setSelectedFeed }) {
  return (
    <div style={{ flex: 1, height: '100%', overflow: 'auto', background: M3.surface, fontFamily: m3Font, color: M3.onSurface }}>
      <div style={{ maxWidth: 880, margin: '0 auto', padding: '36px 44px 80px' }}>
        <div style={{ display: 'flex', alignItems: 'baseline', justifyContent: 'space-between', marginBottom: 22 }}>
          <h1 style={{ fontSize: 28, fontWeight: 500, letterSpacing: '-.01em', margin: 0 }}>Your feeds</h1>
          <button style={{
            all: 'unset', cursor: 'pointer', height: 40, padding: '0 18px 0 14px', borderRadius: 20,
            background: M3.primary, color: M3.onPrimary, display: 'inline-flex', alignItems: 'center', gap: 8,
            fontWeight: 600, fontSize: 14,
          }}>
            <M3Icon name="plus" size={18} color={M3.onPrimary} /> Add feed
          </button>
        </div>

        <div style={{
          display: 'flex', alignItems: 'center', gap: 10, padding: '8px 16px',
          background: M3.surfaceContainerHigh, borderRadius: 28, marginBottom: 20, height: 56, boxSizing: 'border-box',
        }}>
          <M3Icon name="search" size={20} color={M3.onSurfaceVariant} />
          <input placeholder="Search subscriptions or paste a URL…"
            style={{ all: 'unset', flex: 1, fontSize: 14, color: M3.onSurface }} />
          <span style={{ fontSize: 12, color: M3.onSurfaceVariant }}>{FEEDS.length} feeds</span>
        </div>

        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(2, 1fr)', gap: 12 }}>
          {FEEDS.map(f => (
            <button key={f.id} onClick={() => setSelectedFeed(f.id)} style={{
              all: 'unset', cursor: 'pointer', display: 'flex', gap: 14, padding: 16,
              background: M3.surfaceContainer, borderRadius: 16,
            }}>
              <div style={{
                width: 48, height: 48, borderRadius: 24, flex: '0 0 auto',
                background: `oklch(0.92 0.05 ${f.hue})`, color: `oklch(0.35 0.10 ${f.hue})`,
                display: 'flex', alignItems: 'center', justifyContent: 'center',
                fontSize: 18, fontWeight: 600,
              }}>{f.name[0]}</div>
              <div style={{ flex: 1, minWidth: 0 }}>
                <div style={{ display: 'flex', alignItems: 'baseline', gap: 8 }}>
                  <span style={{ fontWeight: 600, fontSize: 15 }}>{f.name}</span>
                  <span style={{ fontSize: 11, color: M3.onSurfaceVariant }}>{f.tag}</span>
                </div>
                <div style={{ fontSize: 12, color: M3.onSurfaceVariant, marginTop: 2,
                  overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{f.url}</div>
                <div style={{ display: 'flex', gap: 8, marginTop: 8 }}>
                  <M3Chip label={f.folder} selected />
                  <M3Chip label={`${f.unread} new`} />
                </div>
              </div>
            </button>
          ))}
        </div>
      </div>
    </div>
  );
}

function M3Settings() {
  const Row = ({ label, hint, control }) => (
    <div style={{
      display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 16,
      padding: '14px 20px', background: M3.surfaceContainer, borderRadius: 0,
    }}>
      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={{ fontSize: 14, fontWeight: 500, color: M3.onSurface }}>{label}</div>
        {hint ? <div style={{ fontSize: 12, color: M3.onSurfaceVariant, marginTop: 2 }}>{hint}</div> : null}
      </div>
      {control}
    </div>
  );
  const Switch = ({ on }) => (
    <div style={{
      width: 52, height: 32, borderRadius: 16, padding: 2, boxSizing: 'border-box',
      background: on ? M3.primary : M3.surfaceContainerHighest,
      border: on ? 'none' : `2px solid ${M3.outline}`,
      display: 'flex', alignItems: 'center', justifyContent: on ? 'flex-end' : 'flex-start',
    }}>
      <div style={{
        width: on ? 24 : 16, height: on ? 24 : 16, borderRadius: '50%',
        background: on ? M3.onPrimary : M3.outline,
      }} />
    </div>
  );
  const Pill = ({ value }) => (
    <div style={{ display: 'inline-flex', alignItems: 'center', gap: 6,
      padding: '6px 12px', borderRadius: 8, border: `1px solid ${M3.outline}`,
      fontSize: 13, color: M3.onSurfaceVariant }}>{value} ›</div>
  );
  return (
    <div style={{ flex: 1, height: '100%', overflow: 'auto', background: M3.surface, fontFamily: m3Font, color: M3.onSurface }}>
      <div style={{ maxWidth: 640, margin: '0 auto', padding: '36px 32px 80px' }}>
        <h1 style={{ fontSize: 28, fontWeight: 500, letterSpacing: '-.01em', margin: '0 0 22px' }}>Settings</h1>

        <div style={{ fontSize: 12, fontWeight: 600, color: M3.primary,
          padding: '0 20px 6px', textTransform: 'uppercase', letterSpacing: '.06em' }}>Reading</div>
        <div style={{ borderRadius: 16, overflow: 'hidden', display: 'flex', flexDirection: 'column', gap: 2, background: M3.surface }}>
          <Row label="Mark as read on scroll" hint="Automatically mark articles read after they pass the fold." control={<Switch on />} />
          <Row label="Reader theme" hint="Background of the article reader." control={<Pill value="Paper" />} />
          <Row label="Default sort" control={<Pill value="Newest" />} />
        </div>

        <div style={{ fontSize: 12, fontWeight: 600, color: M3.primary, marginTop: 24,
          padding: '0 20px 6px', textTransform: 'uppercase', letterSpacing: '.06em' }}>Sync</div>
        <div style={{ borderRadius: 16, overflow: 'hidden', display: 'flex', flexDirection: 'column', gap: 2 }}>
          <Row label="Refresh interval" control={<Pill value="1 hour" />} />
          <Row label="Keep articles" hint="Older than this are removed." control={<Pill value="90 days" />} />
          <Row label="Sync over cellular" control={<Switch on={false} />} />
        </div>

        <div style={{ fontSize: 12, fontWeight: 600, color: M3.primary, marginTop: 24,
          padding: '0 20px 6px', textTransform: 'uppercase', letterSpacing: '.06em' }}>Account</div>
        <div style={{ borderRadius: 16, overflow: 'hidden', display: 'flex', flexDirection: 'column', gap: 2 }}>
          <Row label="Signed in" hint="Personal · this device" control={<Pill value="Local" />} />
          <Row label="Import OPML" control={<Pill value="Choose…" />} />
        </div>
      </div>
    </div>
  );
}

function Material3DesktopPrototype({ density = 'regular', viewMode = 'list', fontSize = 18 }) {
  const [screen, setScreen] = React.useState('feed');
  const [selectedFeed, setSelectedFeed] = React.useState(null);
  const [selectedId, setSelectedId] = React.useState('a01');

  let articles = ARTICLES;
  let title = 'Home', count = '';
  if (selectedFeed) {
    const f = FEED_BY_ID[selectedFeed];
    articles = ARTICLES.filter(a => a.feed === selectedFeed);
    title = f.name;
    count = `${articles.length} articles · ${f.author}`;
  } else if (screen === 'starred') {
    articles = ARTICLES.filter(a => a.starred);
    title = 'Saved'; count = `${articles.length} bookmarked`;
  } else if (screen === 'feed') {
    count = `${ARTICLES.filter(a => a.unread).length} unread · ${ARTICLES.length} total`;
  }
  const article = ARTICLES.find(a => a.id === selectedId);

  const isListView = screen === 'feed' || screen === 'starred' || selectedFeed;

  return (
    <div style={{ width: '100%', height: '100%', display: 'flex', flexDirection: 'column',
      background: M3.surface, fontFamily: m3Font }}>
      <M3TopAppBar title={isListView ? 'Home' : screen === 'subs' ? 'Feeds' : 'Settings'} large={false} />
      <div style={{ flex: 1, display: 'flex', minHeight: 0 }}>
        <M3NavRail screen={selectedFeed ? 'feed' : screen} setScreen={setScreen} setSelectedFeed={setSelectedFeed} />
        {screen === 'subs' && !selectedFeed ? (
          <M3SubsScreen setSelectedFeed={setSelectedFeed} />
        ) : screen === 'settings' && !selectedFeed ? (
          <M3Settings />
        ) : (
          <React.Fragment>
            <M3ArticleList articles={articles} selectedId={selectedId} setSelectedId={setSelectedId}
              density={density} viewMode={viewMode} title={title} count={count} />
            <M3Reader article={article} fontSize={fontSize} />
          </React.Fragment>
        )}
      </div>
    </div>
  );
}

// ─────────────────────────────────────────────────────────────────────────
// MOBILE
// ─────────────────────────────────────────────────────────────────────────
function M3MTopBar({ title, large = true, leading = 'menu', onLead }) {
  return (
    <div style={{
      paddingTop: 14, padding: '14px 4px 0',
      background: M3.surface, color: M3.onSurface, fontFamily: m3Font,
    }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 4, padding: '0 8px' }}>
        <button onClick={onLead} style={m3IconBtnStyle}><M3Icon name={leading} /></button>
        {!large ? <div style={{ flex: 1, fontSize: 18, fontWeight: 500 }}>{title}</div> : <div style={{ flex: 1 }} />}
        <button style={m3IconBtnStyle}><M3Icon name="search" /></button>
        <button style={m3IconBtnStyle}><M3Icon name="more" /></button>
      </div>
      {large ? (
        <div style={{ padding: '12px 24px 18px', fontSize: 28, fontWeight: 500, letterSpacing: '-.01em' }}>{title}</div>
      ) : null}
    </div>
  );
}

function M3MBottomBar({ tab, setTab }) {
  const items = [
    { id: 'feed', label: 'Home', icon: 'home' },
    { id: 'starred', label: 'Saved', icon: 'bookmark' },
    { id: 'subs', label: 'Feeds', icon: 'feed' },
    { id: 'settings', label: 'Settings', icon: 'settings' },
  ];
  return (
    <div style={{
      background: M3.surfaceContainer, borderTop: `1px solid ${M3.outlineVariant}`,
      display: 'flex', padding: '8px 0 14px', fontFamily: m3Font,
    }}>
      {items.map(it => {
        const active = tab === it.id;
        return (
          <button key={it.id} onClick={() => setTab(it.id)} style={{
            all: 'unset', cursor: 'pointer', flex: 1, padding: '4px 0',
            display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 4,
          }}>
            <div style={{
              width: 64, height: 32, borderRadius: 16,
              background: active ? M3.secondaryContainer : 'transparent',
              color: active ? M3.onSecondaryContainer : M3.onSurfaceVariant,
              display: 'flex', alignItems: 'center', justifyContent: 'center',
            }}>
              <M3Icon name={it.icon} size={22} />
            </div>
            <span style={{
              fontSize: 11.5, fontWeight: active ? 600 : 500,
              color: active ? M3.onSurface : M3.onSurfaceVariant,
            }}>{it.label}</span>
          </button>
        );
      })}
    </div>
  );
}

function M3MFeedScreen({ articles, setOpenId, density, viewMode, title, subtitle }) {
  const [chip, setChip] = React.useState('All');
  return (
    <React.Fragment>
      <M3MTopBar title={title} large />
      <div style={{ padding: '0 16px 6px', color: M3.onSurfaceVariant,
        fontFamily: m3Font, fontSize: 13, marginTop: -10, marginBottom: 4 }}>{subtitle}</div>
      <div style={{ display: 'flex', gap: 8, padding: '8px 16px 14px', overflowX: 'auto' }}>
        {['All', 'Unread', 'Long', 'Short', 'Today', 'This week'].map(c => (
          <M3Chip key={c} label={c} selected={chip === c} onClick={() => setChip(c)} />
        ))}
      </div>
      <div style={{ flex: 1 }}>
        {articles.map(a => (
          <M3ArticleListItem key={a.id} a={a} onClick={() => setOpenId(a.id)}
            density={density} viewMode={viewMode} />
        ))}
        <div style={{ height: 80 }} />
      </div>
    </React.Fragment>
  );
}

function M3MReaderScreen({ article, fontSize, onBack }) {
  const feed = FEED_BY_ID[article.feed];
  return (
    <React.Fragment>
      <div style={{
        padding: '14px 8px 6px', display: 'flex', alignItems: 'center', gap: 4,
        background: M3.surface, fontFamily: m3Font,
      }}>
        <button onClick={onBack} style={m3IconBtnStyle}><M3Icon name="arrow_back" /></button>
        <div style={{ flex: 1, fontSize: 15, fontWeight: 500, color: M3.onSurface,
          overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{feed.name}</div>
        <button style={m3IconBtnStyle}><M3Icon name="bookmark" /></button>
        <button style={m3IconBtnStyle}><M3Icon name="share" /></button>
        <button style={m3IconBtnStyle}><M3Icon name="more" /></button>
      </div>
      <div style={{ padding: '8px 24px 80px', color: M3.onSurface, fontFamily: m3Font }}>
        <div style={{ display: 'flex', gap: 6, marginBottom: 12 }}>
          <M3Chip label={feed.tag} selected />
          <M3Chip label={`${article.minutes} min`} />
        </div>
        <h1 style={{ fontSize: 26, fontWeight: 600, lineHeight: 1.18, letterSpacing: '-.01em', margin: '0 0 10px' }}>
          {article.title}
        </h1>
        <div style={{ fontSize: 12.5, color: M3.onSurfaceVariant, marginBottom: 18 }}>
          {feed.author} · {m3Time(article.ts)}
        </div>
        <p style={{ fontSize: 16, lineHeight: 1.5, color: M3.onSurfaceVariant, margin: '0 0 20px' }}>{article.excerpt}</p>
        <div style={{ fontSize, lineHeight: 1.7 }}>
          {ARTICLE_BODY.map((p, i) => (
            <p key={i} style={{ margin: '0 0 1.1em', textWrap: 'pretty' }}>{p}</p>
          ))}
        </div>
      </div>
    </React.Fragment>
  );
}

function M3MSubsScreen() {
  return (
    <React.Fragment>
      <M3MTopBar title="Feeds" large />
      <div style={{ padding: '0 16px 14px' }}>
        <div style={{
          display: 'flex', alignItems: 'center', gap: 10, padding: '0 16px', height: 48,
          background: M3.surfaceContainerHigh, borderRadius: 24,
        }}>
          <M3Icon name="search" size={18} color={M3.onSurfaceVariant} />
          <input placeholder="Add a feed or search…"
            style={{ all: 'unset', flex: 1, fontSize: 14, color: M3.onSurface, fontFamily: m3Font }} />
        </div>
      </div>
      <div style={{ padding: '0 12px 100px' }}>
        {FEEDS.map(f => (
          <div key={f.id} style={{
            display: 'flex', alignItems: 'center', gap: 14, padding: '12px 12px',
            background: M3.surface,
          }}>
            <div style={{
              width: 40, height: 40, borderRadius: 20, flex: '0 0 auto',
              background: `oklch(0.92 0.05 ${f.hue})`, color: `oklch(0.35 0.10 ${f.hue})`,
              display: 'flex', alignItems: 'center', justifyContent: 'center',
              fontFamily: m3Font, fontSize: 16, fontWeight: 600,
            }}>{f.name[0]}</div>
            <div style={{ flex: 1, minWidth: 0, fontFamily: m3Font }}>
              <div style={{ fontSize: 15, fontWeight: 500, color: M3.onSurface }}>{f.name}</div>
              <div style={{ fontSize: 12, color: M3.onSurfaceVariant, marginTop: 2,
                overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                {f.tag} · {f.unread} new
              </div>
            </div>
            <button style={m3IconBtnStyle}><M3Icon name="more" /></button>
          </div>
        ))}
      </div>
    </React.Fragment>
  );
}

function M3MSettingsScreen() {
  const Row = ({ label, hint, value }) => (
    <div style={{
      display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 16,
      padding: '14px 24px', fontFamily: m3Font,
    }}>
      <div style={{ flex: 1 }}>
        <div style={{ fontSize: 14, fontWeight: 500, color: M3.onSurface }}>{label}</div>
        {hint ? <div style={{ fontSize: 12, color: M3.onSurfaceVariant, marginTop: 2 }}>{hint}</div> : null}
      </div>
      <div style={{ fontSize: 13, color: M3.onSurfaceVariant }}>{value} ›</div>
    </div>
  );
  const Group = ({ title, children }) => (
    <React.Fragment>
      <div style={{ padding: '18px 24px 4px', fontSize: 12, fontWeight: 600, color: M3.primary,
        textTransform: 'uppercase', letterSpacing: '.06em', fontFamily: m3Font }}>{title}</div>
      {children}
    </React.Fragment>
  );
  return (
    <React.Fragment>
      <M3MTopBar title="Settings" large />
      <div style={{ paddingBottom: 100 }}>
        <Group title="Reading">
          <Row label="Mark as read on scroll" value="On" />
          <Row label="Reader theme" value="Paper" />
          <Row label="Default sort" value="Newest" />
        </Group>
        <Group title="Sync">
          <Row label="Refresh interval" value="1h" />
          <Row label="Keep articles" hint="Older than this are removed" value="90 days" />
        </Group>
        <Group title="Account">
          <Row label="Import OPML" value="Choose…" />
          <Row label="About" value="v1.0.0" />
        </Group>
      </div>
    </React.Fragment>
  );
}

function Material3MobilePrototype({ density = 'regular', viewMode = 'list', fontSize = 17 }) {
  const [tab, setTab] = React.useState('feed');
  const [openId, setOpenId] = React.useState(null);

  let articles = ARTICLES;
  let title = 'Home', subtitle = `${ARTICLES.filter(a => a.unread).length} unread`;
  if (tab === 'starred') {
    articles = ARTICLES.filter(a => a.starred);
    title = 'Saved'; subtitle = `${articles.length} bookmarked`;
  }
  const article = ARTICLES.find(a => a.id === openId);

  return (
    <div style={{ position: 'relative', width: '100%', height: '100%',
      background: M3.surface, display: 'flex', flexDirection: 'column' }}>
      <div style={{ flex: 1, display: 'flex', flexDirection: 'column', overflow: 'auto' }}>
        {article ? (
          <M3MReaderScreen article={article} fontSize={fontSize} onBack={() => setOpenId(null)} />
        ) : tab === 'subs' ? (
          <M3MSubsScreen />
        ) : tab === 'settings' ? (
          <M3MSettingsScreen />
        ) : (
          <M3MFeedScreen articles={articles} setOpenId={setOpenId}
            density={density} viewMode={viewMode} title={title} subtitle={subtitle} />
        )}
      </div>

      {/* FAB anchored above bottom bar — only on list screens */}
      {!article && (tab === 'feed' || tab === 'starred') ? (
        <div style={{ position: 'absolute', right: 16, bottom: 86, zIndex: 5 }}>
          <button style={{
            all: 'unset', cursor: 'pointer', height: 56, padding: '0 22px',
            borderRadius: 16, background: M3.primaryContainer, color: M3.onPrimaryContainer,
            display: 'inline-flex', alignItems: 'center', gap: 10,
            boxShadow: '0 3px 8px rgba(0,0,0,.16)', fontFamily: m3Font, fontWeight: 600, fontSize: 14,
          }}>
            <M3Icon name="plus" size={20} /> Add feed
          </button>
        </div>
      ) : null}

      {!article ? <M3MBottomBar tab={tab} setTab={setTab} /> : null}
    </div>
  );
}

Object.assign(window, { Material3DesktopPrototype, Material3MobilePrototype });

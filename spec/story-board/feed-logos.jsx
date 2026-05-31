// Feed — Logo studies, v2.
// Each artboard shows two contexts:
//   LEFT  — Web: full lockup + 16px favicon + 32px favicon + browser tab
//   RIGHT — Android: launcher icon on dark wallpaper at 76px + 44px + 32px
//
// Removed: 10 Reading Room (collapses to EnclosedF at icon scale),
//          14 Marginalia (loses meaning without the wordmark).
// Twelve concepts remain across two sections.

const SERIF = "'Source Serif 4', Georgia, 'Times New Roman', serif";
const SANS  = "'IBM Plex Sans', system-ui, sans-serif";
const MONO  = "'IBM Plex Mono', ui-monospace, monospace";

const T = {
  bg: '#f3f5f7', panel: '#f9fafb',
  border: 'rgba(20,25,40,0.08)', borderStrong: 'rgba(20,25,40,0.16)',
  ink: '#1a1f28', ink2: '#4a5160', ink3: '#7c8290',
  accent: '#566073', accentSoft: 'rgba(86,96,115,0.12)', onAccent: '#f9fafb',
};

const WALLPAPER = '#1a1d24'; // dark home-screen bg for Android pane

// ── Wordmark ─────────────────────────────────────────────────────────
function Word({ size = 40, c = T.ink }) {
  return (
    <span style={{ fontFamily: SERIF, fontSize: size, fontWeight: 500,
      letterSpacing: '-0.02em', color: c, lineHeight: 1, whiteSpace: 'nowrap' }}>Feed</span>
  );
}

// ── Marks ─────────────────────────────────────────────────────────────
// Each takes (size, c, a) — size is the mark's height, c = stroke/ink colour, a = accent colour.

function RingDot(size, c, a) {
  const sw = Math.max(1.5, size * 0.055);
  return (
    <div style={{ width: size, height: size, borderRadius: '50%',
      border: `${sw}px solid ${c}`, flex: '0 0 auto',
      display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
      <div style={{ width: size * 0.28, height: size * 0.28, borderRadius: '50%', background: a }} />
    </div>
  );
}

function Signal(size, c, a) {
  return (
    <svg width={size} height={size} viewBox="0 0 40 40" style={{ flex: '0 0 auto', display: 'block' }}>
      <path d="M9 18.5 A12.5 12.5 0 0 1 21.5 31" fill="none" stroke={c} strokeWidth="2.4" strokeLinecap="round" />
      <path d="M9 8.5 A22.5 22.5 0 0 1 31.5 31" fill="none" stroke={c} strokeWidth="2.4" strokeLinecap="round" />
      <circle cx="9" cy="31" r="3.3" fill={a} />
    </svg>
  );
}

function EnclosedF(size, c, a) {
  return (
    <div style={{ width: size, height: size, borderRadius: '50%',
      border: `${Math.max(1.5, size * 0.05)}px solid ${c}`, flex: '0 0 auto',
      display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
      <span style={{ fontFamily: SERIF, fontWeight: 500, fontSize: size * 0.5,
        color: a, lineHeight: 1, marginTop: size * 0.02 }}>F</span>
    </div>
  );
}

function Column(size, c, a) {
  const rows = [
    { dot: a, op: 1,    lw: '100%' },
    { dot: c, op: 0.52, lw: '72%'  },
    { dot: c, op: 0.28, lw: '88%'  },
  ];
  return (
    <div style={{ width: size * 1.08, display: 'flex', flexDirection: 'column',
      gap: size * 0.17, flex: '0 0 auto' }}>
      {rows.map((r, i) => (
        <div key={i} style={{ display: 'flex', alignItems: 'center', gap: size * 0.16 }}>
          <div style={{ width: size * 0.15, height: size * 0.15, borderRadius: '50%',
            background: r.dot, opacity: r.op, flex: '0 0 auto' }} />
          <div style={{ width: r.lw, height: Math.max(2, size * 0.085),
            borderRadius: 999, background: c, opacity: r.op }} />
        </div>
      ))}
    </div>
  );
}

function FoldedSheet(size, c, a) {
  return (
    <svg width={size * 0.82} height={size} viewBox="0 0 32 40" style={{ flex: '0 0 auto', display: 'block' }}>
      <path d="M4 3 H22 L28 9 V37 H4 Z" fill={T.panel} stroke={c} strokeWidth="2" strokeLinejoin="round" />
      <path d="M22 3 L28 9 L22 9 Z" fill={a} stroke={c} strokeWidth="2" strokeLinejoin="round" />
      <line x1="9" y1="20" x2="23" y2="20" stroke={c} strokeWidth="1.5" strokeOpacity="0.4" strokeLinecap="round" />
      <line x1="9" y1="26" x2="23" y2="26" stroke={c} strokeWidth="1.5" strokeOpacity="0.4" strokeLinecap="round" />
      <line x1="9" y1="32" x2="18" y2="32" stroke={c} strokeWidth="1.5" strokeOpacity="0.4" strokeLinecap="round" />
    </svg>
  );
}

function Stack(size, c, a) {
  const cW = size * 0.7, cH = size * 0.5;
  const base = { position: 'absolute', width: cW, height: cH, borderRadius: 4,
    background: T.panel, border: `${Math.max(1.2, size * 0.035)}px solid ${c}`, boxSizing: 'border-box' };
  return (
    <div style={{ position: 'relative', width: size, height: size, flex: '0 0 auto' }}>
      <div style={{ ...base, top: 0, left: size * 0.28, opacity: 0.32 }} />
      <div style={{ ...base, top: size * 0.22, left: size * 0.14, opacity: 0.6 }} />
      <div style={{ ...base, top: size * 0.44, left: 0 }}>
        <div style={{ position: 'absolute', left: 0, top: 0, bottom: 0,
          width: Math.max(2, size * 0.055), background: a, borderRadius: '4px 0 0 4px' }} />
      </div>
    </div>
  );
}

function Pilcrow(size, c, a) {
  return (
    <span style={{ fontFamily: SERIF, fontSize: size * 1.12, fontWeight: 500,
      color: a, lineHeight: 1, flex: '0 0 auto' }}>¶</span>
  );
}

function OpenBook(size, c, a) {
  return (
    <svg width={size * 1.3} height={size} viewBox="0 0 52 34" style={{ flex: '0 0 auto', display: 'block' }}>
      <path d="M26 3 L5 6 L5 30 L26 30 Z" fill={T.panel} stroke={c} strokeWidth="1.8" strokeLinejoin="round" />
      <path d="M26 3 L47 6 L47 30 L26 30 Z" fill={T.panel} stroke={c} strokeWidth="1.8" strokeLinejoin="round" />
      <line x1="26" y1="3" x2="26" y2="30" stroke={c} strokeWidth="1.2" />
      <line x1="8" y1="15" x2="22" y2="15" stroke={a} strokeWidth="1.8" strokeLinecap="round" strokeOpacity="0.85" />
      <line x1="30" y1="15" x2="44" y2="15" stroke={a} strokeWidth="1.8" strokeLinecap="round" strokeOpacity="0.85" />
      {[20, 24].map(y => (
        <React.Fragment key={y}>
          <line x1="8" y1={y} x2="21" y2={y} stroke={c} strokeWidth="1.2" strokeLinecap="round" strokeOpacity="0.28" />
          <line x1="30" y1={y} x2="43" y2={y} stroke={c} strokeWidth="1.2" strokeLinecap="round" strokeOpacity="0.28" />
        </React.Fragment>
      ))}
    </svg>
  );
}

function Spine(size, c, a) {
  return (
    <svg width={size * 0.44} height={size} viewBox="0 0 18 40" style={{ flex: '0 0 auto', display: 'block' }}>
      <rect x="2" y="2" width="14" height="36" rx="3" fill={T.panel} stroke={c} strokeWidth="2" />
      <line x1="2" y1="8.5"  x2="16" y2="8.5"  stroke={c} strokeWidth="1.4" strokeOpacity="0.35" />
      <line x1="2" y1="31.5" x2="16" y2="31.5" stroke={c} strokeWidth="1.4" strokeOpacity="0.35" />
      <rect x="2" y="13" width="14" height="4.5" fill={a} opacity="0.85" />
    </svg>
  );
}

function Spectacles(size, c, a) {
  return (
    <svg width={size * 1.55} height={size * 0.68} viewBox="-5 0 64 26" style={{ flex: '0 0 auto', display: 'block' }}>
      <circle cx="14" cy="13" r="10.5" fill="none" stroke={c} strokeWidth="2" />
      <circle cx="40" cy="13" r="10.5" fill="none" stroke={c} strokeWidth="2" />
      <path d="M24.5 13 Q27 10.5 29.5 13" fill="none" stroke={a} strokeWidth="2.2" strokeLinecap="round" />
      <line x1="3.5" y1="13" x2="-3" y2="10" stroke={c} strokeWidth="2" strokeLinecap="round" />
      <line x1="50.5" y1="13" x2="57" y2="10" stroke={c} strokeWidth="2" strokeLinecap="round" />
    </svg>
  );
}

// Favicon treatments for wordmark-led directions (Feed. and Unread).
function FaviconF(size, c, a, variant) {
  if (variant === 'period') {
    return (
      <span style={{ display: 'inline-flex', alignItems: 'flex-end', gap: size * 0.06 }}>
        <span style={{ fontFamily: SERIF, fontWeight: 500, fontSize: size, color: c, lineHeight: 1 }}>F</span>
        <span style={{ width: size * 0.18, height: size * 0.18, borderRadius: '50%',
          background: a, marginBottom: size * 0.08, flex: '0 0 auto' }} />
      </span>
    );
  }
  // badge
  return (
    <span style={{ position: 'relative', display: 'inline-flex' }}>
      <span style={{ fontFamily: SERIF, fontWeight: 500, fontSize: size, color: c, lineHeight: 1 }}>F</span>
      <span style={{ position: 'absolute', top: -size * 0.04, right: -size * 0.2,
        width: size * 0.22, height: size * 0.22, borderRadius: '50%', background: a }} />
    </span>
  );
}

// ── Concepts ──────────────────────────────────────────────────────────
function lockup(mark, c, wordSize = 40) {
  return (
    <span style={{ display: 'inline-flex', alignItems: 'center', gap: 14 }}>
      {mark}
      <Word size={wordSize} c={c} />
    </span>
  );
}

const CONCEPTS = [
  {
    id: 'ringed-dot', label: '01 · Ringed Dot',
    note: 'The canonical mark, refined. Ring + unread dot, native to the app.',
    hero: (c, a) => lockup(RingDot(42, c, a), c),
    fav:  (s, c, a) => RingDot(s, c, a),
  },
  {
    id: 'feed-period', label: '02 · Feed.',
    note: 'The full stop is the accent dot. Editorial, final, calm.',
    hero: (c, a) => (
      <span style={{ display: 'inline-flex', alignItems: 'flex-end', gap: 4 }}>
        <Word size={46} c={c} />
        <span style={{ width: 10, height: 10, borderRadius: '50%', background: a, marginBottom: 7 }} />
      </span>
    ),
    fav: (s, c, a) => FaviconF(s, c, a, 'period'),
  },
  {
    id: 'the-column', label: '03 · The Column',
    note: 'Three reading rows — newest in accent. The product, abstracted.',
    hero: (c, a) => lockup(Column(40, c, a), c),
    fav:  (s, c, a) => Column(s, c, a),
  },
  {
    id: 'pilcrow', label: '04 · Pilcrow',
    note: 'The oldest symbol for "text begins here."',
    hero: (c, a) => lockup(Pilcrow(40, c, a), c),
    fav:  (s, c, a) => Pilcrow(s, c, a),
  },
];

const READING_CONCEPTS = [
  {
    id: 'open-book', label: '05 · Open Book',
    note: 'Two pages from a spine. Accent rule marks the reading position.',
    hero: (c, a) => lockup(OpenBook(40, c, a), c),
    fav:  (s, c, a) => OpenBook(s, c, a),
  },
];

// ── Artboard layout (two-pane) ────────────────────────────────────────
const ICON_BG = T.bg; // launcher icon background — paper
const ICON_R  = 0.22; // corner radius as fraction of icon size (Android adaptive)

function LauncherIcon({ concept, size }) {
  const markSize = size * 0.42; // mark at 42 % of icon — fits within 72 dp safe zone
  return (
    <div style={{ width: size, height: size, borderRadius: size * ICON_R,
      background: ICON_BG, display: 'flex', alignItems: 'center',
      justifyContent: 'center', flex: '0 0 auto', overflow: 'hidden' }}>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center',
        width: markSize * 1.6, height: markSize * 1.6 }}>
        {concept.fav(markSize, T.ink, T.accent)}
      </div>
    </div>
  );
}

function FaviconTile({ concept, size }) {
  const markSize = size * 0.65;
  return (
    <div style={{ width: size, height: size, flex: '0 0 auto',
      border: `1px solid ${T.borderStrong}`, background: T.panel,
      display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center',
        width: markSize * 1.4, height: markSize * 1.4 }}>
        {concept.fav(markSize, T.ink, T.accent)}
      </div>
    </div>
  );
}

function ArtboardBody({ concept }) {
  return (
    <div style={{ width: '100%', height: '100%', display: 'flex',
      fontFamily: SANS, overflow: 'hidden', boxSizing: 'border-box' }}>

      {/* ── LEFT: Web context ── */}
      <div style={{ flex: '0 0 285px', background: T.bg, display: 'flex',
        flexDirection: 'column', padding: '18px 22px 16px', boxSizing: 'border-box' }}>
        <div style={{ fontFamily: MONO, fontSize: 9, letterSpacing: '0.1em',
          color: T.ink3, textTransform: 'uppercase', marginBottom: 16 }}>Web</div>

        {/* lockup hero */}
        <div style={{ flex: 1, display: 'flex', alignItems: 'center' }}>
          {concept.hero(T.ink, T.accent)}
        </div>

        {/* favicon row */}
        <div style={{ borderTop: `1px solid ${T.border}`, paddingTop: 11,
          display: 'flex', alignItems: 'center', gap: 8 }}>
          <FaviconTile concept={concept} size={16} />
          <FaviconTile concept={concept} size={32} />
          {/* browser tab */}
          <div style={{ display: 'inline-flex', alignItems: 'center', gap: 5,
            border: `1px solid ${T.borderStrong}`, borderRadius: 4,
            padding: '4px 9px 4px 6px', background: T.panel }}>
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center',
              width: 12, height: 12 }}>
              {concept.fav(10, T.ink, T.accent)}
            </div>
            <span style={{ fontSize: 10.5, color: T.ink2, whiteSpace: 'nowrap' }}>Feed</span>
          </div>
          {/* rationale */}
          <span style={{ fontFamily: MONO, fontSize: 9, lineHeight: 1.4,
            color: T.ink3, marginLeft: 4, textWrap: 'pretty' }}>{concept.note}</span>
        </div>
      </div>

      {/* divider */}
      <div style={{ width: 1, background: T.border, flex: '0 0 auto' }} />

      {/* ── RIGHT: Android launcher context ── */}
      <div style={{ flex: 1, background: WALLPAPER, display: 'flex',
        flexDirection: 'column', padding: '18px 20px 16px', boxSizing: 'border-box' }}>
        <div style={{ fontFamily: MONO, fontSize: 9, letterSpacing: '0.1em',
          color: 'rgba(255,255,255,0.3)', textTransform: 'uppercase', marginBottom: 16 }}>Android</div>

        {/* primary icon + label */}
        <div style={{ flex: 1, display: 'flex', flexDirection: 'column',
          alignItems: 'center', justifyContent: 'center', gap: 9 }}>
          <LauncherIcon concept={concept} size={76} />
          <span style={{ fontFamily: SANS, fontSize: 11, color: 'rgba(255,255,255,0.82)',
            letterSpacing: '0.01em' }}>Feed</span>
        </div>

        {/* smaller at-a-glance sizes */}
        <div style={{ display: 'flex', justifyContent: 'flex-end',
          alignItems: 'flex-end', gap: 8 }}>
          <LauncherIcon concept={concept} size={44} />
          <LauncherIcon concept={concept} size={30} />
        </div>
      </div>

    </div>
  );
}

// ── Page ──────────────────────────────────────────────────────────────
function FeedLogos() {
  return (
    <DesignCanvas>
      <DCSection id="logos" title="Feed — Logo Studies"
        subtitle="Web context (lockup + favicon + tab) alongside Android launcher icon at 76 / 44 / 30 dp">
        {[...CONCEPTS, ...READING_CONCEPTS].map(con => (
          <DCArtboard key={con.id} id={con.id} label={con.label} width={500} height={300}>
            <ArtboardBody concept={con} />
          </DCArtboard>
        ))}
      </DCSection>
    </DesignCanvas>
  );
}

const __root = ReactDOM.createRoot(document.getElementById('root'));
__root.render(<FeedLogos />);

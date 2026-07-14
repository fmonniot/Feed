// Feed search — Android Feeds-tab placement explorations.
// Interactive frames for the design canvas. Everything is built from the Paper
// tokens (ED_PALETTES.paper), the two families (edSerifFont / edUiFont), and
// the seed FEEDS from data.jsx — so each option reads as the real product,
// only the search treatment changes.
//
// Behaviour intent held constant across options (per session brief):
//   search feeds within the current category, with a way to widen to All.
// Placement is what varies. Each frame is live: type in the box, tap the scope
// pill / chips to narrow ↔ widen, and (B) scroll to feel the real-estate save.

const P = ED_PALETTES.paper;
const UI = edUiFont;
const SERIF = edSerifFont;

// Category order as it appears in the grouped Feeds list.
const FS_FOLDERS = [...new Set(FEEDS.map((f) => f.folder))];
const FS_SCOPES = ['all', ...FS_FOLDERS];

// Shared search state: query + a scope that cycles all → each folder → all.
function useFeedSearch(initialScope = 'all') {
  const [q, setQ] = React.useState('');
  const [scope, setScope] = React.useState(initialScope);
  const cycle = () => setScope((s) => FS_SCOPES[(FS_SCOPES.indexOf(s) + 1) % FS_SCOPES.length]);
  const label = scope === 'all' ? 'All' : scope;
  const placeholder = scope === 'all' ? 'Search all feeds…' : `Search ${scope}…`;
  return { q, setQ, scope, setScope, cycle, label, placeholder };
}

// Focus without letting the browser yank the pan/zoom canvas around.
function useSafeFocus(active) {
  const ref = React.useRef(null);
  React.useEffect(() => {
    if (active && ref.current) {
      try { ref.current.focus({ preventScroll: true }); } catch (e) { ref.current.focus(); }
    }
  }, [active]);
  return ref;
}

// ── shared atoms ─────────────────────────────────────────────────────
function FsAvatar({ f, size = 34 }) {
  return (
    <div style={{
      width: size, height: size, borderRadius: 4, flex: '0 0 auto',
      background: `oklch(0.85 0.05 ${f.hue})`, color: `oklch(0.35 0.08 ${f.hue})`,
      display: 'flex', alignItems: 'center', justifyContent: 'center',
      fontFamily: SERIF, fontWeight: 500, fontSize: Math.round(size * 0.44),
    }}>{f.name[0]}</div>
  );
}

function FsFeedRow({ f, last }) {
  return (
    <div style={{
      display: 'flex', alignItems: 'center', gap: 14, padding: '13px 22px',
      background: P.bg, borderBottom: last ? 'none' : `1px solid ${P.border}`,
    }}>
      <FsAvatar f={f} size={34} />
      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={{ fontFamily: SERIF, fontSize: 15, fontWeight: 500, color: P.ink }}>{f.name}</div>
        <div style={{ fontSize: 11, color: P.ink3, marginTop: 2,
          overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{f.url}</div>
      </div>
      <span style={{ fontSize: 11, color: P.ink3, fontVariantNumeric: 'tabular-nums' }}>{f.unread}</span>
    </div>
  );
}

// Grouped list. `scope` = folder name or 'all'. `query` filters by name.
function FsGroupedList({ scope = 'all', query = '' }) {
  const ql = query.trim().toLowerCase();
  const folders = scope === 'all' ? FS_FOLDERS : [scope];
  const groups = folders.map((folder) => ({
    folder,
    rows: FEEDS.filter((f) => f.folder === folder && f.name.toLowerCase().includes(ql)),
  })).filter((g) => (ql ? g.rows.length > 0 : true));

  if (groups.every((g) => g.rows.length === 0)) {
    return (
      <div style={{ padding: '44px 22px', textAlign: 'center', fontFamily: SERIF,
        fontStyle: 'italic', fontSize: 15, color: P.ink3 }}>Nothing here yet.</div>
    );
  }
  return (
    <React.Fragment>
      {groups.map((g) => (
        <div key={g.folder}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 10, padding: '20px 22px 6px' }}>
            <span style={{ fontFamily: UI, fontSize: 10, letterSpacing: '.1em', textTransform: 'uppercase',
              color: P.ink3, fontWeight: 500 }}>{g.folder}</span>
            <span style={{ fontSize: 10.5, color: P.ink3, fontVariantNumeric: 'tabular-nums' }}>{g.rows.length}</span>
          </div>
          {g.rows.map((f, i, arr) => <FsFeedRow key={f.id} f={f} last={i === arr.length - 1} />)}
        </div>
      ))}
    </React.Fragment>
  );
}

// The canonical search box (VISUAL_SPEC §Mobile · Feeds), now a live input.
function FsSearchInput({ value, onChange, placeholder, inputRef, scopePill, strong, onClear }) {
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '10px 14px',
      border: `1px solid ${strong ? P.borderStrong : P.border}`, borderRadius: 4, background: P.panel }}>
      <span style={{ color: P.ink3, fontSize: 14 }}>⌕</span>
      <input ref={inputRef} value={value} onChange={(e) => onChange(e.target.value)} placeholder={placeholder}
        style={{ all: 'unset', flex: 1, minWidth: 0, fontFamily: UI, fontSize: 13, color: P.ink }} />
      {value ? (
        <button onClick={onClear} aria-label="Clear search"
          style={{ all: 'unset', cursor: 'pointer', color: P.ink3, fontSize: 13, padding: '0 2px' }}>✕</button>
      ) : null}
      {scopePill}
    </div>
  );
}

// A quiet, tappable scope selector pill: cycles "All ▾" → "Craft ▾" → …
function FsScopePill({ label, onClick }) {
  return (
    <button onClick={onClick} title="Change scope"
      style={{ all: 'unset', cursor: 'pointer', display: 'inline-flex', alignItems: 'center', gap: 4,
        flex: '0 0 auto', padding: '3px 8px', borderRadius: 999, border: `1px solid ${P.border}`,
        background: P.bg, fontFamily: UI, fontSize: 11, color: P.ink2 }}>
      {label}<span style={{ color: P.ink3, fontSize: 9 }}>▾</span>
    </button>
  );
}

function FsAppBarBtn({ glyph, active, onClick }) {
  return (
    <button onClick={onClick} style={{
      all: 'unset', cursor: onClick ? 'pointer' : 'default',
      width: 32, height: 32, borderRadius: 4, flex: '0 0 auto', boxSizing: 'border-box',
      border: `1px solid ${active ? P.borderStrong : P.border}`,
      background: active ? P.accentSoft : P.panel, color: active ? P.accent : P.ink2,
      display: 'inline-flex', alignItems: 'center', justifyContent: 'center', fontSize: 14,
    }}>{glyph}</button>
  );
}

function FsHeader({ subtitle, right, topInset = 10 }) {
  return (
    <div style={{ paddingTop: topInset + 14, paddingLeft: 22, paddingRight: 22, paddingBottom: 16,
      background: P.bg, borderBottom: `1px solid ${P.border}`, flex: '0 0 auto',
      display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', gap: 12 }}>
      <div style={{ minWidth: 0 }}>
        <h1 style={{ fontFamily: SERIF, fontSize: 30, fontWeight: 500, letterSpacing: '-.02em',
          lineHeight: 1.05, margin: 0, color: P.ink }}>Feeds</h1>
        {subtitle ? <div style={{ fontFamily: UI, fontSize: 12, color: P.ink3, marginTop: 6 }}>{subtitle}</div> : null}
      </div>
      {right ? <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>{right}</div> : null}
    </div>
  );
}

// Bottom tab bar — the defining mobile chrome (VISUAL_SPEC §Tab bar).
function FsTabBar() {
  const tabs = [
    { g: '◉', l: 'Unread' }, { g: '☰', l: 'All' },
    { g: '⌒', l: 'Feeds', active: true }, { g: '◌', l: 'Settings' },
  ];
  return (
    <div style={{
      position: 'absolute', left: 0, right: 0, bottom: 0, zIndex: 20,
      display: 'flex', paddingTop: 6, paddingBottom: 14, borderTop: `1px solid ${P.border}`,
      background: 'rgba(249,250,251,0.94)', backdropFilter: 'blur(24px)', WebkitBackdropFilter: 'blur(24px)',
    }}>
      {tabs.map((t) => (
        <div key={t.l} style={{ flex: 1, display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 3 }}>
          <span style={{ padding: '4px 18px', borderRadius: 999, fontFamily: SERIF, fontSize: 18,
            color: t.active ? P.accent : P.ink3, background: t.active ? P.accentSoft : 'transparent' }}>{t.g}</span>
          <span style={{ fontFamily: UI, fontSize: 10, fontWeight: t.active ? 600 : 500,
            color: t.active ? P.accent : P.ink3 }}>{t.l}</span>
        </div>
      ))}
    </div>
  );
}

// A compact on-screen keyboard (reuses the device-frame Gboard art) shown
// inside a frame while an input is focused, so search-mode reads as real.
function FsKeyboard() {
  return (
    <div style={{ position: 'absolute', left: 0, right: 0, bottom: 0, zIndex: 30 }}>
      <AndroidKeyboard />
    </div>
  );
}

// Screen frame — relative box so the tab bar / docked bar can pin.
function FsScreen({ children, tabBar = true }) {
  return (
    <div style={{ position: 'relative', height: '100%', display: 'flex', flexDirection: 'column',
      background: P.bg, fontFamily: UI, overflow: 'hidden' }}>
      {children}
      {tabBar ? <FsTabBar /> : null}
    </div>
  );
}

const fsSub = `${FEEDS.length} subscriptions · ${FS_FOLDERS.length} categories`;

// A small floating annotation used to point out a detail in a frame.
function FsPin({ children, style }) {
  return (
    <div style={{ position: 'absolute', zIndex: 40, fontFamily: UI, fontSize: 11, lineHeight: 1.35,
      color: P.ink2, background: P.panel, border: `1px solid ${P.borderStrong}`, borderRadius: 6,
      padding: '8px 10px', boxShadow: '0 8px 24px rgba(0,0,0,.10)', maxWidth: 210, ...style }}>
      {children}
    </div>
  );
}

// ═══════════════════════════════════════════════════════════════════════
// CURRENT · search hidden behind the ⌕ toggle (the awkward status quo)
// ═══════════════════════════════════════════════════════════════════════
function FsCurrent() {
  const [open, setOpen] = React.useState(false);
  const s = useFeedSearch('all');
  const inputRef = useSafeFocus(open);
  return (
    <FsScreen>
      <FsHeader subtitle={fsSub} right={
        <React.Fragment>
          <FsAppBarBtn glyph="⌕" active={open} onClick={() => setOpen((v) => !v)} />
          <FsAppBarBtn glyph="+" onClick={() => {}} />
          <FsAppBarBtn glyph="⋯" onClick={() => {}} />
        </React.Fragment>
      } />
      {open ? (
        <div style={{ padding: '12px 22px 4px', flex: '0 0 auto' }}>
          <FsSearchInput value={s.q} onChange={s.setQ} onClear={() => s.setQ('')}
            inputRef={inputRef} placeholder="Search feeds…" strong />
        </div>
      ) : null}
      <div style={{ flex: 1, overflow: 'auto', paddingBottom: 90 }}>
        <FsGroupedList scope="all" query={open ? s.q : ''} />
      </div>
      {!open ? (
        <FsPin style={{ top: 150, right: 12 }}>
          Search hides inside <b>⌕</b> — one of three same-looking icons. Tap it
          to reveal the box; today it competes with <b>+</b> and <b>⋯</b>.
        </FsPin>
      ) : null}
    </FsScreen>
  );
}

// ═══════════════════════════════════════════════════════════════════════
// A · persistent search bar under the header  (safe)
// ═══════════════════════════════════════════════════════════════════════
function FsOptionA() {
  const s = useFeedSearch('all');
  return (
    <FsScreen>
      <FsHeader subtitle={fsSub} right={
        <React.Fragment>
          <FsAppBarBtn glyph="+" onClick={() => {}} />
          <FsAppBarBtn glyph="⋯" onClick={() => {}} />
        </React.Fragment>
      } />
      <div style={{ padding: '14px 22px 4px', flex: '0 0 auto' }}>
        <FsSearchInput value={s.q} onChange={s.setQ} onClear={() => s.setQ('')}
          placeholder={s.placeholder} scopePill={<FsScopePill label={s.label} onClick={s.cycle} />} />
      </div>
      <div style={{ flex: 1, overflow: 'auto', paddingBottom: 90 }}>
        <FsGroupedList scope={s.scope} query={s.q} />
      </div>
    </FsScreen>
  );
}

// ═══════════════════════════════════════════════════════════════════════
// B · search pins to the top while the list scrolls under it
// ═══════════════════════════════════════════════════════════════════════
function FsOptionB() {
  const s = useFeedSearch('all');
  const scrollRef = React.useRef(null);
  const [scrolled, setScrolled] = React.useState(false);
  const onScroll = (e) => setScrolled(e.target.scrollTop > 40);
  return (
    <FsScreen>
      <div ref={scrollRef} onScroll={onScroll} style={{ flex: 1, overflow: 'auto', paddingBottom: 90 }}>
        {/* big title scrolls away */}
        <div style={{ padding: '24px 22px 16px' }}>
          <h1 style={{ fontFamily: SERIF, fontSize: 30, fontWeight: 500, letterSpacing: '-.02em',
            lineHeight: 1.05, margin: 0, color: P.ink }}>Feeds</h1>
          <div style={{ fontFamily: UI, fontSize: 12, color: P.ink3, marginTop: 6 }}>{fsSub}</div>
        </div>
        {/* sticky search — pins under the top edge as you scroll */}
        <div style={{ position: 'sticky', top: 0, zIndex: 10, background: P.bg,
          padding: '8px 22px 12px', borderBottom: `1px solid ${P.border}`,
          boxShadow: scrolled ? '0 6px 12px -8px rgba(20,25,40,.35)' : 'none',
          transition: 'box-shadow .15s' }}>
          <FsSearchInput value={s.q} onChange={s.setQ} onClear={() => s.setQ('')}
            placeholder={s.placeholder} scopePill={<FsScopePill label={s.label} onClick={s.cycle} />} />
        </div>
        <FsGroupedList scope={s.scope} query={s.q} />
      </div>
      {!scrolled && !s.q ? (
        <FsPin style={{ top: 96, right: 12 }}>
          Scroll ↑ — the big title gives up its space and the <b>search bar pins</b>
          to the top, so it's always one tap away without a permanent header.
        </FsPin>
      ) : null}
    </FsScreen>
  );
}

// ═══════════════════════════════════════════════════════════════════════
// C · refined ⌕ toggle → full-width search mode  (keeps the toggle)
// ═══════════════════════════════════════════════════════════════════════
function FsOptionC() {
  const [mode, setMode] = React.useState(false);
  const s = useFeedSearch('all');
  const inputRef = useSafeFocus(mode);
  const enter = () => setMode(true);
  const exit = () => { setMode(false); s.setQ(''); };

  if (!mode) {
    return (
      <FsScreen>
        <FsHeader subtitle={fsSub} right={
          <React.Fragment>
            <FsAppBarBtn glyph="⌕" onClick={enter} />
            <FsAppBarBtn glyph="+" onClick={() => {}} />
            <FsAppBarBtn glyph="⋯" onClick={() => {}} />
          </React.Fragment>
        } />
        <div style={{ flex: 1, overflow: 'auto', paddingBottom: 90 }}>
          <FsGroupedList scope="all" />
        </div>
        <FsPin style={{ top: 150, right: 12 }}>
          Tap <b>⌕</b> — instead of a cramped inline box it opens a full search
          mode with the keyboard up. This is the resting state.
        </FsPin>
      </FsScreen>
    );
  }
  const matchCount = FEEDS.filter((f) => f.name.toLowerCase().includes(s.q.trim().toLowerCase())).length;
  return (
    <FsScreen tabBar={false}>
      {/* search-mode top bar replaces the title header */}
      <div style={{ paddingTop: 24, paddingLeft: 12, paddingRight: 16, paddingBottom: 12,
        background: P.bg, borderBottom: `1px solid ${P.border}`, flex: '0 0 auto',
        display: 'flex', alignItems: 'center', gap: 8 }}>
        <button onClick={exit} aria-label="Back"
          style={{ all: 'unset', cursor: 'pointer', color: P.accent, fontSize: 22, lineHeight: 1, padding: '0 4px' }}>‹</button>
        <div style={{ flex: 1 }}>
          <FsSearchInput value={s.q} onChange={s.setQ} onClear={() => s.setQ('')}
            inputRef={inputRef} placeholder="Search feeds…" strong />
        </div>
      </div>
      <div style={{ padding: '10px 22px 2px', flex: '0 0 auto' }}>
        <span style={{ fontFamily: UI, fontSize: 11, color: P.ink3 }}>
          Searching <b style={{ color: P.ink2 }}>All feeds</b>
          {s.q ? ` · ${matchCount} ${matchCount === 1 ? 'match' : 'matches'}` : ''}
        </span>
      </div>
      <div style={{ flex: 1, overflow: 'auto', paddingBottom: 260 }}>
        <FsGroupedList scope="all" query={s.q} />
      </div>
      <FsKeyboard />
    </FsScreen>
  );
}

// ═══════════════════════════════════════════════════════════════════════
// D · scoped search with category chips  (widen ↔ narrow)
// ═══════════════════════════════════════════════════════════════════════
function FsOptionD() {
  const s = useFeedSearch('Craft');
  const chips = ['all', ...FS_FOLDERS];
  return (
    <FsScreen>
      <FsHeader subtitle={fsSub} right={
        <React.Fragment>
          <FsAppBarBtn glyph="+" onClick={() => {}} />
          <FsAppBarBtn glyph="⋯" onClick={() => {}} />
        </React.Fragment>
      } />
      <div style={{ padding: '14px 22px 4px', flex: '0 0 auto' }}>
        <FsSearchInput value={s.q} onChange={s.setQ} onClear={() => s.setQ('')} placeholder={s.placeholder} />
      </div>
      <div style={{ display: 'flex', gap: 8, padding: '10px 22px 12px', overflowX: 'auto',
        flex: '0 0 auto', borderBottom: `1px solid ${P.border}` }}>
        {chips.map((c) => {
          const on = c === s.scope;
          return (
            <button key={c} onClick={() => s.setScope(c)} style={{ all: 'unset', cursor: 'pointer',
              flex: '0 0 auto', padding: '5px 12px', borderRadius: 999, fontFamily: UI, fontSize: 12,
              border: `1px solid ${on ? P.ink : P.border}`,
              background: on ? P.ink : P.panel, color: on ? P.panel : P.ink2 }}>
              {c === 'all' ? 'All' : c}
            </button>
          );
        })}
      </div>
      <div style={{ flex: 1, overflow: 'auto', paddingBottom: 90 }}>
        <FsGroupedList scope={s.scope} query={s.q} />
      </div>
      <FsPin style={{ top: 158, right: 12 }}>
        Tap a chip to scope the search to one category, or <b>All</b> to widen.
        The box placeholder tracks the active scope.
      </FsPin>
    </FsScreen>
  );
}

// ═══════════════════════════════════════════════════════════════════════
// E · search docked in thumb reach, above the tabs  (inventive)
// ═══════════════════════════════════════════════════════════════════════
function FsOptionE() {
  const [open, setOpen] = React.useState(false);
  const s = useFeedSearch('all');
  const inputRef = useSafeFocus(open);
  return (
    <FsScreen tabBar={!open}>
      <FsHeader subtitle={fsSub} right={
        <React.Fragment>
          <FsAppBarBtn glyph="+" onClick={() => {}} />
          <FsAppBarBtn glyph="⋯" onClick={() => {}} />
        </React.Fragment>
      } />
      <div style={{ flex: 1, overflow: 'auto', paddingBottom: open ? 20 : 150 }}>
        <FsGroupedList scope={s.scope} query={open ? s.q : ''} />
      </div>

      {open ? (
        <React.Fragment>
          <div style={{ position: 'absolute', left: 12, right: 12, bottom: 262, zIndex: 35 }}>
            <div style={{ boxShadow: '0 10px 28px rgba(20,25,40,.18)', borderRadius: 4 }}>
              <FsSearchInput value={s.q} onChange={s.setQ} inputRef={inputRef}
                onClear={() => { s.setQ(''); setOpen(false); }} placeholder={s.placeholder} strong
                scopePill={<FsScopePill label={s.label} onClick={s.cycle} />} />
            </div>
          </div>
          <FsKeyboard />
        </React.Fragment>
      ) : (
        <div onClick={() => setOpen(true)} style={{ cursor: 'pointer',
          position: 'absolute', left: 12, right: 12, bottom: 78, zIndex: 25 }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '11px 14px',
            border: `1px solid ${P.borderStrong}`, borderRadius: 4, background: 'rgba(249,250,251,0.94)',
            backdropFilter: 'blur(24px)', WebkitBackdropFilter: 'blur(24px)',
            boxShadow: '0 10px 28px rgba(20,25,40,.16)' }}>
            <span style={{ color: P.ink3, fontSize: 14 }}>⌕</span>
            <span style={{ flex: 1, fontFamily: UI, fontSize: 13, color: P.ink3 }}>Search feeds…</span>
            <FsScopePill label={s.label} onClick={(e) => { e.stopPropagation(); s.cycle(); }} />
          </div>
        </div>
      )}

      {!open ? (
        <FsPin style={{ top: 66, right: 12 }}>
          Search rides just above the tabs — reachable one-handed on a tall
          phone. Tap to raise it with the keyboard.
        </FsPin>
      ) : null}
    </FsScreen>
  );
}

Object.assign(window, {
  FsCurrent, FsOptionA, FsOptionB, FsOptionC, FsOptionD, FsOptionE,
});

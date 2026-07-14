// Category management — exploration (Android / Paper palette).
// Mobile counterpart to category-mgmt-web.jsx. Self-contained shell (own
// header + tab bar, matching editorial-mobile.jsx styling) so it doesn't
// depend on un-exported atoms from other modules.
//
// Three approaches to MOVING a feed on mobile (answered scope), then category
// CRUD + bulk selection.

const CMM = ED_PALETTES.paper;

function cmmGroups(feeds) {
  const order = ['Craft', 'Tech', 'Reading'];
  const named = order.map(name => ({ name, id: name.toLowerCase(), feeds: feeds.filter(f => f.folder === name) }));
  named.push({ name: 'Uncategorized', id: 'uncat', locked: true, feeds: feeds.filter(f => !order.includes(f.folder)) });
  return named;
}

// ── shell chrome ────────────────────────────────────────────────────
function CMMHeader({ title, subtitle, right, topInset = 14 }) {
  return (
    <div style={{
      paddingTop: topInset + 14, paddingLeft: 22, paddingRight: 22, paddingBottom: 18,
      background: CMM.bg, borderBottom: `1px solid ${CMM.border}`, fontFamily: edUiFont, color: CMM.ink,
      flex: '0 0 auto', display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', gap: 12,
    }}>
      <div style={{ minWidth: 0 }}>
        <h1 style={{ fontFamily: edSerifFont, fontSize: 30, fontWeight: 500, letterSpacing: '-.02em', lineHeight: 1.05, margin: 0 }}>{title}</h1>
        {subtitle ? <div style={{ fontSize: 12, color: CMM.ink3, marginTop: 6 }}>{subtitle}</div> : null}
      </div>
      {right}
    </div>
  );
}

// App-bar icon row — same shape/order as the live prototype's Feeds tab
// (search toggle · add feed · overflow), rendered static since these
// artboards are frozen snapshots of a single scenario.
function CMMAppBarActions() {
  const btn = {
    all: 'unset', width: 32, height: 32, borderRadius: 4,
    border: `1px solid ${CMM.border}`, background: CMM.panel, color: CMM.ink2,
    display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
    fontSize: 14, flexShrink: 0,
  };
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
      <button style={btn} aria-label="Search feeds" title="Search feeds">⌕</button>
      <button style={btn} aria-label="Add feed" title="Add feed">+</button>
      <button style={btn} aria-label="More actions" title="More actions">⋯</button>
    </div>
  );
}

function CMMTabBar({ active = 'feeds' }) {
  const items = [
    { id: 'unread', label: 'Unread', glyph: '◉' },
    { id: 'all', label: 'All', glyph: '☰' },
    { id: 'feeds', label: 'Feeds', glyph: '⌒' },
    { id: 'settings', label: 'Settings', glyph: '◌' },
  ];
  return (
    <div style={{
      position: 'absolute', left: 0, right: 0, bottom: 0, padding: '6px 0 30px',
      borderTop: `1px solid ${CMM.border}`, background: 'rgba(249, 250, 251, 0.94)',
      backdropFilter: 'blur(24px)', WebkitBackdropFilter: 'blur(24px)', zIndex: 20, display: 'flex', fontFamily: edUiFont,
    }}>
      {items.map(t => {
        const on = t.id === active;
        return (
          <div key={t.id} style={{ flex: 1, display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 3, color: on ? CMM.accent : CMM.ink3 }}>
            <div style={{ padding: '4px 18px', borderRadius: 999, background: on ? CMM.accentSoft : 'transparent', fontFamily: edSerifFont, fontSize: 18, lineHeight: 1 }}>{t.glyph}</div>
            <div style={{ fontSize: 10, fontWeight: on ? 600 : 500 }}>{t.label}</div>
          </div>
        );
      })}
    </div>
  );
}

function CMMShell({ header, children, tabBar = true, scrim, sheet, hideTab = false }) {
  return (
    <AndroidDevice width={412} height={892}>
      <EdThemeContext.Provider value={CMM}>
        <div style={{ position: 'relative', width: '100%', height: '100%', background: CMM.bg, fontFamily: edUiFont, display: 'flex', flexDirection: 'column', overflow: 'hidden' }}>
          {header}
          <div style={{ flex: 1, minHeight: 0, overflow: 'auto', paddingBottom: hideTab ? 24 : 100 }}>{children}</div>
          {!hideTab && tabBar ? <CMMTabBar /> : null}
          {scrim}
          {sheet}
        </div>
      </EdThemeContext.Provider>
    </AndroidDevice>
  );
}

// ── atoms ───────────────────────────────────────────────────────────
function CMMAvatar({ f, size = 34, dim = false }) {
  return (
    <div style={{
      width: size, height: size, borderRadius: 4, flex: '0 0 auto',
      background: `oklch(0.85 0.05 ${f.hue})`, color: `oklch(0.35 0.08 ${f.hue})`,
      display: 'flex', alignItems: 'center', justifyContent: 'center',
      fontFamily: edSerifFont, fontWeight: 500, fontSize: Math.round(size * 0.44), opacity: dim ? 0.6 : 1,
    }}>{f.name[0]}</div>
  );
}

const cmmHeaderLabel = { fontFamily: edUiFont, fontSize: 10, letterSpacing: '.1em', textTransform: 'uppercase', color: CMM.ink3, fontWeight: 500 };
const cmmBtnGhost = { all: 'unset', cursor: 'pointer', fontFamily: edUiFont, fontSize: 13, color: CMM.accent, padding: '4px 6px' };

function CMMFolderHeader({ cat, organize }) {
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 10, padding: '20px 22px 6px' }}>
      <span style={cmmHeaderLabel}>{cat.name}</span>
      <span style={{ fontSize: 10.5, color: CMM.ink3, fontVariantNumeric: 'tabular-nums' }}>{cat.feeds.length}</span>
      <span style={{ flex: 1 }} />
      {organize && !cat.locked ? <span style={{ color: CMM.ink3, fontSize: 16 }}>⋯</span> : null}
    </div>
  );
}

// feed row variants
function CMMFeedRow({ f, last, trailing, pressed, dim, lifted, dropTarget }) {
  return (
    <div style={{
      display: 'flex', alignItems: 'center', gap: 14, padding: '13px 22px',
      borderBottom: last ? 'none' : `1px solid ${CMM.border}`,
      background: lifted ? CMM.panel : (pressed ? CMM.accentSoft : CMM.bg),
      boxShadow: lifted ? '0 10px 28px rgba(0,0,0,.14)' : 'none',
      outline: dropTarget ? `2px solid ${CMM.accent}` : 'none', outlineOffset: -2,
    }}>
      {trailing && trailing.lead ? trailing.lead : null}
      <CMMAvatar f={f} dim={dim} />
      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={{ fontFamily: edSerifFont, fontSize: 15, fontWeight: 500, color: CMM.ink }}>{f.name}</div>
        <div style={{ fontSize: 11, color: CMM.ink3, marginTop: 2, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{f.url}</div>
      </div>
      {trailing && trailing.tail ? trailing.tail : null}
    </div>
  );
}

// ── bottom sheet ────────────────────────────────────────────────────
function CMMScrim() {
  return <div style={{ position: 'absolute', inset: 0, background: 'rgba(20,25,40,.32)', backdropFilter: 'blur(2px)', zIndex: 40 }} />;
}

function CMMSheet({ title, children, primary, primaryDanger, secondary = 'Cancel' }) {
  return (
    <div style={{
      position: 'absolute', left: 0, right: 0, bottom: 0, zIndex: 50,
      background: CMM.bg, borderTop: `1px solid ${CMM.borderStrong}`,
      borderTopLeftRadius: 14, borderTopRightRadius: 14,
      boxShadow: '0 -12px 40px rgba(0,0,0,.16)', padding: '10px 0 30px', fontFamily: edUiFont,
    }}>
      <div style={{ width: 36, height: 4, borderRadius: 2, background: CMM.border, margin: '0 auto 14px' }} />
      {title ? <div style={{ padding: '0 22px 10px', fontFamily: edSerifFont, fontSize: 20, fontWeight: 500, letterSpacing: '-.015em', color: CMM.ink }}>{title}</div> : null}
      {children}
      {(primary || secondary) ? (
        <div style={{ display: 'flex', gap: 10, padding: '14px 22px 0' }}>
          <button style={{ all: 'unset', cursor: 'pointer', flex: 1, textAlign: 'center', padding: '12px 0', borderRadius: 4, border: `1px solid ${CMM.border}`, background: CMM.panel, color: CMM.ink2, fontSize: 14 }}>{secondary}</button>
          {primary ? (
            <button style={{ all: 'unset', cursor: 'pointer', flex: 1, textAlign: 'center', padding: '12px 0', borderRadius: 4,
              background: primaryDanger ? CMM.panel : CMM.ink, color: primaryDanger ? CMM.danger : CMM.panel,
              border: primaryDanger ? `1px solid ${CMM.danger}` : 'none', fontSize: 14 }}>{primary}</button>
          ) : null}
        </div>
      ) : null}
    </div>
  );
}

function CMMRadioRow({ cat, active, onCurrent }) {
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 12, padding: '12px 22px', background: active ? CMM.accentSoft : 'transparent' }}>
      <span style={{ width: 18, height: 18, borderRadius: '50%', flex: '0 0 auto',
        border: `1px solid ${active ? CMM.accent : CMM.borderStrong}`, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
        {active ? <span style={{ width: 9, height: 9, borderRadius: '50%', background: CMM.accent }} /> : null}
      </span>
      <span style={{ flex: 1, fontFamily: edSerifFont, fontSize: 16, fontWeight: 500, color: active ? CMM.accent : CMM.ink }}>{cat.name}</span>
      {onCurrent ? <span style={{ fontSize: 11, color: CMM.ink3, fontStyle: 'italic', fontFamily: edSerifFont }}>current</span> : null}
      {cat.locked ? <span style={{ fontSize: 11, color: CMM.ink3, fontStyle: 'italic', fontFamily: edSerifFont }}>default</span> : null}
    </div>
  );
}

// plain browse list (read-only), optional per-row trailing content
function CMMBrowseList({ organize, trailingFor }) {
  const groups = cmmGroups(FEEDS);
  return (
    <React.Fragment>
      {groups.map(cat => (
        <div key={cat.id}>
          <CMMFolderHeader cat={cat} organize={organize} />
          {cat.feeds.length === 0 ? (
            <div style={{ padding: '10px 22px 4px', fontFamily: edSerifFont, fontStyle: 'italic', fontSize: 13.5, color: CMM.ink3 }}>Nothing here yet.</div>
          ) : cat.feeds.map((f, i, arr) => (
            <CMMFeedRow key={f.id} f={f} last={i === arr.length - 1} trailing={trailingFor ? trailingFor(f, cat) : { tail: <span style={{ fontSize: 11, color: CMM.ink3 }}>{f.unread}</span> }} />
          ))}
        </div>
      ))}
    </React.Fragment>
  );
}

// ════════════════════════════════════════════════════════════════════
// SECTION 3 · moving a feed — the decided flow (overflow menu → move sheet)
// ════════════════════════════════════════════════════════════════════

// the feed's existing overflow menu, with "Move to category…" slotted in
const CMM_FEED_MENU = [
  { label: 'Refresh now' },
  { label: 'Move to category…', trigger: true },
  { label: 'Rename…' },
  { label: 'Fetch interval…', tail: 'Hourly' },
  { label: 'Pause updates' },
  { divider: true },
  { label: 'Unsubscribe', danger: true },
];

// STEP 1 · tap ⋯ on a feed → its existing menu; "Move to category…" is the entry point
function CMMMoveMenu() {
  const groups = cmmGroups(FEEDS);
  return (
    <CMMShell header={<CMMHeader title="Feeds" subtitle={`${FEEDS.length} subscriptions · ${groups.length} categories`} right={<CMMAppBarActions />} />}>
      <CMMBrowseList trailingFor={(f) => {
        if (f.id !== 'theloop') return { tail: <span style={{ fontSize: 16, color: CMM.ink3, padding: '0 4px' }}>⋯</span> };
        return {
          tail: (
            <div style={{ position: 'relative' }}>
              <span style={{ fontSize: 16, color: CMM.ink2, padding: '0 4px' }}>⋯</span>
              <div style={{ position: 'absolute', right: 0, top: 26, zIndex: 45,
                background: CMM.panel, border: `1px solid ${CMM.borderStrong}`, borderRadius: 6,
                boxShadow: '0 10px 28px rgba(0,0,0,.14)', minWidth: 210, padding: 4 }}>
                {CMM_FEED_MENU.map((it, i) => it.divider ? (
                  <div key={i} style={{ height: 1, background: CMM.border, margin: '4px 6px' }} />
                ) : (
                  <div key={i} style={{
                    padding: '11px 14px', fontSize: 14, borderRadius: 4,
                    display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 10,
                    color: it.danger ? CMM.danger : (it.trigger ? CMM.accent : CMM.ink),
                    background: it.trigger ? CMM.accentSoft : 'transparent',
                    fontWeight: it.trigger ? 600 : 400,
                  }}>
                    <span>{it.label}</span>
                    {it.trigger ? <span style={{ fontSize: 13 }}>›</span> : (it.tail ? <span style={{ fontSize: 12, color: CMM.ink3 }}>{it.tail}</span> : null)}
                  </div>
                ))}
              </div>
            </div>
          ),
        };
      }} />
    </CMMShell>
  );
}

// STEP 2 · "Move to category…" opens the bottom sheet — pick a home (or make one)
function CMMMoveSheet() {
  const groups = cmmGroups(FEEDS);
  return (
    <CMMShell
      header={<CMMHeader title="Feeds" subtitle={`${FEEDS.length} subscriptions · ${groups.length} categories`} right={<CMMAppBarActions />} />}
      scrim={<CMMScrim />}
      sheet={
        <CMMSheet title="Move “The Loop”" primary="Move">
          {groups.map(cat => <CMMRadioRow key={cat.id} cat={cat} active={cat.id === 'tech'} onCurrent={cat.id === 'tech'} />)}
          <div style={{ padding: '12px 22px', fontFamily: edUiFont, fontSize: 14, color: CMM.ink2 }}>+ New category…</div>
        </CMMSheet>
      }
    >
      <CMMBrowseList trailingFor={(f) => ({ tail: <span style={{ fontSize: 11, color: CMM.ink3 }}>{f.unread}</span> })} />
    </CMMShell>
  );
}

// ════════════════════════════════════════════════════════════════════
// SECTION 4 · category CRUD + bulk
// ════════════════════════════════════════════════════════════════════

// edit mode resting — category headers get rename / delete via ⋯, + New category
function CMMEditMode() {
  const groups = cmmGroups(FEEDS);
  return (
    <CMMShell header={<CMMHeader title="Feeds" subtitle="Organizing" right={<button style={{ ...cmmBtnGhost, fontWeight: 600 }}>Done</button>} />}>
      {groups.map(cat => (
        <div key={cat.id}>
          <CMMFolderHeader cat={cat} organize />
          {cat.feeds.length === 0 ? (
            <div style={{ padding: '10px 22px', fontFamily: edSerifFont, fontStyle: 'italic', fontSize: 13.5, color: CMM.ink3 }}>Nothing here yet.</div>
          ) : cat.feeds.map((f, i, arr) => (
            <CMMFeedRow key={f.id} f={f} last={i === arr.length - 1} trailing={{ tail: <span style={{ fontSize: 16, color: CMM.ink3, padding: '0 4px' }}>⋯</span> }} />
          ))}
        </div>
      ))}
      <div style={{ padding: '18px 22px 4px' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '12px 14px', border: `1px dashed ${CMM.borderStrong}`, borderRadius: 6, color: CMM.ink3, fontSize: 13.5 }}>+ New category</div>
      </div>
    </CMMShell>
  );
}

// new category sheet
function CMMNewCategory() {
  return (
    <CMMShell
      header={<CMMHeader title="Feeds" subtitle="Organizing" right={<button style={{ ...cmmBtnGhost, fontWeight: 600 }}>Done</button>} />}
      scrim={<CMMScrim />}
      sheet={
        <CMMSheet title="New category" primary="Create">
          <div style={{ padding: '4px 22px' }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '12px 14px', border: `1px solid ${CMM.borderStrong}`, borderRadius: 4, background: CMM.panel }}>
              <input autoFocus placeholder="Category name…" style={{ all: 'unset', flex: 1, fontSize: 15, color: CMM.ink, fontFamily: edUiFont }} />
            </div>
            <div style={{ fontSize: 12, color: CMM.ink3, marginTop: 8, fontFamily: edUiFont }}>New categories appear in the list; move feeds in from each feed’s ⋯ menu afterward.</div>
          </div>
        </CMMSheet>
      }
    >
      <CMMBrowseList organize />
    </CMMShell>
  );
}

// delete → reassign sheet
function CMMDeleteReassign() {
  const groups = cmmGroups(FEEDS);
  const targets = groups.filter(g => g.id !== 'reading');
  return (
    <CMMShell
      header={<CMMHeader title="Feeds" subtitle="Organizing" right={<button style={{ ...cmmBtnGhost, fontWeight: 600 }}>Done</button>} />}
      scrim={<CMMScrim />}
      sheet={
        <CMMSheet title="Delete “Reading”?" primary="Delete & move" primaryDanger>
          <div style={{ padding: '0 22px 6px', fontFamily: edSerifFont, fontStyle: 'italic', fontSize: 14, color: CMM.ink2, lineHeight: 1.5 }}>
            The 4 feeds are kept — pick where they go. Nothing is unsubscribed.
          </div>
          <div style={{ ...cmmHeaderLabel, padding: '12px 22px 4px' }}>Move its feeds to</div>
          {targets.map(cat => <CMMRadioRow key={cat.id} cat={cat} active={cat.locked} />)}
        </CMMSheet>
      }
    >
      <CMMBrowseList organize />
    </CMMShell>
  );
}

Object.assign(window, {
  CMMMoveMenu, CMMMoveSheet,
  CMMEditMode, CMMNewCategory, CMMDeleteReassign,
});

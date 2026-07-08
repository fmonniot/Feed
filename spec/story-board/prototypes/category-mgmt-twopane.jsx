// Subscriptions-as-two-pane — the resolved direction at real scale.
// Direction B's rail+pane logic promoted to the DEFAULT Subscriptions view,
// populated with a synthesized ~187-feed library so it argues its own case.
// Web = two columns (category rail ↔ feed pane, search in both). Android =
// master-detail (category list → drill into feeds).

const TP = ED_PALETTES.paper;

// ── synthesized library ─────────────────────────────────────────────
const TP_CATDEF = [
  ['Design', 18], ['Engineering', 24], ['AI & ML', 21], ['News', 16],
  ['Science', 14], ['Culture', 19], ['Business', 12], ['Longform', 15],
  ['Security', 9], ['Gaming', 11], ['Local', 8],
];
const TP_CORES = ['Signal', 'Dispatch', 'Notebook', 'Ledger', 'Review', 'Journal',
  'Observer', 'Field Notes', 'Lab', 'Press', 'Wire', 'Almanac', 'Digest', 'Bulletin',
  'Quarterly', 'Gazette', 'Log', 'Reader', 'Report', 'Memo', 'Standard', 'Chronicle', 'Weekly', 'Atlas'];
const TP_PRE = ['The ', 'Daily ', '', 'A ', 'New ', 'My ', 'Little ', 'Open '];
const TP_TLD = ['.com', '.blog', '.io', '.org', '.fm', '.dev', '.net'];

function tpHue(s) { let h = 0; for (let i = 0; i < s.length; i++) h = (h * 31 + s.charCodeAt(i)) % 360; return h; }

const TP_CATS = (() => {
  let n = 0;
  const cats = TP_CATDEF.map(([name, count]) => {
    const feeds = [];
    for (let i = 0; i < count; i++) {
      const core = TP_CORES[n % TP_CORES.length];
      const pre = TP_PRE[Math.floor(n / TP_CORES.length) % TP_PRE.length];
      const fname = (pre + core).trim();
      const slug = fname.toLowerCase().replace(/[^a-z]+/g, '');
      feeds.push({ id: 'tp' + n, name: fname, url: slug + TP_TLD[n % TP_TLD.length] + '/feed', hue: tpHue(fname + name), unread: (n * 7) % 13 });
      n++;
    }
    return { id: name.toLowerCase().replace(/[^a-z]+/g, ''), name, feeds };
  });
  // Uncategorized — always present, sorts last
  const uncat = { id: 'uncat', name: 'Uncategorized', locked: true, feeds: [] };
  for (let i = 0; i < 20; i++) {
    const core = TP_CORES[n % TP_CORES.length];
    const pre = TP_PRE[Math.floor(n / TP_CORES.length) % TP_PRE.length];
    const fname = (pre + core).trim();
    const slug = fname.toLowerCase().replace(/[^a-z]+/g, '');
    uncat.feeds.push({ id: 'tp' + n, name: fname, url: slug + TP_TLD[n % TP_TLD.length] + '/feed', hue: tpHue(fname + 'uncat'), unread: (n * 7) % 13 });
    n++;
  }
  cats.push(uncat);
  return cats;
})();
const TP_TOTAL = TP_CATS.reduce((a, c) => a + c.feeds.length, 0);

// ── shared atoms ────────────────────────────────────────────────────
function TPAvatar({ f, size = 32 }) {
  return (
    <div style={{
      width: size, height: size, borderRadius: 4, flex: '0 0 auto',
      background: `oklch(0.85 0.05 ${f.hue})`, color: `oklch(0.35 0.08 ${f.hue})`,
      display: 'flex', alignItems: 'center', justifyContent: 'center',
      fontFamily: edSerifFont, fontWeight: 500, fontSize: Math.round(size * 0.44),
    }}>{f.name[0]}</div>
  );
}
function TPHandle() {
  return (
    <div style={{ display: 'grid', gridTemplateColumns: '2px 2px', gap: 2, flex: '0 0 auto', padding: '0 2px' }}>
      {Array.from({ length: 6 }).map((_, i) => <span key={i} style={{ width: 2, height: 2, borderRadius: '50%', background: TP.ink3 }} />)}
    </div>
  );
}
const tpLabel = { fontFamily: edUiFont, fontSize: 10, letterSpacing: '.1em', textTransform: 'uppercase', color: TP.ink3, fontWeight: 500 };
const tpSearch = (extra) => ({
  display: 'flex', alignItems: 'center', gap: 8, padding: '8px 12px',
  border: `1px solid ${TP.border}`, borderRadius: 4, background: TP.panel, ...extra,
});

// ── read-only reading sidebar (static, consistent with the big library) ──
function TPSidebar() {
  const top = [...TP_CATS].filter(c => !c.locked).sort((a, b) => b.feeds.length - a.feeds.length).slice(0, 6);
  const Nav = ({ label, count, active }) => (
    <div style={{
      display: 'flex', alignItems: 'center', justifyContent: 'space-between',
      padding: '6px 10px', borderRadius: 4, fontSize: 13, fontWeight: 500,
      background: active ? TP.accentSoft : 'transparent', color: active ? TP.accent : TP.ink,
    }}>
      <span>{label}</span>{count != null ? <span style={{ fontSize: 11, color: TP.muted, fontVariantNumeric: 'tabular-nums' }}>{count}</span> : null}
    </div>
  );
  return (
    <div style={{ width: 220, flex: '0 0 220px', height: '100%', background: TP.panel, borderRight: `1px solid ${TP.border}`, display: 'flex', flexDirection: 'column', fontFamily: edUiFont, color: TP.ink }}>
      <div style={{ padding: '20px 18px 14px' }}>
        <span style={{ display: 'inline-flex', alignItems: 'flex-end', gap: 1.2 }}>
          <span style={{ fontFamily: edSerifFont, fontSize: 17, fontWeight: 500, letterSpacing: '-.01em', lineHeight: 1 }}>Feed</span>
          <span style={{ width: 3, height: 3, borderRadius: '50%', background: TP.accent }} />
        </span>
      </div>
      <div style={{ padding: '4px 10px', display: 'flex', flexDirection: 'column', gap: 1 }}>
        <Nav label="Unread" count={342} />
        <Nav label="All articles" count="8.4k" />
        <Nav label="Subscriptions" count={TP_TOTAL} active />
        <Nav label="Settings" />
      </div>
      <div style={{ height: 1, background: TP.border, margin: '14px 18px' }} />
      <div style={{ padding: '0 10px', flex: 1, overflow: 'hidden' }}>
        {top.map(c => (
          <div key={c.id} style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '5px 10px', fontSize: 12.5, color: TP.ink2 }}>
            <span style={{ display: 'flex', alignItems: 'center', gap: 8, overflow: 'hidden' }}>
              <span style={{ width: 6, height: 6, borderRadius: '50%', background: `oklch(0.65 0.12 ${tpHue(c.name)})`, flex: '0 0 auto' }} />
              <span style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{c.name}</span>
            </span>
          </div>
        ))}
        <div style={{ padding: '5px 10px', fontSize: 11, color: TP.ink3, fontStyle: 'italic', fontFamily: edSerifFont }}>+ {TP_CATDEF.length - 6} more categories</div>
      </div>
      <div style={{ padding: '12px 18px', borderTop: `1px solid ${TP.border}`, fontSize: 11, display: 'flex', justifyContent: 'space-between', color: TP.ink3 }}>
        <span>Synced 2m ago</span><span>↻</span>
      </div>
    </div>
  );
}

function TPShell({ url = 'feed.app/subscriptions', children }) {
  return (
    <ChromeWindow tabs={[{ title: 'Feed — RSS' }, { title: 'inbox' }, { title: 'New Tab' }]} activeIndex={0} url={url} width={1180} height={760}>
      <div style={{ width: '100%', height: '100%', display: 'flex', background: TP.bg, fontFamily: edUiFont }}>
        <TPSidebar />
        {children}
      </div>
    </ChromeWindow>
  );
}

// ── rail ────────────────────────────────────────────────────────────
function TPRailRow({ label, count, active, locked, dot, dropTarget, onClick }) {
  return (
    <div onClick={onClick} style={{
      display: 'flex', alignItems: 'center', gap: 8, padding: '8px 10px', borderRadius: 4,
      marginBottom: 1, cursor: 'pointer',
      background: active ? TP.accentSoft : 'transparent',
      outline: dropTarget ? `2px solid ${TP.accent}` : 'none', outlineOffset: -2,
    }}>
      {!locked && dot !== false ? <TPHandle /> : <span style={{ width: 8 }} />}
      <span style={{ flex: 1, fontFamily: edSerifFont, fontSize: 14, fontWeight: 500, color: active ? TP.accent : TP.ink, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{label}</span>
      {locked ? <span style={{ fontSize: 9.5, color: TP.ink3, fontStyle: 'italic', fontFamily: edSerifFont }}>·</span> : null}
      <span style={{ fontSize: 11, color: active ? TP.accent : TP.ink3, fontVariantNumeric: 'tabular-nums' }}>{count}</span>
    </div>
  );
}

function TPRail({ sel, onSel, dropTargetId }) {
  const [q, setQ] = React.useState('');
  const cats = TP_CATS.filter(c => c.name.toLowerCase().includes(q.trim().toLowerCase()));
  return (
    <div style={{ width: 248, flex: '0 0 248px', borderRight: `1px solid ${TP.border}`, height: '100%', display: 'flex', flexDirection: 'column', background: TP.bg }}>
      <div style={{ padding: '20px 14px 12px' }}>
        <div style={{ ...tpLabel, marginBottom: 10, padding: '0 4px' }}>Categories · {TP_CATDEF.length + 1}</div>
        <div style={tpSearch()}>
          <span style={{ color: TP.ink3, fontSize: 12 }}>⌕</span>
          <input value={q} onChange={e => setQ(e.target.value)} placeholder="Filter categories…" style={{ all: 'unset', flex: 1, fontSize: 12.5, color: TP.ink, fontFamily: edUiFont }} />
        </div>
      </div>
      <div style={{ flex: 1, overflow: 'auto', padding: '0 10px 10px' }}>
        {!q ? <TPRailRow label="All feeds" count={TP_TOTAL} active={sel === 'all'} dot={false} onClick={() => onSel('all')} /> : null}
        {!q ? <div style={{ height: 1, background: TP.border, margin: '6px 8px' }} /> : null}
        {cats.filter(c => !c.locked).map(c => (
          <TPRailRow key={c.id} label={c.name} count={c.feeds.length} active={sel === c.id} dropTarget={dropTargetId === c.id} onClick={() => onSel(c.id)} />
        ))}
        {cats.find(c => c.locked) ? (
          <React.Fragment>
            <div style={{ height: 1, background: TP.border, margin: '6px 8px' }} />
            <TPRailRow label="Uncategorized" count={TP_CATS.find(c => c.locked).feeds.length} active={sel === 'uncat'} locked onClick={() => onSel('uncat')} />
          </React.Fragment>
        ) : null}
      </div>
      <div style={{ padding: '10px 14px', borderTop: `1px solid ${TP.border}` }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '9px 12px', border: `1px dashed ${TP.borderStrong}`, borderRadius: 4, color: TP.ink3, fontSize: 12.5, cursor: 'pointer' }}>+ New category</div>
      </div>
    </div>
  );
}

// ── feed pane ───────────────────────────────────────────────────────
function TPFeedRow({ f, last, lifted, moveOpen, onMove }) {
  return (
    <div style={{
      display: 'flex', alignItems: 'center', gap: 12, padding: '11px 8px',
      borderBottom: last ? 'none' : `1px solid ${TP.border}`,
      background: lifted ? TP.panel : 'transparent',
      borderRadius: lifted ? 4 : 0, boxShadow: lifted ? '0 8px 24px rgba(0,0,0,.12)' : 'none',
      position: 'relative',
    }}>
      <TPHandle />
      <TPAvatar f={f} size={32} />
      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={{ fontFamily: edSerifFont, fontSize: 15, fontWeight: 500, color: TP.ink }}>{f.name}</div>
        <div style={{ fontSize: 11, color: TP.ink3, marginTop: 2, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{f.url}</div>
      </div>
      <span style={{ fontSize: 11, color: TP.ink3, fontVariantNumeric: 'tabular-nums', width: 46, textAlign: 'right' }}>{f.unread} new</span>
      <div style={{ position: 'relative' }}>
        <button onClick={onMove} style={{ all: 'unset', cursor: 'pointer', fontSize: 11.5, color: TP.ink3, border: `1px solid ${TP.border}`, borderRadius: 4, padding: '5px 9px', background: TP.panel }}>Move to… ▾</button>
        {moveOpen ? (
          <div style={{ position: 'absolute', right: 0, top: 30, zIndex: 70, background: TP.panel, border: `1px solid ${TP.borderStrong}`, borderRadius: 4, boxShadow: '0 8px 24px rgba(0,0,0,.12)', minWidth: 180, maxHeight: 240, overflow: 'auto', padding: 4 }}>
            <div style={{ ...tpLabel, padding: '6px 10px 4px', position: 'sticky', top: 0, background: TP.panel }}>Move to</div>
            {TP_CATS.map(c => (
              <div key={c.id} style={{ padding: '7px 10px', borderRadius: 3, fontFamily: edUiFont, fontSize: 13, color: TP.ink, cursor: 'pointer' }}>{c.name}</div>
            ))}
          </div>
        ) : null}
      </div>
    </div>
  );
}

function TPPane({ catId }) {
  const [q, setQ] = React.useState('');
  const [moveId, setMoveId] = React.useState(null);
  let cat, feeds;
  if (catId === 'all') { cat = { name: 'All feeds' }; feeds = TP_CATS.flatMap(c => c.feeds); }
  else { cat = TP_CATS.find(c => c.id === catId) || TP_CATS[0]; feeds = cat.feeds; }
  const shown = feeds.filter(f => f.name.toLowerCase().includes(q.trim().toLowerCase()));
  return (
    <div style={{ flex: 1, height: '100%', display: 'flex', flexDirection: 'column', background: TP.bg, position: 'relative' }}>
      <div style={{ padding: '20px 32px 14px', borderBottom: `1px solid ${TP.border}` }}>
        <div style={{ display: 'flex', alignItems: 'baseline', justifyContent: 'space-between', gap: 12, marginBottom: 12 }}>
          <div style={{ display: 'flex', alignItems: 'baseline', gap: 10 }}>
            <h1 style={{ fontFamily: edSerifFont, fontSize: 24, fontWeight: 500, letterSpacing: '-.02em', margin: 0, color: TP.ink }}>{cat.name}</h1>
            <span style={{ fontSize: 12, color: TP.ink3, fontVariantNumeric: 'tabular-nums' }}>
              {q ? `showing ${shown.length} of ${feeds.length}` : `${feeds.length} feeds`}
            </span>
          </div>
          <button style={{ all: 'unset', cursor: 'pointer', padding: '8px 14px', borderRadius: 4, background: TP.accent, color: TP.onAccent, fontSize: 12.5 }}>+ Add feed</button>
        </div>
        <div style={tpSearch()}>
          <span style={{ color: TP.ink3 }}>⌕</span>
          <input value={q} onChange={e => setQ(e.target.value)} placeholder={`Search ${cat.name === 'All feeds' ? 'all feeds' : cat.name}…`} style={{ all: 'unset', flex: 1, fontSize: 13, color: TP.ink, fontFamily: edUiFont }} />
        </div>
      </div>
      <div style={{ flex: 1, overflow: 'auto', padding: '10px 32px 40px' }} onClick={() => setMoveId(null)}>
        {shown.length === 0 ? (
          <div style={{ padding: '60px 0', textAlign: 'center', fontFamily: edSerifFont, fontStyle: 'italic', fontSize: 16, color: TP.ink3 }}>Nothing here yet.</div>
        ) : shown.map((f, i, arr) => (
          <TPFeedRow key={f.id} f={f} last={i === arr.length - 1} moveOpen={moveId === f.id}
            onMove={(e) => { e.stopPropagation(); setMoveId(moveId === f.id ? null : f.id); }} />
        ))}
      </div>
    </div>
  );
}

// interactive default view
function TPSubsWeb() {
  const [sel, setSel] = React.useState('engineering');
  return (
    <TPShell>
      <div style={{ flex: 1, height: '100%', display: 'flex', minWidth: 0 }}>
        <TPRail sel={sel} onSel={setSel} />
        <TPPane catId={sel} />
      </div>
    </TPShell>
  );
}

// frozen drag-to-rail state
function TPSubsWebDrag() {
  const cat = TP_CATS.find(c => c.id === 'engineering');
  return (
    <TPShell>
      <div style={{ flex: 1, height: '100%', display: 'flex', minWidth: 0 }}>
        <TPRail sel="engineering" onSel={() => {}} dropTargetId="design" />
        <div style={{ flex: 1, height: '100%', display: 'flex', flexDirection: 'column', background: TP.bg, position: 'relative' }}>
          <div style={{ padding: '20px 32px 14px', borderBottom: `1px solid ${TP.border}` }}>
            <div style={{ display: 'flex', alignItems: 'baseline', gap: 10 }}>
              <h1 style={{ fontFamily: edSerifFont, fontSize: 24, fontWeight: 500, letterSpacing: '-.02em', margin: 0 }}>Engineering</h1>
              <span style={{ fontSize: 12, color: TP.ink3 }}>{cat.feeds.length} feeds</span>
            </div>
            <div style={{ fontSize: 12, color: TP.ink3, marginTop: 6, fontStyle: 'italic', fontFamily: edSerifFont }}>Dragging “{cat.feeds[2].name}” → drop on a category in the rail to re-file it.</div>
          </div>
          <div style={{ flex: 1, overflow: 'hidden', padding: '10px 32px 40px' }}>
            {cat.feeds.slice(0, 7).map((f, i, arr) => (
              <TPFeedRow key={f.id} f={f} last={i === arr.length - 1} lifted={i === 2} />
            ))}
          </div>
        </div>
      </div>
    </TPShell>
  );
}

Object.assign(window, { TPSubsWeb, TPSubsWebDrag, TP_TOTAL });

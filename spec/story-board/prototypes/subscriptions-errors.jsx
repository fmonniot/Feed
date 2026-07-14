// Subscriptions · Feed errors — wired to the same category-manager anatomy
// as the live Subscriptions screen (prototypes/subscriptions.jsx):
//   • Web    → category rail (left) + feed pane (right), same rail/row/pane
//              components, same per-feed overflow menu for healthy rows.
//   • Android → grouped-by-category "Feeds" list, same header + avatar sizes.
//
// What's added on top of that shared anatomy is the feed-error surface from
// FEATURES.md §Feed errors / VISUAL_SPEC.md §Subscriptions feed-error surface:
//   • a non-interactive summary banner above the search bar,
//   • broken feed rows (dimmed avatar, tone badge, time-since-failure, chevron)
//     in place of the healthy row's unread-count + ⋯ overflow,
//   • an inline accordion (mono diagnostic + explanation + actions) that
//     toggles open below a broken row on tap/click.
//
// All three broken feeds in the seed data (coldtake, frequencies, atlas) live
// in the "Reading" category, so that's the default rail selection below.

// ── Seed data ─────────────────────────────────────────────────────────────────
const SE_ERR = [
  {
    feedId: 'coldtake',
    badge: '410 GONE',
    severity: 'error',
    since: '14d',
    url: 'coldtake.blog/feed.xml',
    mono: [
      'HTTP 410 Gone · coldtake.blog/feed.xml',
      '14 consecutive failures · since 06 May 2026',
      'last attempt: 2h ago · next retry: none',
      '↳ permanent failure signal; retries paused',
    ].join('\n'),
    human: 'The publisher signals this feed is permanently gone. Cached articles are preserved. No further automatic retries are scheduled.',
    actions: [
      { label: 'Retry once',  danger: false },
      { label: 'Fix URL…',    danger: false },
      { label: 'View raw ↗', danger: false },
      { label: 'Unsubscribe', danger: true  },
    ],
  },
  {
    feedId: 'frequencies',
    badge: 'PARSE FAIL',
    severity: 'error',
    since: '6h',
    url: 'frequencies.fm/rss',
    mono: [
      '200 OK · text/html  (expected application/rss+xml)',
      'frequencies.fm/rss · 1.4 KB',
      'parser: unexpected <!DOCTYPE html> at line 1, col 1',
      '4 consecutive failures · next retry in ~2h',
    ].join('\n'),
    human: 'The server returned HTML instead of a feed — likely a maintenance page or login wall. Showing stale articles from 6h ago.',
    actions: [
      { label: 'Retry now',   danger: false },
      { label: 'View raw ↗', danger: false },
      { label: 'Unsubscribe', danger: true  },
    ],
  },
  {
    feedId: 'atlas',
    badge: 'HTTP 500',
    severity: 'warn',
    since: '3h',
    url: 'atlasessays.org/feed',
    mono: [
      'HTTP 500 Internal Server Error · atlasessays.org/feed',
      '2 consecutive failures · last attempt: 3h ago',
      'next retry in ~30m',
    ].join('\n'),
    human: 'The server is returning errors. This usually resolves on its own. Articles from 3h ago are still available.',
    actions: [
      { label: 'Retry now',   danger: false },
      { label: 'Unsubscribe', danger: true  },
    ],
  },
];

const SE_FS = { coldtake: 'dead', frequencies: 'error', atlas: 'error' };

// ── Shared atoms ──────────────────────────────────────────────────────────────

function SEBadge({ severity, label }) {
  const t = severity === 'warn'
    ? { fg: EDGE_TOK.warnFg, bd: EDGE_TOK.warnBd, bg: EDGE_TOK.warnBg }
    : { fg: EDGE_TOK.errFg,  bd: EDGE_TOK.errBd,  bg: EDGE_TOK.errBg  };
  return (
    <span style={{
      fontFamily: 'ui-monospace, monospace', fontSize: 9.5,
      letterSpacing: '.14em', textTransform: 'uppercase', color: t.fg,
      padding: '2px 5px', border: `1px solid ${t.bd}`,
      borderRadius: 2, background: t.bg, flex: '0 0 auto', lineHeight: 1.1,
      whiteSpace: 'nowrap',
    }}>{label}</span>
  );
}

function SEMono({ text }) {
  const ED_C = React.useContext(EdThemeContext);
  return (
    <pre style={{
      margin: 0,
      fontFamily: '"SF Mono", "Fira Code", ui-monospace, monospace',
      fontSize: 11, lineHeight: 1.7, color: ED_C.ink2,
      background: ED_C.bg, border: `1px solid ${ED_C.border}`,
      padding: '10px 14px', borderRadius: 3,
      whiteSpace: 'pre-wrap', overflowWrap: 'break-word',
    }}>{text}</pre>
  );
}

function SEHuman({ text }) {
  const ED_C = React.useContext(EdThemeContext);
  return (
    <div style={{
      fontFamily: edUiFont, fontSize: 12.5, lineHeight: 1.55,
      color: ED_C.ink2, textWrap: 'pretty',
    }}>{text}</div>
  );
}

function SEActions({ actions }) {
  const ED_C = React.useContext(EdThemeContext);
  return (
    <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
      {actions.map(a => (
        <button key={a.label} style={{
          all: 'unset', cursor: 'pointer', padding: '6px 12px', borderRadius: 4,
          border: `1px solid ${a.danger ? ED_C.danger : ED_C.border}`,
          background: ED_C.panel, fontFamily: edUiFont, fontSize: 12,
          color: a.danger ? ED_C.danger : ED_C.ink2,
        }}>{a.label}</button>
      ))}
    </div>
  );
}

function SEDetail({ err }) {
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
      <SEMono text={err.mono} />
      <SEHuman text={err.human} />
      <SEActions actions={err.actions} />
    </div>
  );
}

// Non-expandable summary strip — purely informational, no toggle. Pinned
// above the search bar whenever ≥ 1 feed is failing (FEATURES.md SUBS-6).
function SESummaryBanner({ errCount = SE_ERR.length }) {
  if (errCount === 0) return null;
  const warnCount = SE_ERR.filter(e => e.severity === 'warn').length;
  const errOnly   = SE_ERR.filter(e => e.severity === 'error').length;
  const demoted   = errOnly === 0;
  const bg = demoted ? EDGE_TOK.warnBg : EDGE_TOK.errBg;
  const bd = demoted ? EDGE_TOK.warnBd : EDGE_TOK.errBd;
  const fg = demoted ? EDGE_TOK.warnFg : EDGE_TOK.errFg;
  const label  = errCount === 1 ? '1 error' : `${errCount} errors`;
  const detail = errOnly === errCount
    ? `${errCount} feed${errCount > 1 ? 's' : ''} failing — last checked 2h ago`
    : `${errOnly} failing · ${warnCount} warning — last checked 2h ago`;

  return (
    <div style={{
      display: 'flex', alignItems: 'center', gap: 12, padding: '10px 16px',
      background: bg, border: `1px solid ${bd}`,
      borderRadius: 4, marginBottom: 16,
    }}>
      <span style={{
        fontFamily: 'ui-monospace, monospace', fontSize: 9.5, letterSpacing: '.14em',
        textTransform: 'uppercase', color: fg,
        padding: '2px 6px', border: `1px solid ${bd}`, borderRadius: 2,
        background: 'rgba(255,255,255,.55)', flex: '0 0 auto', lineHeight: 1.2,
      }}>{label}</span>
      <span style={{ fontSize: 13, color: fg, flex: 1, fontFamily: edUiFont }}>
        {detail}
      </span>
      {/* No expand control — details live in the list rows below */}
    </div>
  );
}

// ── Broken feed row — same anatomy (handle · avatar · name · url) as the
// healthy SubFeedRow, with the trailing cluster + accordion from the
// feed-error surface spec swapped in.
function SubFeedRowErr({ ED_C, f, err, last, expanded, onToggle }) {
  const toneFg = err.severity === 'warn' ? EDGE_TOK.warnFg : EDGE_TOK.errFg;
  return (
    <div style={{ borderBottom: (!expanded && !last) ? `1px solid ${ED_C.border}` : 'none' }}>
      <div onClick={onToggle} style={{
        display: 'flex', alignItems: 'center', gap: 12, padding: '11px 8px', cursor: 'pointer',
      }}>
        <SubHandle ED_C={ED_C} />
        <div style={{ opacity: 0.6 }}>{subAvatar(ED_C, f, 32)}</div>
        <div style={{ flex: 1, minWidth: 0 }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
            <span style={{ fontFamily: edSerifFont, fontSize: 15, fontWeight: 500, color: ED_C.ink }}>{f.name}</span>
            <SEBadge severity={err.severity} label={err.badge} />
          </div>
          <div style={{ fontSize: 11, color: ED_C.ink3, marginTop: 2, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{f.url}</div>
        </div>
        <span style={{ fontSize: 11, color: toneFg, whiteSpace: 'nowrap' }}>{err.since}</span>
        <span style={{ fontSize: 11, color: ED_C.ink3, width: 14, textAlign: 'center' }}>{expanded ? '▲' : '▼'}</span>
      </div>
      {expanded ? (
        <div style={{
          margin: '0 0 14px', padding: 14,
          background: ED_C.panel, border: `1px solid ${ED_C.border}`,
          borderLeft: `3px solid ${toneFg}`, borderRadius: 3,
        }}>
          <SEDetail err={err} />
        </div>
      ) : null}
    </div>
  );
}

// ════════════════════════════════════════════════════════════════════
// WEB · category rail + feed pane (broken rows use SubFeedRowErr, healthy
// rows reuse the live SubFeedRow verbatim — same drag handle, avatar,
// pause badge, and full ⋯ overflow menu).
// ════════════════════════════════════════════════════════════════════

function SubPaneErr({ ED_C, feeds, categories, cat, handlers, dragId, setDragId, setDropTargetId, expandedId, setExpandedId }) {
  const [q, setQ] = React.useState('');
  const [menu, setMenu] = React.useState(null);
  const [addOpen, setAddOpen] = React.useState(false);
  const [addUrl, setAddUrl] = React.useState('');
  const [renameId, setRenameId] = React.useState(null);
  const [renameVal, setRenameVal] = React.useState('');
  const [refreshingIds, setRefreshingIds] = React.useState(() => new Set());

  const list = catFeedList(feeds, cat, categories);
  const shown = list.filter(f => f.name.toLowerCase().includes(q.trim().toLowerCase()));
  const targetCatName = cat.id === 'all' || cat.locked ? 'Uncategorized' : cat.name;

  const submitAdd = (e) => {
    e && e.preventDefault();
    if (!addUrl.trim()) return;
    handlers.addFeed(addUrl.trim(), targetCatName);
    setAddUrl(''); setAddOpen(false);
  };
  const startRename = (f) => { setMenu(null); setRenameId(f.id); setRenameVal(f.name); };
  const commitRename = (f) => (cancel) => {
    if (!cancel && renameVal.trim()) handlers.renameFeed(f.id, renameVal.trim());
    setRenameId(null);
  };
  const refresh = (f) => {
    setMenu(null);
    setRefreshingIds(prev => new Set(prev).add(f.id));
    setTimeout(() => setRefreshingIds(prev => { const n = new Set(prev); n.delete(f.id); return n; }), 1200);
  };

  return (
    <div style={{ flex: 1, height: '100%', display: 'flex', flexDirection: 'column', background: ED_C.bg, position: 'relative', minWidth: 0 }}>
      <div style={{ padding: '20px 32px 14px', borderBottom: `1px solid ${ED_C.border}` }}>
        <div style={{ display: 'flex', alignItems: 'baseline', justifyContent: 'space-between', gap: 12, marginBottom: 12 }}>
          <div style={{ display: 'flex', alignItems: 'baseline', gap: 10 }}>
            <h1 style={{ fontFamily: edSerifFont, fontSize: 24, fontWeight: 500, letterSpacing: '-.02em', margin: 0, color: ED_C.ink }}>{cat.name}</h1>
            <span style={{ fontSize: 12, color: ED_C.ink3, fontVariantNumeric: 'tabular-nums' }}>
              {q ? `showing ${shown.length} of ${list.length}` : `${list.length} ${list.length === 1 ? 'feed' : 'feeds'}`}
            </span>
          </div>
          <button onClick={() => setAddOpen(v => !v)} style={{
            all: 'unset', cursor: 'pointer', padding: '8px 14px', borderRadius: 4, fontSize: 12.5,
            background: addOpen ? ED_C.panel : ED_C.accent, color: addOpen ? ED_C.ink2 : ED_C.onAccent,
            border: addOpen ? `1px solid ${ED_C.border}` : 'none',
          }}>{addOpen ? 'Cancel' : '+ Add feed'}</button>
        </div>

        {addOpen ? (
          <form onSubmit={submitAdd} style={{ display: 'flex', gap: 8, padding: '10px 12px', marginBottom: 12,
            border: `1px solid ${ED_C.borderStrong}`, borderRadius: 4, background: ED_C.panel }}>
            <input autoFocus value={addUrl} onChange={(e) => setAddUrl(e.target.value)}
              placeholder="https://example.com/feed.xml"
              style={{ all: 'unset', flex: 1, fontSize: 13, color: ED_C.ink, fontFamily: edUiFont }} />
            <button type="submit" style={{ all: 'unset', cursor: 'pointer', padding: '6px 14px', borderRadius: 4,
              background: ED_C.ink, color: ED_C.panel, fontSize: 12.5 }}>Subscribe</button>
          </form>
        ) : null}

        <SESummaryBanner />

        <div style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '9px 12px',
          border: `1px solid ${ED_C.border}`, borderRadius: 4, background: ED_C.panel }}>
          <span style={{ color: ED_C.ink3 }}>⌕</span>
          <input value={q} onChange={(e) => setQ(e.target.value)}
            placeholder={`Search ${cat.id === 'all' ? 'all feeds' : cat.name}…`}
            style={{ all: 'unset', flex: 1, fontSize: 13, color: ED_C.ink, fontFamily: edUiFont }} />
        </div>
      </div>

      <div style={{ flex: 1, overflow: 'auto', padding: '10px 32px 40px' }} onClick={() => setMenu(null)}>
        {shown.length === 0 ? (
          <div style={{ padding: '60px 0', textAlign: 'center', fontFamily: edSerifFont, fontStyle: 'italic', fontSize: 16, color: ED_C.ink3 }}>
            Nothing here yet.
          </div>
        ) : shown.map((f, i, arr) => {
          const err = SE_ERR.find(e => e.feedId === f.id);
          if (err) {
            return (
              <SubFeedRowErr key={f.id} ED_C={ED_C} f={f} err={err} last={i === arr.length - 1}
                expanded={expandedId === f.id}
                onToggle={() => setExpandedId(expandedId === f.id ? null : f.id)} />
            );
          }
          return (
            <SubFeedRow key={f.id} ED_C={ED_C} f={f} last={i === arr.length - 1} categories={categories}
              menu={menu} setMenu={setMenu} refreshing={refreshingIds.has(f.id)} lifted={dragId === f.id}
              renaming={renameId === f.id} renameVal={renameVal} setRenameVal={setRenameVal} commitRename={commitRename(f)}
              onRefresh={() => refresh(f)}
              onMove={(c) => { handlers.moveFeed(f.id, c.isNew ? handlers.addCategory(c.name) : c.name); setMenu(null); }}
              onRename={() => startRename(f)}
              onInterval={(iv) => { handlers.setInterval(f.id, iv); setMenu(null); }}
              onPause={() => { handlers.togglePause(f.id); setMenu(null); }}
              onDelete={() => { setMenu(null); if (confirm(`Unsubscribe from “${f.name}”? Its articles will be removed.`)) handlers.deleteFeed(f.id); }}
              onDragStart={() => setDragId(f.id)} onDragEnd={() => { setDragId(null); setDropTargetId(null); }} />
          );
        })}
      </div>
    </div>
  );
}

function SubsWebErr({ feeds, setFeeds, categories, setCategories, expandedId, setExpandedId, initialSel }) {
  const ED_C = React.useContext(EdThemeContext);
  const [sel, setSel] = React.useState(initialSel || (() => {
    const first = categories.find(c => !c.locked);
    return first ? first.id : 'all';
  }));
  const [catMenuId, setCatMenuId] = React.useState(null);
  const [dragId, setDragId] = React.useState(null);
  const [dropTargetId, setDropTargetId] = React.useState(null);
  const [deleteCat, setDeleteCat] = React.useState(null);

  const A = useSubActions(feeds, setFeeds, categories, setCategories);
  const cat = categories.find(c => c.id === sel) || { id: 'all', name: 'All feeds' };

  const onFeedDrop = (targetCat) => {
    if (dragId) A.moveFeed(dragId, targetCat.locked ? 'Uncategorized' : targetCat.name);
    setDragId(null); setDropTargetId(null);
  };
  const confirmDelete = (targetName) => {
    A.deleteCategory(deleteCat, targetName);
    if (sel === deleteCat.id) { const first = categories.find(c => !c.locked && c.id !== deleteCat.id); setSel(first ? first.id : 'all'); }
    setDeleteCat(null);
  };

  return (
    <div style={{ flex: 1, height: '100%', display: 'flex', minWidth: 0, position: 'relative' }}>
      <SubRail ED_C={ED_C} feeds={feeds} categories={categories} sel={sel} onSel={setSel}
        dragId={dragId} dropTargetId={dropTargetId} setDropTargetId={setDropTargetId} onFeedDrop={onFeedDrop}
        organize={true} catMenuId={catMenuId} setCatMenuId={setCatMenuId}
        onAddCategory={A.addCategory} onRenameCategory={A.renameCategory} onRequestDelete={setDeleteCat} />
      <SubPaneErr ED_C={ED_C} feeds={feeds} categories={categories} cat={cat} handlers={A}
        dragId={dragId} setDragId={setDragId} setDropTargetId={setDropTargetId}
        expandedId={expandedId} setExpandedId={setExpandedId} />
      {deleteCat ? (
        <SubDeleteModal ED_C={ED_C} cat={deleteCat} categories={categories} feeds={feeds}
          onCancel={() => setDeleteCat(null)} onConfirm={confirmDelete} />
      ) : null}
    </div>
  );
}

// ════════════════════════════════════════════════════════════════════
// ANDROID · grouped-by-category "Feeds" list (matches SubsMobile's browse
// shape — header, search, uppercase category headers with counts, 34×34
// avatars). Broken rows swap the trailing unread+⋯ cluster for the tone
// badge + time-since-failure + chevron and expand the same accordion.
// ════════════════════════════════════════════════════════════════════

function SubsMobileErr({ feeds, categories, expandedId, setExpandedId }) {
  const ED_C = React.useContext(EdThemeContext);
  const [q, setQ] = React.useState('');
  const [searchOpen, setSearchOpen] = React.useState(false);
  const [screenMenu, setScreenMenu] = React.useState(false);
  const [feedMenu, setFeedMenu] = React.useState(null);
  const [refreshingIds, setRefreshingIds] = React.useState(() => new Set());
  const scrollRef = React.useRef(null);
  const rowRefs = React.useRef({});

  // Keep the expanded accordion clear of the bottom tab bar — the tab bar
  // floats over the last ~90px of the frame regardless of scroll position,
  // so a row that expands near the bottom (e.g. deep-linked open on mount)
  // needs to be nudged up rather than just relying on trailing padding.
  React.useEffect(() => {
    if (!expandedId) return;
    const container = scrollRef.current;
    const row = rowRefs.current[expandedId];
    if (!container || !row) return;
    const CLEARANCE = 110;
    const containerRect = container.getBoundingClientRect();
    const rowRect = row.getBoundingClientRect();
    const overflowBelow = rowRect.bottom - (containerRect.bottom - CLEARANCE);
    if (overflowBelow > 0) container.scrollTop += overflowBelow;
  }, [expandedId]);

  const ql = q.trim().toLowerCase();
  const groups = categories
    .map(c => {
      const gf = catFeedList(feeds, c, categories);
      return { cat: c, total: gf.length, shown: gf.filter(f => f.name.toLowerCase().includes(ql)) };
    })
    .filter(g => ql ? g.shown.length > 0 : g.total > 0);

  const refresh = (f) => {
    setFeedMenu(null);
    setRefreshingIds(prev => new Set(prev).add(f.id));
    setTimeout(() => setRefreshingIds(prev => { const n = new Set(prev); n.delete(f.id); return n; }), 1200);
  };

  const menuCard = { position: 'absolute', right: 0, top: 26, zIndex: 45,
    background: ED_C.panel, border: `1px solid ${ED_C.borderStrong}`, borderRadius: 6,
    boxShadow: '0 10px 28px rgba(0,0,0,.14)', minWidth: 168, padding: 4 };
  const mitem = (label, onClick, opts = {}) => (
    <button onClick={onClick} style={{
      all: 'unset', cursor: 'pointer', display: 'block', width: '100%', boxSizing: 'border-box',
      padding: '10px 12px', fontSize: 13, borderRadius: 4, textAlign: 'left',
      color: opts.danger ? ED_C.danger : ED_C.ink, fontFamily: edUiFont,
    }}>{label}</button>
  );

  // App-bar icon row — same shape/order as the live prototype's Feeds tab
  // (search toggle · add feed · overflow) so every Feeds screen matches.
  const appBarBtn = (active) => ({
    all: 'unset', cursor: 'pointer', width: 32, height: 32, borderRadius: 4,
    border: `1px solid ${active ? ED_C.borderStrong : ED_C.border}`,
    background: active ? ED_C.accentSoft : ED_C.panel,
    color: active ? ED_C.accent : ED_C.ink2,
    display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
    fontSize: 14, flexShrink: 0,
  });
  const toggleSearch = () => {
    setScreenMenu(false);
    setSearchOpen(v => { const next = !v; if (!next) setQ(''); return next; });
  };
  const appBarActions = (
    <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
      <button onClick={toggleSearch} style={appBarBtn(searchOpen)} aria-label="Search feeds" title="Search feeds">⌕</button>
      <button style={appBarBtn(false)} aria-label="Add feed" title="Add feed">+</button>
      <div style={{ position: 'relative' }}>
        <button onClick={(e) => { e.stopPropagation(); setScreenMenu(v => !v); setFeedMenu(null); }}
          style={appBarBtn(screenMenu)} aria-label="More actions" title="More actions">⋯</button>
        {screenMenu ? (
          <div onClick={(e) => e.stopPropagation()} style={menuCard}>
            {mitem('+ New category…', () => setScreenMenu(false))}
          </div>
        ) : null}
      </div>
    </div>
  );

  return (
    <div style={{ flex: 1, minHeight: 0, display: 'flex', flexDirection: 'column', background: ED_C.bg }}
      onClick={() => { setFeedMenu(null); setScreenMenu(false); }}>
      <SubMHeader ED_C={ED_C} topInset={14} title="Feeds"
        subtitle={`${feeds.length} subscriptions · ${categories.length} categories`}
        right={appBarActions} />

      <div ref={scrollRef} style={{ flex: 1, minHeight: 0, overflow: 'auto', paddingBottom: 100 }}>
      <div style={{ padding: '14px 22px 4px' }}>
        <SESummaryBanner />
        {searchOpen ? (
          <div style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '10px 14px',
            border: `1px solid ${ED_C.border}`, borderRadius: 4, background: ED_C.panel }}>
            <span style={{ color: ED_C.ink3 }}>⌕</span>
            <input autoFocus value={q} onChange={(e) => setQ(e.target.value)} placeholder="Search feeds…"
              style={{ all: 'unset', flex: 1, fontSize: 13, color: ED_C.ink, fontFamily: edUiFont }} />
          </div>
        ) : null}
      </div>

      {groups.map(g => (
        <div key={g.cat.id}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 10, padding: '20px 22px 6px' }}>
            <span style={{ fontFamily: edUiFont, fontSize: 10, letterSpacing: '.1em', textTransform: 'uppercase', color: ED_C.ink3, fontWeight: 500 }}>{g.cat.name}</span>
            <span style={{ fontSize: 10.5, color: ED_C.ink3, fontVariantNumeric: 'tabular-nums' }}>{g.total}</span>
          </div>

          {g.shown.map((f, i, arr) => {
            const err = SE_ERR.find(e => e.feedId === f.id);
            const isExp = err && expandedId === f.id;
            return (
              <div key={f.id} ref={el => { rowRefs.current[f.id] = el; }}
                style={{ borderBottom: (!isExp && i < arr.length - 1) ? `1px solid ${ED_C.border}` : 'none' }}>
                <div onClick={(e) => { if (err) { e.stopPropagation(); setExpandedId(isExp ? null : f.id); } }}
                  style={{ display: 'flex', alignItems: 'center', gap: 14, padding: '13px 22px', cursor: err ? 'pointer' : 'default', position: 'relative' }}>
                  <div style={{ opacity: err ? 0.6 : 1 }}>{subAvatar(ED_C, f, 34)}</div>
                  <div style={{ flex: 1, minWidth: 0 }}>
                    <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
                      <span style={{ fontFamily: edSerifFont, fontSize: 15, fontWeight: 500, color: ED_C.ink }}>{f.name}</span>
                      {err ? <SEBadge severity={err.severity} label={err.badge} /> : null}
                    </div>
                    <div style={{ fontSize: 11, color: ED_C.ink3, marginTop: 2, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{f.url}</div>
                  </div>
                  {err ? (
                    <React.Fragment>
                      <span style={{ fontSize: 11, whiteSpace: 'nowrap', color: err.severity === 'error' ? EDGE_TOK.errFg : EDGE_TOK.warnFg }}>{err.since}</span>
                      <span style={{ fontSize: 11, color: ED_C.ink3, width: 14, textAlign: 'center' }}>{isExp ? '▲' : '▼'}</span>
                    </React.Fragment>
                  ) : (
                    <React.Fragment>
                      {refreshingIds.has(f.id) ? (
                        <span style={{ display: 'inline-block', width: 13, height: 13, borderRadius: '50%',
                          border: `2px solid ${ED_C.border}`, borderTopColor: ED_C.accent, animation: 'seSpinM .8s linear infinite' }} />
                      ) : f.unread > 0 ? (
                        <span style={{ fontSize: 12, color: ED_C.ink3, fontVariantNumeric: 'tabular-nums' }}>{f.unread}</span>
                      ) : null}
                      <button onClick={(e) => { e.stopPropagation(); setFeedMenu(feedMenu === f.id ? null : f.id); }}
                        style={{ all: 'unset', cursor: 'pointer', fontSize: 16, color: ED_C.ink3, padding: '0 4px' }}>⋯</button>
                      {feedMenu === f.id ? (
                        <div onClick={(e) => e.stopPropagation()} style={menuCard}>
                          {mitem('Refresh now', () => refresh(f))}
                          {mitem(f.paused ? 'Resume updates' : 'Pause updates', () => setFeedMenu(null))}
                          <div style={{ height: 1, background: ED_C.border, margin: '4px 6px' }} />
                          {mitem('Unsubscribe', () => setFeedMenu(null), { danger: true })}
                        </div>
                      ) : null}
                    </React.Fragment>
                  )}
                </div>
                {isExp ? (
                  <div style={{ margin: '0 22px 12px', padding: 12, background: ED_C.panel, border: `1px solid ${ED_C.border}`,
                    borderLeft: `3px solid ${err.severity === 'error' ? EDGE_TOK.errFg : EDGE_TOK.warnFg}`, borderRadius: 3 }}>
                    <SEDetail err={err} />
                  </div>
                ) : null}
              </div>
            );
          })}
        </div>
      ))}
      </div>
      <style>{`@keyframes seSpinM { to { transform: rotate(360deg); } }`}</style>
    </div>
  );
}

// ── Artboard wrappers ──────────────────────────────────────────────────────

function SubsMixedWebDefault() {
  const [feeds, setFeeds] = React.useState(FEEDS);
  const [categories, setCategories] = React.useState(() => makeInitialCategories(FEEDS));
  const [expandedId, setExpandedId] = React.useState(null);
  const readingCat = categories.find(c => c.name === 'Reading');
  return (
    <EdgeShell sidebar={<EdgeSidebar active="subs" feedStatus={SE_FS} feeds={feeds} />}>
      <SubsWebErr feeds={feeds} setFeeds={setFeeds} categories={categories} setCategories={setCategories}
        expandedId={expandedId} setExpandedId={setExpandedId}
        initialSel={readingCat ? readingCat.id : 'all'} />
    </EdgeShell>
  );
}

function SubsMixedWebExpanded() {
  const [feeds, setFeeds] = React.useState(FEEDS);
  const [categories, setCategories] = React.useState(() => makeInitialCategories(FEEDS));
  const [expandedId, setExpandedId] = React.useState('coldtake');
  const readingCat = categories.find(c => c.name === 'Reading');
  return (
    <EdgeShell sidebar={<EdgeSidebar active="subs" feedStatus={SE_FS} feeds={feeds} />}>
      <SubsWebErr feeds={feeds} setFeeds={setFeeds} categories={categories} setCategories={setCategories}
        expandedId={expandedId} setExpandedId={setExpandedId}
        initialSel={readingCat ? readingCat.id : 'all'} />
    </EdgeShell>
  );
}

function SubsMixedMobileDefault() {
  const [feeds] = React.useState(FEEDS);
  const [categories] = React.useState(() => makeInitialCategories(FEEDS));
  const [expandedId, setExpandedId] = React.useState(null);
  return (
    <AndroidDevice width={412} height={892}>
      <EdThemeContext.Provider value={ED_PALETTES.paper}>
        <div style={{ width: '100%', height: '100%', background: ED_PALETTES.paper.bg, position: 'relative', overflow: 'hidden', display: 'flex', flexDirection: 'column' }}>
          <SubsMobileErr feeds={feeds} categories={categories} expandedId={expandedId} setExpandedId={setExpandedId} />
          <EdgeMTabBar active="feeds" />
        </div>
      </EdThemeContext.Provider>
    </AndroidDevice>
  );
}

function SubsMixedMobileExpanded() {
  const [feeds] = React.useState(FEEDS);
  const [categories] = React.useState(() => makeInitialCategories(FEEDS));
  const [expandedId, setExpandedId] = React.useState('coldtake');
  return (
    <AndroidDevice width={412} height={892}>
      <EdThemeContext.Provider value={ED_PALETTES.paper}>
        <div style={{ width: '100%', height: '100%', background: ED_PALETTES.paper.bg, position: 'relative', overflow: 'hidden', display: 'flex', flexDirection: 'column' }}>
          <SubsMobileErr feeds={feeds} categories={categories} expandedId={expandedId} setExpandedId={setExpandedId} />
          <EdgeMTabBar active="feeds" />
        </div>
      </EdThemeContext.Provider>
    </AndroidDevice>
  );
}

Object.assign(window, {
  SubsMixedWebDefault, SubsMixedWebExpanded,
  SubsMixedMobileDefault, SubsMixedMobileExpanded,
});

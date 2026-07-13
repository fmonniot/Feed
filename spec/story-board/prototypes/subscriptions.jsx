// Subscriptions — the resolved surface, wired live into index.html.
//
// Adopts the Category Management exploration:
//   • Web  → Subscriptions IS the two-pane manager (category rail ↔ feed pane,
//            search in both, re-file by dragging a feed onto the rail OR the
//            row's ⋯ → Move to category…).
//   • Android → the grouped Feeds list keeps its shape; each feed's ⋯ overflow
//            menu carries the full action set (Move to category… included), and
//            an Organizing mode adds category rename / delete / + New category.
//
// CRITICAL: "category management" is not the full action set. Every per-feed
// action of the live product is preserved here — Refresh now, Move to
// category…, Rename…, Change URL… (BUG-56/BUG-60), Fetch interval…,
// Pause/Resume, Unsubscribe — alongside the top-level + Add feed / + New
// category / search / category rename + delete-with-reassign.
//
// Reuses ED_C palette, fonts, and EdThumb from editorial.jsx. State (feeds +
// categories) is owned by the prototype and mutated through the setters passed
// in, so edits reflect live in the reading sidebar / Feeds tab.

const FETCH_INTERVALS = ['15m', '1h', '6h', 'Daily'];

// Categories are a first-class list so empty categories + rename + delete work.
// A feed joins its category by `folder === category.name`. "Uncategorized" is
// permanent, un-renamable, un-deletable, and sorts last.
function makeInitialCategories(feeds) {
  const order = [];
  feeds.forEach((f) => {if (f.folder && !order.includes(f.folder)) order.push(f.folder);});
  const cats = order.map((name) => ({ id: name.toLowerCase().replace(/[^a-z0-9]+/g, '') || 'c', name }));
  cats.push({ id: 'uncat', name: 'Uncategorized', locked: true });
  return cats;
}

function subKnownNames(categories) {
  return categories.filter((c) => !c.locked).map((c) => c.name);
}

// Feeds belonging to a category. Uncategorized absorbs any feed whose folder
// matches no live (non-locked) category — so "no category" is always safe.
function catFeedList(feeds, cat, categories) {
  if (!cat || cat.id === 'all') return feeds;
  if (cat.locked) {
    const known = subKnownNames(categories);
    return feeds.filter((f) => !known.includes(f.folder));
  }
  return feeds.filter((f) => f.folder === cat.name);
}

function subAvatar(ED_C, f, size = 32) {
  return (
    <div style={{
      width: size, height: size, borderRadius: 4, flex: '0 0 auto',
      background: `oklch(0.85 0.05 ${f.hue})`, color: `oklch(0.35 0.08 ${f.hue})`,
      display: 'flex', alignItems: 'center', justifyContent: 'center',
      fontFamily: edSerifFont, fontWeight: 500, fontSize: Math.round(size * 0.44),
      opacity: f.paused ? 0.55 : 1
    }}>{f.name[0]}</div>);

}

// Drag handle. In the product this affords two web-only drag interactions:
// re-filing a feed onto a rail category (wired below via onDragStart/onDrop) AND
// reordering feeds. NOTE: this prototype intentionally does NOT implement
// reorder (drop-to-reorder + persisted order is a lot of plumbing for a story
// board) — the handle is drawn as the affordance, but only re-filing is live.
// The reorder contract lives in FEATURES.md §Categories & feed management and
// ticket #123; don't infer reorder-is-unsupported from its absence here.
function SubHandle({ ED_C }) {
  return (
    <div style={{ display: 'grid', gridTemplateColumns: '2px 2px', gap: 2, flex: '0 0 auto', padding: '0 2px', cursor: 'grab' }}>
      {Array.from({ length: 6 }).map((_, i) => <span key={i} style={{ width: 2, height: 2, borderRadius: '50%', background: ED_C.ink3 }} />)}
    </div>);

}

const subMenuItem = (ED_C, opts = {}) => ({
  all: 'unset', cursor: 'pointer', display: 'flex', width: '100%', boxSizing: 'border-box',
  alignItems: 'center', justifyContent: 'space-between', gap: 10,
  padding: '8px 12px', fontFamily: edUiFont, fontSize: 13, borderRadius: 3,
  color: opts.danger ? ED_C.danger : opts.accent ? ED_C.accent : ED_C.ink,
  background: opts.accent ? ED_C.accentSoft : 'transparent'
});

// ════════════════════════════════════════════════════════════════════
// WEB · two-pane manager
// ════════════════════════════════════════════════════════════════════

// ── rail category row ────────────────────────────────────────────────
function SubRailRow({ ED_C, cat, count, active, dropTarget, onClick, isDropZone,
  onDragOver, onDragLeave, onDrop,
  organize, renaming, renameVal, setRenameVal, commitRename, menuOpen, onMenu, onRename, onDelete }) {
  return (
    <div onClick={onClick}
    onDragOver={isDropZone ? onDragOver : undefined}
    onDragLeave={isDropZone ? onDragLeave : undefined}
    onDrop={isDropZone ? onDrop : undefined}
    style={{
      display: 'flex', alignItems: 'center', gap: 8, padding: '8px 10px', borderRadius: 4,
      marginBottom: 1, cursor: 'pointer', position: 'relative',
      background: active ? ED_C.accentSoft : 'transparent',
      outline: dropTarget ? `2px solid ${ED_C.accent}` : 'none', outlineOffset: -2
    }}>
      {renaming ?
      <input autoFocus value={renameVal} onClick={(e) => e.stopPropagation()}
      onChange={(e) => setRenameVal(e.target.value)}
      onBlur={() => commitRename()}
      onKeyDown={(e) => {if (e.key === 'Enter') commitRename();if (e.key === 'Escape') commitRename(true);}}
      style={{ all: 'unset', flex: 1, fontFamily: edSerifFont, fontSize: 14, fontWeight: 500,
        color: ED_C.ink, borderBottom: `1px solid ${ED_C.borderStrong}`, padding: '0 0 2px' }} /> :

      <span style={{ flex: 1, fontFamily: edSerifFont, fontSize: 14, fontWeight: 500,
        color: active ? ED_C.accent : ED_C.ink, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{cat.name}</span>
      }
      <span style={{ fontSize: 11, color: active ? ED_C.accent : ED_C.ink3, fontVariantNumeric: 'tabular-nums' }}>{count}</span>
      {organize && !cat.locked && !renaming ?
      <div style={{ position: 'relative' }}>
          <button onClick={(e) => {e.stopPropagation();onMenu();}}
        style={{ all: 'unset', cursor: 'pointer', color: ED_C.ink3, fontSize: 14, padding: '2px 4px' }}>⋯</button>
          {menuOpen ?
        <div onClick={(e) => e.stopPropagation()} style={{
          position: 'absolute', right: 0, top: 24, zIndex: 60,
          background: ED_C.panel, border: `1px solid ${ED_C.borderStrong}`, borderRadius: 4,
          boxShadow: '0 8px 24px rgba(0,0,0,.10)', minWidth: 160, padding: 4
        }}>
              <button onClick={onRename} style={subMenuItem(ED_C)}>Rename…</button>
              <button onClick={onDelete} style={subMenuItem(ED_C, { danger: true })}>Delete category…</button>
            </div> :
        null}
        </div> :
      null}
    </div>);

}

function SubRail({ ED_C, feeds, categories, sel, onSel, dragId, dropTargetId, setDropTargetId, onFeedDrop,
  organize, catMenuId, setCatMenuId, onAddCategory, onRenameCategory, onRequestDelete }) {
  const [q, setQ] = React.useState('');
  const [newOpen, setNewOpen] = React.useState(false);
  const [newVal, setNewVal] = React.useState('');
  const [renameId, setRenameId] = React.useState(null);
  const [renameVal, setRenameVal] = React.useState('');

  const shown = categories.filter((c) => c.name.toLowerCase().includes(q.trim().toLowerCase()));
  const nonLocked = shown.filter((c) => !c.locked);
  const locked = shown.find((c) => c.locked);

  const commitNew = (cancel) => {
    if (!cancel && newVal.trim()) onAddCategory(newVal.trim());
    setNewVal('');setNewOpen(false);
  };
  const startRename = (cat) => {setCatMenuId(null);setRenameId(cat.id);setRenameVal(cat.name);};
  const commitRename = (cat) => (cancel) => {
    if (!cancel && renameVal.trim() && renameVal.trim() !== cat.name) onRenameCategory(cat, renameVal.trim());
    setRenameId(null);
  };

  const railRow = (cat) =>
  <SubRailRow key={cat.id} ED_C={ED_C} cat={cat}
  count={catFeedList(feeds, cat, categories).length}
  active={sel === cat.id} dropTarget={dropTargetId === cat.id}
  onClick={() => onSel(cat.id)}
  isDropZone={!!dragId}
  onDragOver={(e) => {e.preventDefault();setDropTargetId(cat.id);}}
  onDragLeave={() => setDropTargetId((prev) => prev === cat.id ? null : prev)}
  onDrop={(e) => {e.preventDefault();onFeedDrop(cat);}}
  organize={organize}
  renaming={renameId === cat.id} renameVal={renameVal} setRenameVal={setRenameVal}
  commitRename={commitRename(cat)}
  menuOpen={catMenuId === cat.id} onMenu={() => setCatMenuId(catMenuId === cat.id ? null : cat.id)}
  onRename={() => startRename(cat)} onDelete={() => {setCatMenuId(null);onRequestDelete(cat);}} />;


  return (
    <div style={{ width: 248, flex: '0 0 248px', borderRight: `1px solid ${ED_C.border}`, height: '100%', display: 'flex', flexDirection: 'column', background: ED_C.bg }}>
      <div style={{ padding: '20px 14px 4px' }}>
        <div style={{ display: 'flex', alignItems: 'center', marginBottom: 10, padding: '0 4px' }}>
          <span style={{ fontFamily: edUiFont, fontSize: 10, letterSpacing: '.1em', textTransform: 'uppercase', color: ED_C.ink3, fontWeight: 500 }}>
            Categories · {categories.length}
          </span>
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '8px 12px',
          border: `1px solid ${ED_C.border}`, borderRadius: 4, background: ED_C.panel }}>
          <span style={{ color: ED_C.ink3, fontSize: 12 }}>⌕</span>
          <input value={q} onChange={(e) => setQ(e.target.value)} placeholder="Filter categories…"
          style={{ all: 'unset', flex: 1, fontSize: 12.5, color: ED_C.ink, fontFamily: edUiFont }} />
        </div>
      </div>

      <div style={{ flex: 1, overflow: 'auto', padding: '0 10px 10px' }} onClick={() => setCatMenuId(null)}>
        {!q ?
        <React.Fragment>
            <SubRailRow ED_C={ED_C} cat={{ id: 'all', name: 'All feeds' }} count={feeds.length}
          active={sel === 'all'} onClick={() => onSel('all')} />
            <div style={{ height: 1, background: ED_C.border, margin: '6px 8px' }} />
          </React.Fragment> :
        null}
        {nonLocked.map(railRow)}
        {locked ?
        <React.Fragment>
            <div style={{ height: 1, background: ED_C.border, margin: '6px 8px' }} />
            {railRow(locked)}
          </React.Fragment> :
        null}
      </div>

      <div style={{ padding: '10px 14px', borderTop: `1px solid ${ED_C.border}` }}>
        {newOpen ?
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '8px 12px',
          border: `1px solid ${ED_C.borderStrong}`, borderRadius: 4, background: ED_C.panel }}>
            <input autoFocus value={newVal} onChange={(e) => setNewVal(e.target.value)}
          onBlur={() => commitNew(false)}
          onKeyDown={(e) => {if (e.key === 'Enter') commitNew(false);if (e.key === 'Escape') commitNew(true);}}
          placeholder="Category name…"
          style={{ all: 'unset', flex: 1, fontSize: 12.5, color: ED_C.ink, fontFamily: edUiFont }} />
          </div> :

        <button onClick={() => setNewOpen(true)}
        style={{ all: 'unset', cursor: 'pointer', display: 'flex', alignItems: 'center', gap: 8,
          padding: '9px 12px', width: '100%', boxSizing: 'border-box',
          border: `1px dashed ${ED_C.borderStrong}`, borderRadius: 4, color: ED_C.ink3, fontSize: 12.5 }}>+ New category</button>
        }
      </div>
    </div>);

}

// ── feed row (pane) with the full overflow menu ───────────────────────
function SubFeedRow({ ED_C, f, last, categories, menu, setMenu, refreshing,
  renaming, renameVal, setRenameVal, commitRename,
  onRefresh, onMove, onRename, onInterval, onPause, onDelete, onDragStart, onDragEnd, lifted }) {
  const [newCatVal, setNewCatVal] = React.useState('');
  const open = menu && menu.id === f.id;
  const mode = open ? menu.mode : null;
  const interval = f.fetchInterval || '1h';
  const inThisCat = (c) => f.folder === c.name || c.locked && !subKnownNames(categories).includes(f.folder);

  return (
    <div draggable onDragStart={onDragStart} onDragEnd={onDragEnd}
    style={{
      display: 'flex', alignItems: 'center', gap: 12, padding: '11px 8px',
      borderBottom: last ? 'none' : `1px solid ${ED_C.border}`,
      background: 'transparent', position: 'relative', opacity: lifted ? 0.4 : 1
    }}>
      <SubHandle ED_C={ED_C} />
      {subAvatar(ED_C, f, 32)}
      <div style={{ flex: 1, minWidth: 0 }}>
        {renaming ?
        <input autoFocus value={renameVal} onChange={(e) => setRenameVal(e.target.value)}
        onBlur={() => commitRename()}
        onKeyDown={(e) => {if (e.key === 'Enter') commitRename();if (e.key === 'Escape') commitRename(true);}}
        style={{ all: 'unset', fontFamily: edSerifFont, fontSize: 15, fontWeight: 500, color: ED_C.ink,
          borderBottom: `1px solid ${ED_C.borderStrong}`, padding: '0 0 2px', width: '80%' }} /> :

        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            <span style={{ fontFamily: edSerifFont, fontSize: 15, fontWeight: 500, color: ED_C.ink }}>{f.name}</span>
            {f.paused ? <span style={{ fontFamily: edUiFont, fontSize: 9.5, letterSpacing: '.08em', textTransform: 'uppercase',
            color: ED_C.ink3, border: `1px solid ${ED_C.border}`, borderRadius: 3, padding: '1px 5px' }}>Paused</span> : null}
          </div>
        }
        <div style={{ fontSize: 11, color: ED_C.ink3, marginTop: 2, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{f.url}</div>
      </div>
      {refreshing ?
      <span style={{ display: 'inline-block', width: 13, height: 13, borderRadius: '50%',
        border: `2px solid ${ED_C.border}`, borderTopColor: ED_C.accent, animation: 'edSpin .8s linear infinite' }} /> :

      <span style={{ fontSize: 11, color: ED_C.ink3, fontVariantNumeric: 'tabular-nums', width: 46, textAlign: 'right' }}>{f.unread} new</span>
      }
      <div style={{ position: 'relative' }}>
        <button onClick={(e) => {e.stopPropagation();setMenu(open ? null : { id: f.id, mode: 'root' });}}
        style={{ all: 'unset', cursor: 'pointer', color: ED_C.ink3, fontSize: 16, padding: '2px 8px' }}>⋯</button>
        {open ?
        <div onClick={(e) => e.stopPropagation()} style={{
          position: 'absolute', right: 0, top: 28, zIndex: 70,
          background: ED_C.panel, border: `1px solid ${ED_C.borderStrong}`, borderRadius: 4,
          boxShadow: '0 8px 24px rgba(0,0,0,.12)', minWidth: 214, maxHeight: 300, overflow: 'auto', padding: 4
        }}>
            {mode === 'root' ?
          <React.Fragment>
                <button onClick={onRefresh} style={subMenuItem(ED_C)}>Refresh now</button>
                <button onClick={() => setMenu({ id: f.id, mode: 'move' })} style={subMenuItem(ED_C)}>
                  <span>Move to category…</span><span style={{ fontSize: 13, color: ED_C.ink3 }}>›</span>
                </button>
                <button onClick={onRename} style={subMenuItem(ED_C)}>Rename…</button>
                <button onClick={() => setMenu({ id: f.id, mode: 'interval' })} style={subMenuItem(ED_C)}>
                  <span>Fetch interval…</span><span style={{ fontSize: 12, color: ED_C.ink3 }}>{interval} ›</span>
                </button>
                <button onClick={onPause} style={subMenuItem(ED_C)}>{f.paused ? 'Resume updates' : 'Pause updates'}</button>
                <div style={{ height: 1, background: ED_C.border, margin: '4px 6px' }} />
                <button onClick={onDelete} style={subMenuItem(ED_C, { danger: true })}>Unsubscribe</button>
              </React.Fragment> :
          mode === 'move' ?
          <React.Fragment>
                <button onClick={() => setMenu({ id: f.id, mode: 'root' })}
            style={{ ...subMenuItem(ED_C), color: ED_C.ink3, fontSize: 11, letterSpacing: '.08em', textTransform: 'uppercase' }}>‹ Move to</button>
                {categories.map((c) => {
              const cur = inThisCat(c);
              return (
                <button key={c.id} onClick={() => onMove(c)} style={subMenuItem(ED_C, { accent: cur })}>
                      <span style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                        <span style={{ width: 6, height: 6, borderRadius: '50%',
                      background: cur ? ED_C.accent : 'transparent', border: cur ? 'none' : `1px solid ${ED_C.borderStrong}` }} />
                        {c.name}
                      </span>
                      {c.locked ? <span style={{ fontSize: 10, color: ED_C.ink3, fontStyle: 'italic', fontFamily: edSerifFont }}>default</span> : null}
                    </button>);

            })}
                <div style={{ height: 1, background: ED_C.border, margin: '4px 6px' }} />
                <div style={{ padding: '2px 6px 4px' }}>
                  <input value={newCatVal} onChange={(e) => setNewCatVal(e.target.value)}
              onKeyDown={(e) => {if (e.key === 'Enter' && newCatVal.trim()) {onMove({ name: newCatVal.trim(), isNew: true });setNewCatVal('');}}}
              placeholder="+ New category…"
              style={{ all: 'unset', width: '100%', boxSizing: 'border-box', fontSize: 12.5, color: ED_C.ink, fontFamily: edUiFont,
                border: `1px solid ${ED_C.border}`, borderRadius: 3, padding: '6px 8px' }} />
                </div>
              </React.Fragment> :

          <React.Fragment>
                <button onClick={() => setMenu({ id: f.id, mode: 'root' })}
            style={{ ...subMenuItem(ED_C), color: ED_C.ink3, fontSize: 11, letterSpacing: '.08em', textTransform: 'uppercase' }}>‹ Fetch interval</button>
                {FETCH_INTERVALS.map((iv) =>
            <button key={iv} onClick={() => onInterval(iv)} style={subMenuItem(ED_C, { accent: iv === interval })}>
                    <span style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                      <span style={{ width: 6, height: 6, borderRadius: '50%',
                  background: iv === interval ? ED_C.accent : 'transparent', border: iv === interval ? 'none' : `1px solid ${ED_C.borderStrong}` }} />
                      {iv === 'Daily' ? 'Daily' : `Every ${iv}`}
                    </span>
                  </button>
            )}
              </React.Fragment>
          }
          </div> :
        null}
      </div>
    </div>);

}

function SubPane({ ED_C, feeds, categories, cat, handlers, dragId, setDragId, setDropTargetId }) {
  const [q, setQ] = React.useState('');
  const [menu, setMenu] = React.useState(null);
  const [addOpen, setAddOpen] = React.useState(false);
  const [addUrl, setAddUrl] = React.useState('');
  const [renameId, setRenameId] = React.useState(null);
  const [renameVal, setRenameVal] = React.useState('');
  const [refreshingIds, setRefreshingIds] = React.useState(() => new Set());

  const list = catFeedList(feeds, cat, categories);
  const shown = list.filter((f) => f.name.toLowerCase().includes(q.trim().toLowerCase()));
  const targetCatName = cat.id === 'all' || cat.locked ? 'Uncategorized' : cat.name;

  const submitAdd = (e) => {
    e && e.preventDefault();
    if (!addUrl.trim()) return;
    handlers.addFeed(addUrl.trim(), targetCatName);
    setAddUrl('');setAddOpen(false);
  };
  const startRename = (f) => {setMenu(null);setRenameId(f.id);setRenameVal(f.name);};
  const commitRename = (f) => (cancel) => {
    if (!cancel && renameVal.trim()) handlers.renameFeed(f.id, renameVal.trim());
    setRenameId(null);
  };
  const refresh = (f) => {
    setMenu(null);
    setRefreshingIds((prev) => new Set(prev).add(f.id));
    setTimeout(() => setRefreshingIds((prev) => {const n = new Set(prev);n.delete(f.id);return n;}), 1200);
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
          <button onClick={() => setAddOpen((v) => !v)} style={{
            all: 'unset', cursor: 'pointer', padding: '8px 14px', borderRadius: 4, fontSize: 12.5,
            background: addOpen ? ED_C.panel : ED_C.accent, color: addOpen ? ED_C.ink2 : ED_C.onAccent,
            border: addOpen ? `1px solid ${ED_C.border}` : 'none'
          }}>{addOpen ? 'Cancel' : '+ Add feed'}</button>
        </div>

        {addOpen ?
        <form onSubmit={submitAdd} style={{ display: 'flex', gap: 8, padding: '10px 12px', marginBottom: 12,
          border: `1px solid ${ED_C.borderStrong}`, borderRadius: 4, background: ED_C.panel }}>
            <input autoFocus value={addUrl} onChange={(e) => setAddUrl(e.target.value)}
          placeholder="https://example.com/feed.xml"
          style={{ all: 'unset', flex: 1, fontSize: 13, color: ED_C.ink, fontFamily: edUiFont }} />
            <button type="submit" style={{ all: 'unset', cursor: 'pointer', padding: '6px 14px', borderRadius: 4,
            background: ED_C.ink, color: ED_C.panel, fontSize: 12.5 }}>Subscribe</button>
          </form> :
        null}

        <div style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '9px 12px',
          border: `1px solid ${ED_C.border}`, borderRadius: 4, background: ED_C.panel }}>
          <span style={{ color: ED_C.ink3 }}>⌕</span>
          <input value={q} onChange={(e) => setQ(e.target.value)}
          placeholder={`Search ${cat.id === 'all' ? 'all feeds' : cat.name}…`}
          style={{ all: 'unset', flex: 1, fontSize: 13, color: ED_C.ink, fontFamily: edUiFont }} />
        </div>
      </div>

      <div style={{ flex: 1, overflow: 'auto', padding: '10px 32px 40px' }} onClick={() => setMenu(null)}>
        {shown.length === 0 ?
        <div style={{ padding: '60px 0', textAlign: 'center', fontFamily: edSerifFont, fontStyle: 'italic', fontSize: 16, color: ED_C.ink3 }}>
            Nothing here yet.
          </div> :
        shown.map((f, i, arr) =>
        <SubFeedRow key={f.id} ED_C={ED_C} f={f} last={i === arr.length - 1} categories={categories}
        menu={menu} setMenu={setMenu} refreshing={refreshingIds.has(f.id)} lifted={dragId === f.id}
        renaming={renameId === f.id} renameVal={renameVal} setRenameVal={setRenameVal} commitRename={commitRename(f)}
        onRefresh={() => refresh(f)}
        onMove={(c) => {handlers.moveFeed(f.id, c.isNew ? handlers.addCategory(c.name) : c.name);setMenu(null);}}
        onRename={() => startRename(f)}
        onInterval={(iv) => {handlers.setInterval(f.id, iv);setMenu(null);}}
        onPause={() => {handlers.togglePause(f.id);setMenu(null);}}
        onDelete={() => {setMenu(null);if (confirm(`Unsubscribe from “${f.name}”? Its articles will be removed.`)) handlers.deleteFeed(f.id);}}
        onDragStart={() => setDragId(f.id)} onDragEnd={() => {setDragId(null);setDropTargetId(null);}} />
        )}
      </div>
    </div>);

}

// ── delete-category → reassign modal ─────────────────────────────────
function SubDeleteModal({ ED_C, cat, categories, feeds, onCancel, onConfirm }) {
  const count = catFeedList(feeds, cat, categories).length;
  const targets = categories.filter((g) => g.id !== cat.id);
  const [target, setTarget] = React.useState((targets.find((g) => g.locked) || targets[0]).name);
  return (
    <div style={{ position: 'absolute', inset: 0, zIndex: 90, background: 'rgba(20,25,40,.32)',
      backdropFilter: 'blur(2px)', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
      <div style={{ width: 460, background: ED_C.bg, border: `1px solid ${ED_C.borderStrong}`,
        boxShadow: '0 24px 60px rgba(0,0,0,.18)', padding: '32px 32px 28px', fontFamily: edUiFont, color: ED_C.ink }}>
        <div style={{ fontFamily: 'ui-monospace, monospace', fontSize: 10.5, letterSpacing: '.14em',
          textTransform: 'uppercase', color: ED_C.danger, marginBottom: 14 }}>Delete category</div>
        <div style={{ fontFamily: edSerifFont, fontSize: 24, fontWeight: 500, letterSpacing: '-.02em', lineHeight: 1.15, marginBottom: 10 }}>
          Delete “{cat.name}”?
        </div>
        <div style={{ fontFamily: edSerifFont, fontStyle: 'italic', fontSize: 14.5, color: ED_C.ink2, lineHeight: 1.5, marginBottom: 20, textWrap: 'pretty' }}>
          The category is removed, but its {count} {count === 1 ? 'feed is' : 'feeds are'} kept — choose where they go. Nothing is unsubscribed.
        </div>
        {count > 0 ?
        <div style={{ padding: 14, marginBottom: 22, background: ED_C.panel, border: `1px solid ${ED_C.border}`, borderRadius: 4 }}>
            <div style={{ fontSize: 10, letterSpacing: '.1em', textTransform: 'uppercase', color: ED_C.ink3, marginBottom: 10 }}>Move its feeds to</div>
            <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6 }}>
              {targets.map((g) => {
              const active = g.name === target;
              return (
                <button key={g.id} onClick={() => setTarget(g.name)} style={{
                  all: 'unset', cursor: 'pointer', padding: '6px 12px', borderRadius: 4, fontSize: 12.5,
                  border: `1px solid ${active ? ED_C.ink : ED_C.border}`,
                  background: active ? ED_C.ink : ED_C.panel, color: active ? ED_C.panel : ED_C.ink2
                }}>{g.name}</button>);

            })}
            </div>
          </div> :
        null}
        <div style={{ display: 'flex', gap: 8, justifyContent: 'flex-end' }}>
          <button onClick={onCancel} style={{ all: 'unset', cursor: 'pointer', padding: '6px 12px', borderRadius: 4,
            border: `1px solid ${ED_C.border}`, background: ED_C.panel, color: ED_C.ink2, fontSize: 12 }}>Cancel</button>
          <button onClick={() => onConfirm(target)} style={{ all: 'unset', cursor: 'pointer', padding: '6px 12px', borderRadius: 4,
            border: `1px solid ${ED_C.danger}`, background: ED_C.panel, color: ED_C.danger, fontSize: 12 }}>
            {count > 0 ? 'Delete & move feeds' : 'Delete category'}
          </button>
        </div>
      </div>
    </div>);

}

// ── category mutations shared by web + mobile managers ────────────────
function useSubActions(feeds, setFeeds, categories, setCategories) {
  const moveFeed = (id, catName) => setFeeds(feeds.map((f) => f.id === id ? { ...f, folder: catName } : f));
  const addFeed = (url, catName) => {
    const id = 'new' + Math.random().toString(36).slice(2, 7);
    setFeeds([...feeds, { id, name: url.replace(/^https?:\/\//, '').split('/')[0],
      author: '—', url, hue: Math.floor(Math.random() * 360), folder: catName, unread: 0 }]);
  };
  const renameFeed = (id, name) => setFeeds(feeds.map((f) => f.id === id ? { ...f, name } : f));
  // BUG-56/BUG-60: change a feed's source URL from the overflow menu, via the
  // same bottom-sheet shell as Rename feed.
  const changeUrl = (id, url) => setFeeds(feeds.map((f) => f.id === id ? { ...f, url } : f));
  const deleteFeed = (id) => setFeeds(feeds.filter((f) => f.id !== id));
  const setInterval = (id, iv) => setFeeds(feeds.map((f) => f.id === id ? { ...f, fetchInterval: iv } : f));
  const togglePause = (id) => setFeeds(feeds.map((f) => f.id === id ? { ...f, paused: !f.paused } : f));
  const addCategory = (name) => {
    if (!categories.some((c) => c.name.toLowerCase() === name.toLowerCase())) {
      const id = name.toLowerCase().replace(/[^a-z0-9]+/g, '') || 'c' + Math.random().toString(36).slice(2, 5);
      setCategories([...categories.filter((c) => !c.locked), { id, name }, ...categories.filter((c) => c.locked)]);
    }
    return name;
  };
  const renameCategory = (cat, newName) => {
    setCategories(categories.map((c) => c.id === cat.id ? { ...c, name: newName } : c));
    setFeeds(feeds.map((f) => f.folder === cat.name ? { ...f, folder: newName } : f));
  };
  const deleteCategory = (cat, targetName) => {
    setFeeds(feeds.map((f) => f.folder === cat.name ? { ...f, folder: targetName } : f));
    setCategories(categories.filter((c) => c.id !== cat.id));
  };
  return { moveFeed, addFeed, renameFeed, changeUrl, deleteFeed, setInterval, togglePause, addCategory, renameCategory, deleteCategory };
}

// ── top-level web manager ────────────────────────────────────────────
function SubsWeb({ feeds, setFeeds, categories, setCategories }) {
  const ED_C = React.useContext(EdThemeContext);
  const [sel, setSel] = React.useState(() => {
    const first = categories.find((c) => !c.locked);
    return first ? first.id : 'all';
  });
  const organize = true;
  const [catMenuId, setCatMenuId] = React.useState(null);
  const [dragId, setDragId] = React.useState(null);
  const [dropTargetId, setDropTargetId] = React.useState(null);
  const [deleteCat, setDeleteCat] = React.useState(null);

  const A = useSubActions(feeds, setFeeds, categories, setCategories);
  const cat = categories.find((c) => c.id === sel) || { id: 'all', name: 'All feeds' };

  const onFeedDrop = (targetCat) => {
    if (dragId) A.moveFeed(dragId, targetCat.locked ? 'Uncategorized' : targetCat.name);
    setDragId(null);setDropTargetId(null);
  };
  const confirmDelete = (targetName) => {
    A.deleteCategory(deleteCat, targetName);
    if (sel === deleteCat.id) {const first = categories.find((c) => !c.locked && c.id !== deleteCat.id);setSel(first ? first.id : 'all');}
    setDeleteCat(null);
  };

  return (
    <div style={{ flex: 1, height: '100%', display: 'flex', minWidth: 0, position: 'relative' }}>
      <SubRail ED_C={ED_C} feeds={feeds} categories={categories} sel={sel} onSel={setSel}
      dragId={dragId} dropTargetId={dropTargetId} setDropTargetId={setDropTargetId} onFeedDrop={onFeedDrop}
      organize={organize} catMenuId={catMenuId} setCatMenuId={setCatMenuId}
      onAddCategory={A.addCategory} onRenameCategory={A.renameCategory} onRequestDelete={setDeleteCat} />
      <SubPane ED_C={ED_C} feeds={feeds} categories={categories} cat={cat} handlers={A}
      dragId={dragId} setDragId={setDragId} setDropTargetId={setDropTargetId} />
      {deleteCat ?
      <SubDeleteModal ED_C={ED_C} cat={deleteCat} categories={categories} feeds={feeds}
      onCancel={() => setDeleteCat(null)} onConfirm={confirmDelete} /> :
      null}
    </div>);

}

// ════════════════════════════════════════════════════════════════════
// ANDROID · master-detail + Organizing mode + bottom-sheet flows
// ════════════════════════════════════════════════════════════════════

function SubMScrim({ onClick }) {
  return <div onClick={onClick} style={{ position: 'absolute', inset: 0, background: 'rgba(20,25,40,.32)', backdropFilter: 'blur(2px)', zIndex: 40 }} />;
}

function SubMSheet({ ED_C, title, children, primary, primaryDanger, onPrimary, onCancel, secondary = 'Cancel' }) {
  return (
    <div style={{ position: 'absolute', left: 0, right: 0, bottom: 0, zIndex: 50,
      background: ED_C.bg, borderTop: `1px solid ${ED_C.borderStrong}`,
      borderTopLeftRadius: 14, borderTopRightRadius: 14,
      boxShadow: '0 -12px 40px rgba(0,0,0,.16)', padding: '10px 0 30px', fontFamily: edUiFont }}>
      <div style={{ width: 36, height: 4, borderRadius: 2, background: ED_C.border, margin: '0 auto 14px' }} />
      {title ? <div style={{ padding: '0 22px 10px', fontFamily: edSerifFont, fontSize: 20, fontWeight: 500, letterSpacing: '-.015em', color: ED_C.ink }}>{title}</div> : null}
      {children}
      {primary || secondary ?
      <div style={{ display: 'flex', gap: 10, padding: '14px 22px 0' }}>
          <button onClick={onCancel} style={{ all: 'unset', cursor: 'pointer', flex: 1, textAlign: 'center', padding: '12px 0', borderRadius: 4,
          border: `1px solid ${ED_C.border}`, background: ED_C.panel, color: ED_C.ink2, fontSize: 14 }}>{secondary}</button>
          {primary ?
        <button onClick={onPrimary} style={{ all: 'unset', cursor: 'pointer', flex: 1, textAlign: 'center', padding: '12px 0', borderRadius: 4,
          background: primaryDanger ? ED_C.panel : ED_C.ink, color: primaryDanger ? ED_C.danger : ED_C.panel,
          border: primaryDanger ? `1px solid ${ED_C.danger}` : 'none', fontSize: 14 }}>{primary}</button> :
        null}
        </div> :
      null}
    </div>);

}

function SubMRadioRow({ ED_C, label, active, note }) {
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 12, padding: '12px 22px', background: active ? ED_C.accentSoft : 'transparent' }}>
      <span style={{ width: 18, height: 18, borderRadius: '50%', flex: '0 0 auto',
        border: `1px solid ${active ? ED_C.accent : ED_C.borderStrong}`, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
        {active ? <span style={{ width: 9, height: 9, borderRadius: '50%', background: ED_C.accent }} /> : null}
      </span>
      <span style={{ flex: 1, fontFamily: edSerifFont, fontSize: 16, fontWeight: 500, color: active ? ED_C.accent : ED_C.ink }}>{label}</span>
      {note ? <span style={{ fontSize: 11, color: ED_C.ink3, fontStyle: 'italic', fontFamily: edSerifFont }}>{note}</span> : null}
    </div>);

}

function SubMHeader({ ED_C, title, subtitle, back, right, topInset = 14 }) {
  return (
    <div style={{ paddingTop: topInset + 14, paddingLeft: 22, paddingRight: 22, paddingBottom: 16,
      background: ED_C.bg, borderBottom: `1px solid ${ED_C.border}`, fontFamily: edUiFont, color: ED_C.ink, flex: '0 0 auto',
      display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', gap: 12 }}>
      <div style={{ minWidth: 0 }}>
        {back ? <div onClick={back} style={{ color: ED_C.accent, fontSize: 14, marginBottom: 8, cursor: 'pointer' }}>‹ Feeds</div> : null}
        <h1 style={{ fontFamily: edSerifFont, fontSize: 30, fontWeight: 500, letterSpacing: '-.02em', lineHeight: 1.05, margin: 0 }}>{title}</h1>
        {subtitle ? <div style={{ fontSize: 12, color: ED_C.ink3, marginTop: 6 }}>{subtitle}</div> : null}
      </div>
      {right}
    </div>);

}

function SubsMobile({ feeds, setFeeds, categories, setCategories, topInset = 14 }) {
  const ED_C = React.useContext(EdThemeContext);
  const A = useSubActions(feeds, setFeeds, categories, setCategories);
  const [q, setQ] = React.useState('');
  const [searchOpen, setSearchOpen] = React.useState(false); // app-bar search toggle
  const [screenMenu, setScreenMenu] = React.useState(false); // app-bar overflow (⋯) menu
  const [sheet, setSheet] = React.useState(null); // { type, feed?, cat? }
  const [tmp, setTmp] = React.useState(''); // sheet text/selection buffer
  const [refreshingIds, setRefreshingIds] = React.useState(() => new Set());
  const [feedMenu, setFeedMenu] = React.useState(null); // open feed's id
  const [catMenu, setCatMenu] = React.useState(null); // open category header's id (organize)

  const toggleSearch = () => {
    setScreenMenu(false);
    setSearchOpen((v) => {
      const next = !v;
      if (!next) setQ('');
      return next;
    });
  };

  const closeSheet = () => {setSheet(null);setTmp('');};
  const openSheet = (s, initial = '') => {setSheet(s);setTmp(initial);setFeedMenu(null);setCatMenu(null);};

  const refresh = (f) => {
    closeSheet();
    setRefreshingIds((prev) => new Set(prev).add(f.id));
    setTimeout(() => setRefreshingIds((prev) => {const n = new Set(prev);n.delete(f.id);return n;}), 1200);
  };

  // ── sheets ──────────────────────────────────────────────────────────
  let scrim = null,sheetEl = null;
  if (sheet) {
    scrim = <SubMScrim onClick={closeSheet} />;
    if (sheet.type === 'move') {
      const f = sheet.feed;
      const isCur = (c) => tmp === c.name || c.locked && !subKnownNames(categories).includes(tmp);
      sheetEl =
      <SubMSheet ED_C={ED_C} title={`Move “${f.name}”`} primary="Move" onPrimary={() => {A.moveFeed(f.id, tmp);closeSheet();}} onCancel={closeSheet}>
          <div style={{ maxHeight: 300, overflow: 'auto' }}>
            {categories.map((c) =>
          <div key={c.id} onClick={() => setTmp(c.name)} style={{ cursor: 'pointer' }}>
                <SubMRadioRow ED_C={ED_C} label={c.name} active={isCur(c)}
            note={f.folder === c.name ? 'current' : c.locked ? 'default' : null} />
              </div>
          )}
          </div>
          <div onClick={() => openSheet({ type: 'newCatThenMove', feed: f }, '')}
        style={{ padding: '12px 22px', fontFamily: edUiFont, fontSize: 14, color: ED_C.ink2, cursor: 'pointer' }}>+ New category…</div>
        </SubMSheet>;

    } else if (sheet.type === 'renameFeed') {
      const f = sheet.feed;
      sheetEl =
      <SubMSheet ED_C={ED_C} title="Rename feed" primary="Save"
      onPrimary={() => {if (tmp.trim()) A.renameFeed(f.id, tmp.trim());closeSheet();}} onCancel={closeSheet}>
          <div style={{ padding: '4px 22px' }}>
            <input autoFocus value={tmp} onChange={(e) => setTmp(e.target.value)}
          style={{ all: 'unset', width: '100%', boxSizing: 'border-box', fontSize: 15, color: ED_C.ink, fontFamily: edUiFont,
            padding: '12px 14px', border: `1px solid ${ED_C.borderStrong}`, borderRadius: 4, background: ED_C.panel }} />
          </div>
        </SubMSheet>;

    } else if (sheet.type === 'changeUrl') {
      const f = sheet.feed;
      sheetEl =
      <SubMSheet ED_C={ED_C} title="Change Feed URL" primary="Save"
      onPrimary={() => {if (tmp.trim()) A.changeUrl(f.id, tmp.trim());closeSheet();}} onCancel={closeSheet}>
          <div style={{ padding: '4px 22px' }}>
            <input autoFocus value={tmp} onChange={(e) => setTmp(e.target.value)}
          style={{ all: 'unset', width: '100%', boxSizing: 'border-box', fontSize: 15, color: ED_C.ink, fontFamily: edUiFont,
            padding: '12px 14px', border: `1px solid ${ED_C.borderStrong}`, borderRadius: 4, background: ED_C.panel }} />
          </div>
        </SubMSheet>;

    } else if (sheet.type === 'interval') {
      const f = sheet.feed;
      sheetEl =
      <SubMSheet ED_C={ED_C} title="Fetch interval" primary="Set"
      onPrimary={() => {A.setInterval(f.id, tmp);closeSheet();}} onCancel={closeSheet}>
          {FETCH_INTERVALS.map((iv) =>
        <div key={iv} onClick={() => setTmp(iv)} style={{ cursor: 'pointer' }}>
              <SubMRadioRow ED_C={ED_C} label={iv === 'Daily' ? 'Daily' : `Every ${iv}`} active={tmp === iv} />
            </div>
        )}
        </SubMSheet>;

    } else if (sheet.type === 'addFeed') {
      const defaultCat = (categories.find((c) => c.locked) || {}).name || 'Uncategorized';
      sheetEl =
      <SubMSheet ED_C={ED_C} title="Add feed" primary="Subscribe"
      onPrimary={() => {if (tmp.trim()) A.addFeed(tmp.trim(), defaultCat);closeSheet();}} onCancel={closeSheet}>
          <div style={{ padding: '4px 22px' }}>
            <input autoFocus value={tmp} onChange={(e) => setTmp(e.target.value)} placeholder="https://example.com/feed.xml"
          style={{ all: 'unset', width: '100%', boxSizing: 'border-box', fontSize: 15, color: ED_C.ink, fontFamily: edUiFont,
            padding: '12px 14px', border: `1px solid ${ED_C.borderStrong}`, borderRadius: 4, background: ED_C.panel }} />
            <div style={{ fontSize: 12, color: ED_C.ink3, marginTop: 8 }}>
              Added to “{defaultCat}” — move it to another category afterward from the feed’s ⋯ menu.
            </div>
          </div>
        </SubMSheet>;

    } else if (sheet.type === 'newCat' || sheet.type === 'newCatThenMove') {
      const f = sheet.feed;
      sheetEl =
      <SubMSheet ED_C={ED_C} title="New category" primary="Create"
      onPrimary={() => {if (tmp.trim()) {A.addCategory(tmp.trim());if (f) A.moveFeed(f.id, tmp.trim());}closeSheet();}} onCancel={closeSheet}>
          <div style={{ padding: '4px 22px' }}>
            <input autoFocus value={tmp} onChange={(e) => setTmp(e.target.value)} placeholder="Category name…"
          style={{ all: 'unset', width: '100%', boxSizing: 'border-box', fontSize: 15, color: ED_C.ink, fontFamily: edUiFont,
            padding: '12px 14px', border: `1px solid ${ED_C.borderStrong}`, borderRadius: 4, background: ED_C.panel }} />
            <div style={{ fontSize: 12, color: ED_C.ink3, marginTop: 8 }}>
              {f ? `“${f.name}” moves here once created.` : 'Move feeds in from each feed’s ⋯ menu afterward.'}
            </div>
          </div>
        </SubMSheet>;

    } else if (sheet.type === 'renameCat') {
      const c = sheet.cat;
      sheetEl =
      <SubMSheet ED_C={ED_C} title="Rename category" primary="Save"
      onPrimary={() => {if (tmp.trim()) A.renameCategory(c, tmp.trim());closeSheet();}} onCancel={closeSheet}>
          <div style={{ padding: '4px 22px' }}>
            <input autoFocus value={tmp} onChange={(e) => setTmp(e.target.value)}
          style={{ all: 'unset', width: '100%', boxSizing: 'border-box', fontSize: 15, color: ED_C.ink, fontFamily: edUiFont,
            padding: '12px 14px', border: `1px solid ${ED_C.borderStrong}`, borderRadius: 4, background: ED_C.panel }} />
          </div>
        </SubMSheet>;

    } else if (sheet.type === 'deleteCat') {
      const c = sheet.cat;
      const count = catFeedList(feeds, c, categories).length;
      const targets = categories.filter((g) => g.id !== c.id);
      sheetEl =
      <SubMSheet ED_C={ED_C} title={`Delete “${c.name}”?`} primary="Delete & move" primaryDanger
      onPrimary={() => {A.deleteCategory(c, tmp);closeSheet();}} onCancel={closeSheet}>
          <div style={{ padding: '0 22px 6px', fontFamily: edSerifFont, fontStyle: 'italic', fontSize: 14, color: ED_C.ink2, lineHeight: 1.5 }}>
            {count > 0 ? `The ${count} ${count === 1 ? 'feed is' : 'feeds are'} kept — pick where they go. Nothing is unsubscribed.` : 'This category is empty. Nothing is unsubscribed.'}
          </div>
          {count > 0 ?
        <React.Fragment>
              <div style={{ padding: '12px 22px 4px', fontSize: 10, letterSpacing: '.1em', textTransform: 'uppercase', color: ED_C.ink3 }}>Move its feeds to</div>
              <div style={{ maxHeight: 240, overflow: 'auto' }}>
                {targets.map((g) =>
            <div key={g.id} onClick={() => setTmp(g.name)} style={{ cursor: 'pointer' }}>
                    <SubMRadioRow ED_C={ED_C} label={g.name} active={tmp === g.name} note={g.locked ? 'default' : null} />
                  </div>
            )}
              </div>
            </React.Fragment> :
        null}
        </SubMSheet>;

    }
  }

  // ── grouped Feeds list (browse) + Organizing mode ──────────────────
  const mitem = (label, onClick, opts = {}) =>
  <button onClick={onClick} style={{ all: 'unset', cursor: 'pointer', display: 'flex', width: '100%', boxSizing: 'border-box',
    justifyContent: 'space-between', alignItems: 'center', gap: 10, padding: '11px 14px', fontSize: 14, borderRadius: 4,
    color: opts.danger ? ED_C.danger : opts.accent ? ED_C.accent : ED_C.ink,
    background: opts.accent ? ED_C.accentSoft : 'transparent', fontWeight: opts.accent ? 600 : 400 }}>
      <span>{label}</span>{opts.tail ? <span style={{ fontSize: 12, color: ED_C.ink3 }}>{opts.tail}</span> : null}
    </button>;

  const menuCard = (up, minWidth) => ({
    position: 'absolute', right: 0, [up ? 'bottom' : 'top']: 26, zIndex: 45,
    background: ED_C.panel, border: `1px solid ${ED_C.borderStrong}`, borderRadius: 6,
    boxShadow: '0 10px 28px rgba(0,0,0,.14)', minWidth, padding: 4
  });
  // The feed's ⋯ overflow menu — the full per-feed action set. Move / Rename /
  // Change URL / Fetch interval open their bottom sheets; Refresh / Pause /
  // Unsubscribe act inline.
  const feedMenuEl = (f, up) =>
  <div onClick={(e) => e.stopPropagation()} style={menuCard(up, 214)}>
      {mitem('Refresh now', () => refresh(f))}
      {mitem('Move to category…', () => openSheet({ type: 'move', feed: f }, f.folder), { accent: true, tail: '›' })}
      {mitem('Rename…', () => openSheet({ type: 'renameFeed', feed: f }, f.name))}
      {mitem('Change URL…', () => openSheet({ type: 'changeUrl', feed: f }, f.url))}
      {mitem('Fetch interval…', () => openSheet({ type: 'interval', feed: f }, f.fetchInterval || '1h'), { tail: f.fetchInterval || '1h' })}
      {mitem(f.paused ? 'Resume updates' : 'Pause updates', () => {A.togglePause(f.id);setFeedMenu(null);})}
      <div style={{ height: 1, background: ED_C.border, margin: '4px 6px' }} />
      {mitem('Unsubscribe', () => {setFeedMenu(null);if (confirm(`Unsubscribe from “${f.name}”? Its articles will be removed.`)) A.deleteFeed(f.id);}, { danger: true })}
    </div>;

  const catMenuEl = (c) =>
  <div onClick={(e) => e.stopPropagation()} style={menuCard(false, 176)}>
      {mitem('Rename…', () => openSheet({ type: 'renameCat', cat: c }, c.name))}
      {mitem('Delete category…', () => openSheet({ type: 'deleteCat', cat: c }, (categories.find((g) => g.locked) || {}).name || 'Uncategorized'), { danger: true })}
    </div>;


  const ql = q.trim().toLowerCase();
  const groups = categories.map((c) => {
    const gf = catFeedList(feeds, c, categories);
    return { cat: c, total: gf.length, shown: gf.filter((f) => f.name.toLowerCase().includes(ql)) };
  });
  // Every category always shows (even empty ones) so it can be renamed /
  // deleted via its ever-present ⋯ menu. Searching drops empty groups.
  const visible = groups.filter((g) => ql ? g.shown.length > 0 : true);
  const flat = [];
  visible.forEach((g) => g.shown.forEach((f) => flat.push(f.id)));
  const openUp = (id) => flat.length > 4 && flat.indexOf(id) >= flat.length - 3;

  // App-bar icon button — matches the reader top bar's icon-button shape
  // (1px border, panel fill, ink2 glyph), with an "active" (toggled-open) state.
  const appBarBtn = (active) => ({
    all: 'unset', cursor: 'pointer', width: 32, height: 32, borderRadius: 4,
    border: `1px solid ${active ? ED_C.borderStrong : ED_C.border}`,
    background: active ? ED_C.accentSoft : ED_C.panel,
    color: active ? ED_C.accent : ED_C.ink2,
    display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
    fontSize: 14, flexShrink: 0,
  });

  const appBarActions =
  <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
      <button onClick={toggleSearch} style={appBarBtn(searchOpen)} aria-label="Search feeds" title="Search feeds">⌕</button>
      <button onClick={() => openSheet({ type: 'addFeed' }, '')} style={appBarBtn(false)} aria-label="Add feed" title="Add feed">+</button>
      <div style={{ position: 'relative' }}>
        <button onClick={(e) => {e.stopPropagation();setScreenMenu((v) => !v);setFeedMenu(null);setCatMenu(null);}}
        style={appBarBtn(screenMenu)} aria-label="More actions" title="More actions">⋯</button>
        {screenMenu ?
      <div onClick={(e) => e.stopPropagation()} style={menuCard(false, 176)}>
            {mitem('+ New category…', () => {setScreenMenu(false);openSheet({ type: 'newCat' }, '');})}
          </div> :
      null}
      </div>
    </div>;

  return (
    <div style={{ height: '100%', display: 'flex', flexDirection: 'column' }}>
      <div onClick={() => {setFeedMenu(null);setCatMenu(null);setScreenMenu(false);}}
      style={{ flex: 1, minHeight: 0, overflow: 'auto', paddingBottom: 100, background: ED_C.bg, display: 'flex', flexDirection: 'column' }}>
        <SubMHeader ED_C={ED_C} topInset={topInset} title="Feeds"
        subtitle={`${feeds.length} subscriptions · ${categories.length} categories`}
        right={appBarActions} />

        {searchOpen ?
        <div style={{ padding: '14px 22px 4px' }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '10px 14px',
            border: `1px solid ${ED_C.border}`, borderRadius: 4, background: ED_C.panel }}>
              <span style={{ color: ED_C.ink3 }}>⌕</span>
              <input autoFocus value={q} onChange={(e) => setQ(e.target.value)} placeholder="Search feeds…"
              style={{ all: 'unset', flex: 1, fontSize: 13, color: ED_C.ink, fontFamily: edUiFont }} />
            </div>
          </div> :
        null}

        {visible.length === 0 ?
        <div style={{ padding: '48px 22px', textAlign: 'center', fontFamily: edSerifFont, fontStyle: 'italic', fontSize: 15, color: ED_C.ink3 }}>Nothing here yet.</div> :
        visible.map((g) =>
        <div key={g.cat.id}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 10, padding: '20px 22px 6px' }}>
              <span style={{ fontFamily: edUiFont, fontSize: 10, letterSpacing: '.1em', textTransform: 'uppercase', color: ED_C.ink3, fontWeight: 500 }}>{g.cat.name}</span>
              <span style={{ fontSize: 10.5, color: ED_C.ink3, fontVariantNumeric: 'tabular-nums' }}>{g.total}</span>
              <span style={{ flex: 1 }} />
              {!g.cat.locked ?
            <div style={{ position: 'relative' }}>
                  <button onClick={(e) => {e.stopPropagation();setCatMenu(catMenu === g.cat.id ? null : g.cat.id);setFeedMenu(null);}}
              style={{ all: 'unset', cursor: 'pointer', color: ED_C.ink3, fontSize: 16, padding: '0 4px' }}>⋯</button>
                  {catMenu === g.cat.id ? catMenuEl(g.cat) : null}
                </div> :
            null}
            </div>

            {g.shown.length === 0 ?
          <div style={{ padding: '6px 22px 12px', fontFamily: edSerifFont, fontStyle: 'italic', fontSize: 13.5, color: ED_C.ink3 }}>Nothing here yet.</div> :
          g.shown.map((f, i, arr) =>
          <div key={f.id} style={{ display: 'flex', alignItems: 'center', gap: 14, padding: '13px 22px',
            borderBottom: i === arr.length - 1 ? 'none' : `1px solid ${ED_C.border}` }}>
                {subAvatar(ED_C, f, 34)}
                <div style={{ flex: 1, minWidth: 0 }}>
                  <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                    <span style={{ fontFamily: edSerifFont, fontSize: 15, fontWeight: 500, color: ED_C.ink }}>{f.name}</span>
                    {f.paused ? <span style={{ fontFamily: edUiFont, fontSize: 9.5, letterSpacing: '.08em', textTransform: 'uppercase',
                  color: ED_C.ink3, border: `1px solid ${ED_C.border}`, borderRadius: 3, padding: '1px 5px' }}>Paused</span> : null}
                  </div>
                  <div style={{ fontSize: 11, color: ED_C.ink3, marginTop: 2, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{f.url}</div>
                </div>
                {refreshingIds.has(f.id) ?
            <span style={{ display: 'inline-block', width: 13, height: 13, borderRadius: '50%',
              border: `2px solid ${ED_C.border}`, borderTopColor: ED_C.accent, animation: 'edSpin .8s linear infinite' }} /> :

            <span style={{ fontSize: 11, color: ED_C.ink3, fontVariantNumeric: 'tabular-nums' }}>{f.unread}</span>
            }
                <div style={{ position: 'relative' }}>
                  <button onClick={(e) => {e.stopPropagation();setFeedMenu(feedMenu === f.id ? null : f.id);setCatMenu(null);}}
              style={{ all: 'unset', cursor: 'pointer', fontSize: 16, color: ED_C.ink3, padding: '0 4px' }}>⋯</button>
                  {feedMenu === f.id ? feedMenuEl(f, openUp(f.id)) : null}
                </div>
              </div>
          )}
          </div>
        )}

      </div>
      {scrim}
      {sheetEl}
      <style>{`@keyframes edSpin { to { transform: rotate(360deg); } }`}</style>
    </div>);

}

Object.assign(window, {
  FETCH_INTERVALS, makeInitialCategories, subKnownNames, catFeedList, useSubActions,
  SubsWeb, SubsMobile,
  // Shared row/rail/pane atoms — exposed so other prototype files (edge cases,
  // subscriptions-errors) can compose the same category-manager anatomy
  // instead of forking a second implementation.
  SubRail, SubRailRow, SubFeedRow, SubPane, SubHandle, subAvatar, subMenuItem,
  SubDeleteModal, SubMHeader,
});
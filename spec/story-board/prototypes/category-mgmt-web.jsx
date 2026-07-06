// Category management — exploration (web / Paper palette).
// Standalone from the story board. Reuses editorial.jsx palette + fonts +
// EdSidebar (read-only per the answered scoping questions). Three directions
// for WHERE management lives, then the operations worked through in Direction A.
//
// Category model (answered scope):
//   • strict 1 category per feed
//   • a permanent "Uncategorized" group, un-renamable + un-deletable, sorts last
//   • the web sidebar stays read-only — management lives only on Subscriptions

const CM = ED_PALETTES.paper;

function cmGroups(feeds) {
  const order = ['Craft', 'Tech', 'Reading'];
  const named = order.map(name => ({
    name, id: name.toLowerCase(),
    feeds: feeds.filter(f => f.folder === name),
  }));
  const uncat = {
    name: 'Uncategorized', id: 'uncat', locked: true,
    feeds: feeds.filter(f => !order.includes(f.folder)),
  };
  return [...named, uncat];
}

// ── atoms ───────────────────────────────────────────────────────────
function CMAvatar({ f, size = 34, dim = false }) {
  return (
    <div style={{
      width: size, height: size, borderRadius: 4, flex: '0 0 auto',
      background: `oklch(0.85 0.05 ${f.hue})`, color: `oklch(0.35 0.08 ${f.hue})`,
      display: 'flex', alignItems: 'center', justifyContent: 'center',
      fontFamily: edSerifFont, fontWeight: 500, fontSize: Math.round(size * 0.44),
      opacity: dim ? 0.6 : 1,
    }}>{f.name[0]}</div>
  );
}

// 2×3 dot drag handle — no SVG, pure grid of dots.
function CMHandle({ active = false }) {
  return (
    <div style={{
      display: 'grid', gridTemplateColumns: '2px 2px', gap: 2,
      flex: '0 0 auto', cursor: 'grab', padding: '0 2px',
    }}>
      {Array.from({ length: 6 }).map((_, i) => (
        <span key={i} style={{
          width: 2, height: 2, borderRadius: '50%',
          background: active ? CM.ink2 : CM.ink3,
        }} />
      ))}
    </div>
  );
}

function CMCheckbox({ checked }) {
  return (
    <div style={{
      width: 16, height: 16, borderRadius: 3, flex: '0 0 auto',
      border: `1px solid ${checked ? CM.ink : CM.borderStrong}`,
      background: checked ? CM.ink : 'transparent',
      display: 'flex', alignItems: 'center', justifyContent: 'center',
      color: CM.panel, fontSize: 10,
    }}>{checked ? '✓' : ''}</div>
  );
}

const cmBtn = (variant = 'ghost') => {
  const base = {
    all: 'unset', cursor: 'pointer', padding: '6px 12px', borderRadius: 4,
    fontFamily: edUiFont, fontSize: 12, whiteSpace: 'nowrap',
    display: 'inline-flex', alignItems: 'center', gap: 6,
  };
  if (variant === 'solid') return { ...base, background: CM.ink, color: CM.panel };
  if (variant === 'accent') return { ...base, background: CM.accent, color: CM.onAccent, padding: '8px 14px' };
  if (variant === 'danger') return { ...base, border: `1px solid ${CM.danger}`, background: CM.panel, color: CM.danger };
  return { ...base, border: `1px solid ${CM.border}`, background: CM.panel, color: CM.ink2 };
};

const cmHeaderLabel = {
  fontFamily: edUiFont, fontSize: 10, letterSpacing: '.1em',
  textTransform: 'uppercase', color: CM.ink3, fontWeight: 500,
};

// ── page chrome ─────────────────────────────────────────────────────
function CMPageHead({ title = 'Subscriptions', right }) {
  return (
    <div style={{ display: 'flex', alignItems: 'baseline', justifyContent: 'space-between', marginBottom: 22 }}>
      <h1 style={{ fontFamily: edSerifFont, fontSize: 28, fontWeight: 500, letterSpacing: '-.02em', margin: 0, color: CM.ink }}>{title}</h1>
      <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>{right}</div>
    </div>
  );
}

function CMSearch({ count = FEEDS.length, mb = 24 }) {
  return (
    <div style={{
      display: 'flex', alignItems: 'center', gap: 8, padding: '10px 14px',
      border: `1px solid ${CM.border}`, borderRadius: 4, background: CM.panel, marginBottom: mb,
    }}>
      <span style={{ color: CM.ink3 }}>⌕</span>
      <span style={{ flex: 1, fontSize: 13, color: CM.ink3, fontFamily: edUiFont }}>Search subscriptions…</span>
      <span style={{ fontSize: 11, color: CM.ink3, fontFamily: edUiFont }}>{count} feeds</span>
    </div>
  );
}

// Static read-only sidebar (grouped by folder) — reused from editorial.jsx.
function CMShell({ url = 'feed.app/subscriptions', children }) {
  return (
    <ChromeWindow tabs={[{ title: 'Feed — RSS' }, { title: 'inbox' }, { title: 'New Tab' }]}
      activeIndex={0} url={url} width={1180} height={760}>
      <EdThemeContext.Provider value={CM}>
        <div style={{ width: '100%', height: '100%', display: 'flex', background: CM.bg, fontFamily: edUiFont }}>
          <EdSidebar screen="subs" setScreen={() => {}} selectedFeed={null} setSelectedFeed={() => {}}
            syncState="ok" feeds={FEEDS} unreadCount={FEEDS.reduce((a, f) => a + f.unread, 0)} />
          {children}
        </div>
      </EdThemeContext.Provider>
    </ChromeWindow>
  );
}

function CMContent({ children, maxWidth = 720 }) {
  return (
    <div style={{ flex: 1, height: '100%', overflow: 'auto', background: CM.bg, fontFamily: edUiFont, color: CM.ink, position: 'relative' }}>
      <div style={{ maxWidth, margin: '0 auto', padding: '48px 40px 80px' }}>{children}</div>
    </div>
  );
}

// ── category header (organize mode) ─────────────────────────────────
function CMCatHeader({ cat, organize, renaming, menuOpen }) {
  return (
    <div style={{
      display: 'flex', alignItems: 'center', gap: 10,
      padding: '0 0 8px', marginTop: 8, position: 'relative',
      borderBottom: `1px solid ${CM.border}`, marginBottom: 6,
    }}>
      {organize && !cat.locked ? <CMHandle /> : null}
      {renaming ? (
        <input autoFocus defaultValue={cat.name} style={{
          all: 'unset', ...cmHeaderLabel, letterSpacing: '.1em',
          borderBottom: `1px solid ${CM.borderStrong}`, padding: '0 0 2px', minWidth: 120,
        }} />
      ) : (
        <span style={{ ...cmHeaderLabel }}>{cat.name}</span>
      )}
      <span style={{ fontSize: 10.5, color: CM.ink3, fontVariantNumeric: 'tabular-nums' }}>
        {cat.feeds.length}
      </span>
      <span style={{ flex: 1 }} />
      {organize && !cat.locked ? (
        <div style={{ position: 'relative' }}>
          <span style={{ color: CM.ink3, fontSize: 15, cursor: 'pointer', padding: '2px 6px' }}>⋯</span>
          {menuOpen ? (
            <div style={{
              position: 'absolute', right: 0, top: 24, zIndex: 50,
              background: CM.panel, border: `1px solid ${CM.borderStrong}`, borderRadius: 4,
              boxShadow: '0 8px 24px rgba(0,0,0,.10)', minWidth: 150, padding: 4,
            }}>
              <div style={cmMenuItem()}>Rename…</div>
              <div style={cmMenuItem()}>New category below</div>
              <div style={{ ...cmMenuItem(), color: CM.danger }}>Delete category…</div>
            </div>
          ) : null}
        </div>
      ) : null}
    </div>
  );
}

const cmMenuItem = (danger) => ({
  display: 'block', width: '100%', boxSizing: 'border-box',
  padding: '7px 12px', fontFamily: edUiFont, fontSize: 13,
  color: danger ? CM.danger : CM.ink, borderRadius: 3, cursor: 'pointer',
});

// ── feed row · organize mode ────────────────────────────────────────
function CMOrganizeRow({ f, selected, lifted, last }) {
  return (
    <div style={{
      display: 'flex', alignItems: 'center', gap: 12, padding: '11px 8px',
      borderBottom: last ? 'none' : `1px solid ${CM.border}`,
      background: lifted ? CM.panel : (selected ? CM.accentSoft : 'transparent'),
      borderRadius: lifted ? 4 : 0,
      boxShadow: lifted ? '0 8px 24px rgba(0,0,0,.12)' : 'none',
      position: 'relative',
    }}>
      <CMHandle active={lifted} />
      <CMCheckbox checked={selected} />
      <CMAvatar f={f} size={32} />
      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={{ fontFamily: edSerifFont, fontSize: 15, fontWeight: 500, color: CM.ink }}>{f.name}</div>
        <div style={{ fontSize: 11, color: CM.ink3, marginTop: 2, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{f.url}</div>
      </div>
      <span style={{ fontSize: 11, color: CM.ink3, fontVariantNumeric: 'tabular-nums' }}>{f.unread} new</span>
    </div>
  );
}

// ── feed row · browse mode (Direction C / normal) ──────────────────
function CMBrowseRow({ f, last, menu }) {
  return (
    <div style={{
      display: 'flex', alignItems: 'center', gap: 14, padding: '12px 0',
      borderBottom: last ? 'none' : `1px solid ${CM.border}`, position: 'relative',
    }}>
      <CMAvatar f={f} size={34} />
      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={{ fontFamily: edSerifFont, fontSize: 16, fontWeight: 500, color: CM.ink }}>{f.name}</div>
        <div style={{ fontSize: 11.5, color: CM.ink3, marginTop: 2 }}>{f.url}</div>
      </div>
      <span style={{ fontSize: 11, color: CM.ink3, width: 60, textAlign: 'right', fontVariantNumeric: 'tabular-nums' }}>{f.unread} new</span>
      <div style={{ position: 'relative' }}>
        <span style={{ color: CM.ink3, padding: '4px 8px', cursor: 'pointer' }}>⋯</span>
        {menu}
      </div>
    </div>
  );
}

function CMNewCategoryRow({ active }) {
  return (
    <div style={{ marginTop: 18 }}>
      {active ? (
        <div style={{
          display: 'flex', alignItems: 'center', gap: 8, padding: '10px 12px',
          border: `1px solid ${CM.borderStrong}`, borderRadius: 4, background: CM.panel,
        }}>
          <span style={cmHeaderLabel}>New</span>
          <input autoFocus placeholder="Category name…" style={{
            all: 'unset', flex: 1, fontSize: 13, color: CM.ink, fontFamily: edUiFont,
          }} />
          <button style={cmBtn('solid')}>Create</button>
        </div>
      ) : (
        <button style={{
          all: 'unset', cursor: 'pointer', display: 'flex', alignItems: 'center', gap: 8,
          padding: '10px 12px', width: '100%', boxSizing: 'border-box',
          border: `1px dashed ${CM.borderStrong}`, borderRadius: 4,
          fontFamily: edUiFont, fontSize: 12.5, color: CM.ink3,
        }}>+ New category</button>
      )}
    </div>
  );
}

// ── grouped organize list ───────────────────────────────────────────
function CMOrganizeList({ groups, selected = new Set(), renameCatId, headerMenuId, liftFeedId, dropLine, newCatActive }) {
  return (
    <div>
      {groups.map(cat => (
        <div key={cat.id} style={{ marginBottom: 22 }}>
          <CMCatHeader cat={cat} organize renaming={renameCatId === cat.id} menuOpen={headerMenuId === cat.id} />
          {cat.feeds.length === 0 ? (
            <div style={{
              padding: '14px 8px', fontFamily: edSerifFont, fontStyle: 'italic',
              fontSize: 13.5, color: CM.ink3,
            }}>No feeds here yet — drag one in.</div>
          ) : cat.feeds.map((f, i, arr) => (
            <React.Fragment key={f.id}>
              {dropLine && dropLine.catId === cat.id && dropLine.index === i ? (
                <div style={{ height: 2, background: CM.accent, margin: '2px 0', borderRadius: 2 }} />
              ) : null}
              <CMOrganizeRow f={f} selected={selected.has(f.id)}
                lifted={liftFeedId === f.id} last={i === arr.length - 1} />
            </React.Fragment>
          ))}
        </div>
      ))}
      <CMNewCategoryRow active={newCatActive} />
    </div>
  );
}

// ── selection bar (bulk-move) ───────────────────────────────────────
function CMSelectionBar({ count, moveMenu }) {
  return (
    <div style={{
      position: 'absolute', left: '50%', bottom: 28, transform: 'translateX(-50%)',
      display: 'flex', alignItems: 'center', gap: 14, padding: '10px 12px 10px 18px',
      background: CM.panel, border: `1px solid ${CM.borderStrong}`, borderRadius: 6,
      boxShadow: '0 8px 24px rgba(0,0,0,.14)', zIndex: 60, fontFamily: edUiFont,
    }}>
      <span style={{ fontSize: 12.5, color: CM.ink2, fontVariantNumeric: 'tabular-nums' }}>{count} selected</span>
      <div style={{ width: 1, height: 20, background: CM.border }} />
      <div style={{ position: 'relative' }}>
        <button style={cmBtn('solid')}>Move to… ▾</button>
        {moveMenu}
      </div>
      <button style={cmBtn('ghost')}>Cancel</button>
    </div>
  );
}

// ── move-to popover ─────────────────────────────────────────────────
function CMMoveMenu({ groups, currentId, anchor = 'bottom' }) {
  const pos = anchor === 'up'
    ? { bottom: 'calc(100% + 8px)', left: 0 }
    : { top: 'calc(100% + 6px)', right: 0 };
  return (
    <div style={{
      position: 'absolute', ...pos, zIndex: 70,
      background: CM.panel, border: `1px solid ${CM.borderStrong}`, borderRadius: 4,
      boxShadow: '0 8px 24px rgba(0,0,0,.12)', minWidth: 190, padding: 4,
    }}>
      <div style={{ ...cmHeaderLabel, padding: '6px 10px 4px' }}>Move to</div>
      {groups.map(cat => {
        const active = cat.id === currentId;
        return (
          <div key={cat.id} style={{
            display: 'flex', alignItems: 'center', gap: 8, padding: '7px 10px', borderRadius: 3,
            background: active ? CM.accentSoft : 'transparent', cursor: 'pointer',
            fontFamily: edUiFont, fontSize: 13, color: active ? CM.accent : CM.ink,
          }}>
            <span style={{ width: 6, height: 6, borderRadius: '50%',
              background: active ? CM.accent : 'transparent', border: active ? 'none' : `1px solid ${CM.borderStrong}`, flex: '0 0 auto' }} />
            <span style={{ flex: 1 }}>{cat.name}</span>
            {cat.locked ? <span style={{ fontSize: 10, color: CM.ink3, fontStyle: 'italic', fontFamily: edSerifFont }}>default</span> : null}
          </div>
        );
      })}
      <div style={{ height: 1, background: CM.border, margin: '4px 0' }} />
      <div style={{ ...cmMenuItem(), color: CM.ink2 }}>+ New category…</div>
    </div>
  );
}

// ── delete → reassign modal ─────────────────────────────────────────
function CMDeleteModal({ cat, groups }) {
  const targets = groups.filter(g => g.id !== cat.id);
  return (
    <div style={{
      position: 'absolute', inset: 0, zIndex: 80,
      background: 'rgba(20,25,40,.32)', backdropFilter: 'blur(2px)',
      display: 'flex', alignItems: 'center', justifyContent: 'center',
    }}>
      <div style={{
        width: 460, background: CM.bg, border: `1px solid ${CM.borderStrong}`,
        boxShadow: '0 24px 60px rgba(0,0,0,.18)', padding: '32px 32px 28px',
        fontFamily: edUiFont, color: CM.ink,
      }}>
        <div style={{ fontFamily: 'ui-monospace, monospace', fontSize: 10.5, letterSpacing: '.14em',
          textTransform: 'uppercase', color: CM.danger, marginBottom: 14 }}>Delete category</div>
        <div style={{ fontFamily: edSerifFont, fontSize: 24, fontWeight: 500, letterSpacing: '-.02em', lineHeight: 1.15, marginBottom: 10 }}>
          Delete “{cat.name}”?
        </div>
        <div style={{ fontFamily: edSerifFont, fontStyle: 'italic', fontSize: 14.5, color: CM.ink2, lineHeight: 1.5, marginBottom: 20, textWrap: 'pretty' }}>
          The category is removed, but its {cat.feeds.length} feeds are kept — choose where they go. Nothing is unsubscribed.
        </div>
        <div style={{ padding: '14px', marginBottom: 22, background: CM.panel, border: `1px solid ${CM.border}`, borderRadius: 4 }}>
          <div style={{ ...cmHeaderLabel, marginBottom: 10 }}>Move its {cat.feeds.length} feeds to</div>
          <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6 }}>
            {targets.map((g, i) => {
              const active = g.locked; // default target = Uncategorized
              return (
                <span key={g.id} style={{
                  padding: '6px 12px', borderRadius: 4, fontSize: 12.5,
                  border: `1px solid ${active ? CM.ink : CM.border}`,
                  background: active ? CM.ink : CM.panel,
                  color: active ? CM.panel : CM.ink2, cursor: 'pointer',
                }}>{g.name}</span>
              );
            })}
          </div>
        </div>
        <div style={{ display: 'flex', gap: 8, justifyContent: 'flex-end' }}>
          <button style={cmBtn('ghost')}>Cancel</button>
          <button style={cmBtn('danger')}>Delete & move feeds</button>
        </div>
      </div>
    </div>
  );
}

// ════════════════════════════════════════════════════════════════════
// SECTION 1 · three directions
// ════════════════════════════════════════════════════════════════════

// A · Organize mode ON — the distinctive moment for this direction.
function CMDirA() {
  const groups = cmGroups(FEEDS);
  return (
    <CMShell>
      <CMContent>
        <CMPageHead right={<button style={cmBtn('solid')}>Done</button>} />
        <div style={{
          display: 'flex', alignItems: 'center', gap: 8, padding: '9px 14px', marginBottom: 20,
          background: CM.accentSoft, borderRadius: 4, fontSize: 12.5, color: CM.accent,
        }}>
          <span style={{ fontFamily: 'ui-monospace, monospace', fontSize: 9.5, letterSpacing: '.14em', textTransform: 'uppercase',
            border: `1px solid ${CM.accent}`, borderRadius: 2, padding: '1px 5px' }}>Organize</span>
          <span>Drag to reorder or re-file · rename or delete a category from its ⋯ · select feeds to bulk-move.</span>
        </div>
        <CMOrganizeList groups={groups} />
      </CMContent>
    </CMShell>
  );
}

// B · dedicated two-pane manager
function CMDirB() {
  const groups = cmGroups(FEEDS);
  const selCat = groups[2]; // Reading selected
  return (
    <CMShell url="feed.app/subscriptions/manage">
      <div style={{ flex: 1, height: '100%', overflow: 'hidden', background: CM.bg, fontFamily: edUiFont, color: CM.ink, display: 'flex', flexDirection: 'column' }}>
        <div style={{ padding: '28px 40px 18px', borderBottom: `1px solid ${CM.border}` }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 10, fontSize: 12.5, color: CM.ink3 }}>
            <span style={{ color: CM.accent, cursor: 'pointer' }}>‹ Subscriptions</span>
          </div>
          <h1 style={{ fontFamily: edSerifFont, fontSize: 26, fontWeight: 500, letterSpacing: '-.02em', margin: '10px 0 0' }}>Manage categories</h1>
        </div>
        <div style={{ flex: 1, display: 'flex', minHeight: 0 }}>
          {/* category rail */}
          <div style={{ width: 260, flex: '0 0 260px', borderRight: `1px solid ${CM.border}`, padding: '18px 16px', overflow: 'auto' }}>
            <div style={{ ...cmHeaderLabel, marginBottom: 10, padding: '0 8px' }}>Categories</div>
            {groups.map(cat => {
              const active = cat.id === selCat.id;
              return (
                <div key={cat.id} style={{
                  display: 'flex', alignItems: 'center', gap: 8, padding: '9px 10px', borderRadius: 4,
                  background: active ? CM.accentSoft : 'transparent', marginBottom: 2, cursor: 'pointer',
                }}>
                  {!cat.locked ? <CMHandle /> : <span style={{ width: 6 }} />}
                  <span style={{ flex: 1, fontFamily: edSerifFont, fontSize: 14.5, fontWeight: 500, color: active ? CM.accent : CM.ink }}>{cat.name}</span>
                  <span style={{ fontSize: 11, color: CM.ink3, fontVariantNumeric: 'tabular-nums' }}>{cat.feeds.length}</span>
                </div>
              );
            })}
            <div style={{ marginTop: 10, padding: '0 2px' }}><CMNewCategoryRow active={false} /></div>
          </div>
          {/* feeds in selected category */}
          <div style={{ flex: 1, padding: '18px 40px 40px', overflow: 'auto' }}>
            <div style={{ display: 'flex', alignItems: 'baseline', gap: 10, marginBottom: 4 }}>
              <h2 style={{ fontFamily: edSerifFont, fontSize: 20, fontWeight: 500, letterSpacing: '-.015em', margin: 0 }}>{selCat.name}</h2>
              <span style={{ fontSize: 12, color: CM.ink3 }}>{selCat.feeds.length} feeds</span>
            </div>
            <div style={{ fontSize: 12, color: CM.ink3, marginBottom: 16, fontStyle: 'italic', fontFamily: edSerifFont }}>
              Drag a feed onto a category on the left to move it.
            </div>
            <div>
              {selCat.feeds.map((f, i, arr) => (
                <div key={f.id} style={{ display: 'flex', alignItems: 'center', gap: 12, padding: '11px 8px', borderBottom: i === arr.length - 1 ? 'none' : `1px solid ${CM.border}` }}>
                  <CMHandle />
                  <CMAvatar f={f} size={32} />
                  <div style={{ flex: 1, minWidth: 0 }}>
                    <div style={{ fontFamily: edSerifFont, fontSize: 15, fontWeight: 500 }}>{f.name}</div>
                    <div style={{ fontSize: 11, color: CM.ink3, marginTop: 2 }}>{f.url}</div>
                  </div>
                  <button style={cmBtn('ghost')}>Move to… ▾</button>
                </div>
              ))}
            </div>
          </div>
        </div>
      </div>
    </CMShell>
  );
}

// C · always-inline (grouped list, row ⋯ → Move submenu open)
function CMDirC() {
  const groups = cmGroups(FEEDS);
  return (
    <CMShell>
      <CMContent>
        <CMPageHead right={<button style={cmBtn('accent')}>+ Add feed</button>} />
        <CMSearch />
        {groups.map(cat => (
          <div key={cat.id} style={{ marginBottom: 22 }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 10, borderBottom: `1px solid ${CM.border}`, padding: '0 0 8px', marginBottom: 6 }}>
              <span style={cmHeaderLabel}>{cat.name}</span>
              <span style={{ fontSize: 10.5, color: CM.ink3 }}>{cat.feeds.length}</span>
              <span style={{ flex: 1 }} />
              {!cat.locked ? <span style={{ color: CM.ink3, fontSize: 15, cursor: 'pointer' }}>⋯</span> : null}
            </div>
            {cat.feeds.length === 0 ? (
              <div style={{ padding: '10px 0', fontFamily: edSerifFont, fontStyle: 'italic', fontSize: 13.5, color: CM.ink3 }}>Nothing here yet.</div>
            ) : cat.feeds.map((f, i, arr) => (
              <CMBrowseRow key={f.id} f={f} last={i === arr.length - 1}
                menu={f.id === 'theloop' ? (
                  <div style={{ position: 'absolute', right: 0, top: 28, zIndex: 50,
                    background: CM.panel, border: `1px solid ${CM.borderStrong}`, borderRadius: 4,
                    boxShadow: '0 8px 24px rgba(0,0,0,.10)', minWidth: 160, padding: 4 }}>
                    <div style={{ ...cmMenuItem(), display: 'flex', justifyContent: 'space-between', alignItems: 'center', background: CM.accentSoft, color: CM.accent }}>
                      <span>Move to</span><span>▸</span>
                    </div>
                    <div style={cmMenuItem()}>Rename…</div>
                    <div style={{ ...cmMenuItem(), color: CM.danger }}>Unsubscribe</div>
                    {/* submenu */}
                    <div style={{ position: 'absolute', left: '100%', top: 4, marginLeft: 4,
                      background: CM.panel, border: `1px solid ${CM.borderStrong}`, borderRadius: 4,
                      boxShadow: '0 8px 24px rgba(0,0,0,.10)', minWidth: 150, padding: 4 }}>
                      {groups.map(g => {
                        const cur = g.name === f.folder;
                        return (
                          <div key={g.id} style={{ ...cmMenuItem(), display: 'flex', alignItems: 'center', gap: 8,
                            color: cur ? CM.accent : CM.ink, background: cur ? CM.accentSoft : 'transparent' }}>
                            <span style={{ width: 6, height: 6, borderRadius: '50%', background: cur ? CM.accent : 'transparent', border: cur ? 'none' : `1px solid ${CM.borderStrong}` }} />
                            {g.name}
                          </div>
                        );
                      })}
                    </div>
                  </div>
                ) : null} />
            ))}
          </div>
        ))}
        <CMNewCategoryRow active={false} />
      </CMContent>
    </CMShell>
  );
}

// ════════════════════════════════════════════════════════════════════
// SECTION 2 · operations (Direction A vehicle)
// ════════════════════════════════════════════════════════════════════

function CMOpHead() {
  return (
    <React.Fragment>
      <CMPageHead right={<button style={cmBtn('solid')}>Done</button>} />
    </React.Fragment>
  );
}

// create + rename
function CMOpCreateRename() {
  const groups = cmGroups(FEEDS);
  return (
    <CMShell>
      <CMContent>
        <CMOpHead />
        <CMOrganizeList groups={groups} renameCatId="tech" newCatActive />
      </CMContent>
    </CMShell>
  );
}

// reorder — dragging a feed within/into a category
function CMOpReorder() {
  const groups = cmGroups(FEEDS);
  return (
    <CMShell>
      <CMContent>
        <CMOpHead />
        <CMOrganizeList groups={groups} liftFeedId="theloop" dropLine={{ catId: 'craft', index: 1 }} />
      </CMContent>
    </CMShell>
  );
}

// bulk-move — 3 selected + selection bar + move popover
function CMOpBulk() {
  const groups = cmGroups(FEEDS);
  const sel = new Set(['coldtake', 'atlas', 'frequencies']);
  return (
    <CMShell>
      <CMContent>
        <CMOpHead />
        <CMOrganizeList groups={groups} selected={sel} />
      </CMContent>
      <CMSelectionBar count={sel.size} moveMenu={<CMMoveMenu groups={groups} currentId="reading" anchor="up" />} />
    </CMShell>
  );
}

// delete → reassign modal
function CMOpDelete() {
  const groups = cmGroups(FEEDS);
  const target = groups.find(g => g.id === 'reading');
  return (
    <CMShell>
      <CMContent>
        <CMOpHead />
        <CMOrganizeList groups={groups} headerMenuId="reading" />
      </CMContent>
      <CMDeleteModal cat={target} groups={groups} />
    </CMShell>
  );
}

Object.assign(window, {
  CMDirA, CMDirB, CMDirC,
  CMOpCreateRename, CMOpReorder, CMOpBulk, CMOpDelete,
  cmGroups, CM_PALETTE: CM,
});

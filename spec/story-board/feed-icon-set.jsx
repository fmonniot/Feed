// Feed — Icon Set · "Feed." direction
// Full icon set for web + Android in all contexts.
// Canonical proportions: dot = 15% of font-size, gap = 0.07em, baseline-aligned.

const SERIF = "'Source Serif 4', Georgia, 'Times New Roman', serif";
const SANS  = "'IBM Plex Sans', system-ui, sans-serif";
const MONO  = "'IBM Plex Mono', ui-monospace, monospace";

const T = {
  bg: '#f3f5f7', panel: '#f9fafb',
  border: 'rgba(20,25,40,0.08)', borderStrong: 'rgba(20,25,40,0.16)',
  ink: '#1a1f28', ink2: '#4a5160', ink3: '#7c8290',
  accent: '#566073', onAccent: '#f9fafb',
};
const WALL = '#15171e';

// ── Core atoms ────────────────────────────────────────────────────────

function FeedWordmark({ fontSize = 42, c = T.ink, a = T.accent }) {
  const dot = Math.round(fontSize * 0.15);
  return (
    <span style={{ display: 'inline-flex', alignItems: 'flex-end', gap: fontSize * 0.07 }}>
      <span style={{ fontFamily: SERIF, fontWeight: 500, fontSize,
        letterSpacing: '-0.02em', color: c, lineHeight: 1 }}>Feed</span>
      <span style={{ width: dot, height: dot, borderRadius: '50%',
        background: a, flex: '0 0 auto' }} />
    </span>
  );
}

// Standalone "F." — for icon use. h = cap height.
function FMark({ h, c = T.ink, a = T.accent }) {
  const dot = Math.round(h * 0.22);
  return (
    <span style={{ display: 'inline-flex', alignItems: 'flex-end', gap: h * 0.09 }}>
      <span style={{ fontFamily: SERIF, fontWeight: 500, fontSize: h,
        color: c, lineHeight: 1 }}>F</span>
      <span style={{ width: dot, height: dot, borderRadius: '50%',
        background: a, flex: '0 0 auto' }} />
    </span>
  );
}

// Square app icon tile. markH = 40% of size, sitting in 60% safe zone.
function FIcon({ size, bg = T.bg, c = T.ink, a = T.accent, r }) {
  const radius = r ?? size * 0.22;
  return (
    <div style={{ width: size, height: size, borderRadius: radius, background: bg,
      display: 'flex', alignItems: 'center', justifyContent: 'center',
      flex: '0 0 auto', overflow: 'hidden' }}>
      <FMark h={size * 0.40} c={c} a={a} />
    </div>
  );
}

// Checkerboard (transparency indicator)
function Checker({ size, children }) {
  return (
    <div style={{ width: size, height: size, position: 'relative',
      backgroundImage: 'repeating-conic-gradient(#ddd 0% 25%, #fff 0% 50%)',
      backgroundSize: '8px 8px', display: 'flex', alignItems: 'center',
      justifyContent: 'center' }}>
      {children}
    </div>
  );
}

function Lbl({ children, c = T.ink3 }) {
  return (
    <span style={{ fontFamily: MONO, fontSize: 9, letterSpacing: '0.05em', color: c,
      lineHeight: 1.45, textAlign: 'center', whiteSpace: 'pre-line',
      display: 'block' }}>{children}</span>
  );
}

function Stack({ label, children, gap = 7, lc }) {
  return (
    <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap }}>
      {children}
      <Lbl c={lc}>{label}</Lbl>
    </div>
  );
}

function Pane({ children, bg = T.bg, pad = 28 }) {
  return (
    <div style={{ background: bg, padding: pad, boxSizing: 'border-box',
      width: '100%', height: '100%', display: 'flex',
      flexDirection: 'column', gap: 20 }}>
      {children}
    </div>
  );
}

function SectionLbl({ children }) {
  return <Lbl c={T.ink2}>{children}</Lbl>;
}

function Row({ children, gap = 20, align = 'flex-end' }) {
  return <div style={{ display: 'flex', alignItems: align, gap }}>{children}</div>;
}

// ── 1. The Mark ───────────────────────────────────────────────────────
function MarkBody() {
  const sizes = [
    { px: 17, desc: '17px\nsidebar' },
    { px: 28, desc: '28px\nheadings' },
    { px: 48, desc: '48px\nbrand' },
    { px: 80, desc: '80px\ndisplay' },
  ];
  return (
    <Pane pad={36}>
      <Row gap={52} align="flex-end">
        {sizes.map(({ px, desc }) => (
          <div key={px} style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
            <div style={{ position: 'relative', paddingBottom: 7 }}>
              <FeedWordmark fontSize={px} />
              <div style={{ position: 'absolute', bottom: 0, left: 0, right: 0,
                height: 1, background: T.borderStrong }} />
            </div>
            <Lbl>{desc}</Lbl>
          </div>
        ))}
      </Row>
      <div style={{ borderTop: `1px solid ${T.border}`, paddingTop: 14,
        fontFamily: MONO, fontSize: 9, color: T.ink3, lineHeight: 1.65 }}>
        {'Dot · 15% of font-size · baseline-aligned · gap 0.07em · fill #566073\n'}
        {'Typeface · Source Serif 4 · weight 500 · tracking −0.02em'}
      </div>
    </Pane>
  );
}

// ── 2. Web Favicons ───────────────────────────────────────────────────
function FaviconsBody() {
  const sizes = [16, 32, 48];
  return (
    <Pane>
      <SectionLbl>Favicon · solid background (favicon.ico / .png)</SectionLbl>
      <Row gap={18}>
        {sizes.map(s => (
          <Stack key={s} label={`${s}×${s}px`}>
            <div style={{ width: s, height: s, border: `1px solid ${T.borderStrong}`,
              background: T.bg, display: 'flex', alignItems: 'center',
              justifyContent: 'center', flex: '0 0 auto' }}>
              <FMark h={s * 0.60} />
            </div>
          </Stack>
        ))}
        {/* 4× zoom of 16px */}
        <Stack label={'16px · 4× zoom'}>
          <div style={{ width: 64, height: 64, border: `1px solid ${T.border}`,
            background: T.bg, display: 'flex', alignItems: 'center',
            justifyContent: 'center', overflow: 'hidden' }}>
            <div style={{ transform: 'scale(4)', transformOrigin: 'center' }}>
              <FMark h={9} />
            </div>
          </div>
        </Stack>
      </Row>
      <div style={{ borderTop: `1px solid ${T.border}`, paddingTop: 18 }}>
        <SectionLbl>Transparent background (SVG favicon)</SectionLbl>
        <Row gap={18} style={{ marginTop: 12 }}>
          {sizes.map(s => (
            <Stack key={s} label={`${s}×${s}px`}>
              <Checker size={s}>
                <FMark h={s * 0.60} />
              </Checker>
            </Stack>
          ))}
        </Row>
      </div>
    </Pane>
  );
}

// ── 3. Web App Icons ──────────────────────────────────────────────────
function WebAppIconsBody() {
  const icons = [
    { disp: 90,  orig: 180, label: '180×180\nApple Touch Icon' },
    { disp: 96,  orig: 192, label: '192×192\nPWA install' },
    { disp: 128, orig: 512, label: '512×512\nPWA / splash' },
  ];
  return (
    <Pane>
      <SectionLbl>Web App Icons · shown at 50% of true pixel size</SectionLbl>
      <Row gap={28} align="flex-end">
        {icons.map(ic => (
          <Stack key={ic.orig} label={ic.label}>
            <FIcon size={ic.disp} r={ic.disp * 0.22} />
          </Stack>
        ))}
        {/* Dark bg variant for 512 */}
        <Stack label={'512×512\nDark variant'}>
          <FIcon size={128} r={128 * 0.22} bg={T.ink} c={T.onAccent} a={'#a0aabb'} />
        </Stack>
      </Row>
    </Pane>
  );
}

// ── 4. OG / Social ────────────────────────────────────────────────────
function SocialBody() {
  return (
    <Pane pad={24}>
      <SectionLbl>Open Graph · Social Preview · 1200×630px (shown at 50%)</SectionLbl>
      <div style={{ width: 600, height: 315, background: T.bg,
        border: `1px solid ${T.border}`, display: 'flex',
        alignItems: 'center', justifyContent: 'center', flex: '0 0 auto' }}>
        <FeedWordmark fontSize={72} />
      </div>
    </Pane>
  );
}

// ── 5. Android Adaptive Icon ──────────────────────────────────────────
function AdaptiveBody() {
  const total = 108, safe = 72, offset = (total - safe) / 2;
  return (
    <Pane>
      <SectionLbl>Android Adaptive Icon · 108dp canvas · 72dp safe zone (dashed)</SectionLbl>
      <Row gap={28} align="flex-start">
        {/* Background layer */}
        <Stack label={'Background layer\n108×108dp · #f3f5f7'}>
          <div style={{ width: total, height: total, background: T.bg,
            border: `1px solid ${T.borderStrong}` }} />
        </Stack>
        {/* Foreground layer */}
        <Stack label={'Foreground layer\n108×108dp · F. in safe zone'}>
          <div style={{ width: total, height: total, position: 'relative',
            backgroundImage: 'repeating-conic-gradient(#ddd 0% 25%, #fff 0% 50%)',
            backgroundSize: '8px 8px', display: 'flex',
            alignItems: 'center', justifyContent: 'center' }}>
            <FMark h={total * 0.38} />
            <div style={{ position: 'absolute', top: offset, left: offset,
              width: safe, height: safe, border: `1px dashed ${T.accent}`,
              opacity: 0.55, boxSizing: 'border-box', pointerEvents: 'none' }} />
          </div>
        </Stack>
        {/* Combined light */}
        <Stack label={'Combined · Light\n(default)'}>
          <FIcon size={total} r={total * 0.22} />
        </Stack>
        {/* Combined dark */}
        <Stack label={'Combined · Dark\n(optional)'}>
          <div style={{ background: WALL, padding: 12, borderRadius: 10 }}>
            <FIcon size={total} r={total * 0.22}
              bg={T.ink} c={T.onAccent} a={'#a0aabb'} />
          </div>
        </Stack>
      </Row>
    </Pane>
  );
}

// ── 6. Launcher Densities ─────────────────────────────────────────────
function DensitiesBody() {
  const densities = [
    { key: 'mdpi',    dp: 48  },
    { key: 'hdpi',    dp: 72  },
    { key: 'xhdpi',   dp: 96  },
    { key: 'xxhdpi',  dp: 144 },
    { key: 'xxxhdpi', dp: 192 },
  ];
  const disp = dp => dp > 72 ? Math.round(dp / 2) : dp;
  return (
    <Pane>
      <SectionLbl>Launcher Densities · xxhdpi+ shown at 50%</SectionLbl>
      <Row gap={18} align="flex-end">
        {densities.map(d => (
          <Stack key={d.key} label={`${d.key}\n${d.dp}dp`}>
            <FIcon size={disp(d.dp)} r={disp(d.dp) * 0.22} />
          </Stack>
        ))}
      </Row>
      <div style={{ borderTop: `1px solid ${T.border}`, paddingTop: 18 }}>
        <SectionLbl>Dark background variant</SectionLbl>
        <Row gap={18} align="flex-end" style={{ marginTop: 12 }}>
          {densities.map(d => (
            <Stack key={d.key} label={`${d.key}\n${d.dp}dp`}>
              <FIcon size={disp(d.dp)} r={disp(d.dp) * 0.22}
                bg={T.ink} c={T.onAccent} a={'#a0aabb'} />
            </Stack>
          ))}
        </Row>
      </div>
    </Pane>
  );
}

// ── 7. Notification Icon ──────────────────────────────────────────────
function NotificationBody() {
  const sizes = [24, 36, 48];
  return (
    <Pane bg={WALL} pad={28}>
      <SectionLbl c="rgba(255,255,255,0.4)">
        Android Notification Icon · monochrome white · transparent bg · 24dp base
      </SectionLbl>
      <Row gap={28} align="flex-end">
        {sizes.map(s => (
          <Stack key={s} label={`${s}dp`} lc="rgba(255,255,255,0.35)">
            <Checker size={s}>
              <span style={{ fontFamily: SERIF, fontWeight: 500,
                fontSize: s * 0.72, color: '#fff', lineHeight: 1 }}>F</span>
            </Checker>
          </Stack>
        ))}
        {/* Notification drawer mockup */}
        <div style={{ marginLeft: 16, display: 'flex', flexDirection: 'column', gap: 8 }}>
          <div style={{ background: '#222530', borderRadius: 6, padding: '8px 14px',
            display: 'flex', alignItems: 'center', gap: 10, minWidth: 220 }}>
            <span style={{ fontFamily: SERIF, fontWeight: 500,
              fontSize: 13, color: '#fff', lineHeight: 1 }}>F</span>
            <div style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
              <span style={{ fontFamily: SANS, fontSize: 11.5,
                color: 'rgba(255,255,255,0.9)', fontWeight: 500 }}>Feed</span>
              <span style={{ fontFamily: SANS, fontSize: 10.5,
                color: 'rgba(255,255,255,0.55)' }}>7 unread articles</span>
            </div>
          </div>
          <Lbl c="rgba(255,255,255,0.3)">Notification drawer</Lbl>
        </div>
      </Row>
    </Pane>
  );
}

// ── 8. Browser Tab ────────────────────────────────────────────────────
function BrowserTabBody() {
  return (
    <div style={{ width: '100%', height: '100%', background: '#dce0e8',
      display: 'flex', flexDirection: 'column', boxSizing: 'border-box' }}>
      {/* Tab strip */}
      <div style={{ background: '#cdd1da', padding: '7px 7px 0',
        display: 'flex', gap: 2, alignItems: 'flex-end' }}>
        {/* Active tab */}
        <div style={{ background: T.panel, borderRadius: '6px 6px 0 0',
          padding: '7px 14px 7px 10px', display: 'flex', alignItems: 'center',
          gap: 7, minWidth: 190, flex: '0 0 auto' }}>
          <div style={{ width: 14, height: 14, display: 'flex',
            alignItems: 'center', justifyContent: 'center', flex: '0 0 auto' }}>
            <FMark h={10} />
          </div>
          <span style={{ fontFamily: SANS, fontSize: 11.5, color: T.ink,
            flex: 1 }}>Feed — Unread</span>
          <span style={{ fontFamily: SANS, fontSize: 12, color: T.ink3 }}>×</span>
        </div>
        {/* Inactive tab */}
        {['GitHub', 'Notion'].map(name => (
          <div key={name} style={{ background: '#bcc1cc', borderRadius: '6px 6px 0 0',
            padding: '7px 14px 7px 10px', display: 'flex', alignItems: 'center',
            gap: 7, opacity: 0.75 }}>
            <div style={{ width: 12, height: 12, background: '#999', borderRadius: 2 }} />
            <span style={{ fontFamily: SANS, fontSize: 11.5, color: '#556' }}>{name}</span>
          </div>
        ))}
      </div>
      {/* Address bar */}
      <div style={{ background: T.panel, borderBottom: `1px solid ${T.border}`,
        padding: '7px 12px', display: 'flex', alignItems: 'center', gap: 8 }}>
        <div style={{ flex: 1, background: T.bg, border: `1px solid ${T.border}`,
          borderRadius: 20, padding: '5px 14px', display: 'flex',
          alignItems: 'center', gap: 8 }}>
          <div style={{ width: 13, height: 13, display: 'flex',
            alignItems: 'center', justifyContent: 'center', flex: '0 0 auto' }}>
            <FMark h={9} />
          </div>
          <span style={{ fontFamily: SANS, fontSize: 12, color: T.ink3 }}>
            feed.example.com</span>
        </div>
      </div>
      {/* Bookmarks bar */}
      <div style={{ background: T.panel, borderBottom: `1px solid ${T.border}`,
        padding: '4px 14px', display: 'flex', alignItems: 'center', gap: 12 }}>
        {['Feed', 'Work', 'Reading'].map((bm, i) => (
          <div key={bm} style={{ display: 'flex', alignItems: 'center', gap: 5 }}>
            {i === 0 && (
              <div style={{ width: 12, height: 12, display: 'flex',
                alignItems: 'center', justifyContent: 'center' }}>
                <FMark h={8} />
              </div>
            )}
            {i > 0 && <div style={{ width: 12, height: 12, background: '#bbb', borderRadius: 2 }} />}
            <span style={{ fontFamily: SANS, fontSize: 11, color: i === 0 ? T.ink : T.ink3 }}>{bm}</span>
          </div>
        ))}
      </div>
      {/* Page body (faint) */}
      <div style={{ flex: 1, background: T.bg, display: 'flex',
        alignItems: 'center', justifyContent: 'center' }}>
        <span style={{ fontFamily: SANS, fontSize: 10, color: T.border }}>app content</span>
      </div>
    </div>
  );
}

// ── 9. Android Home Screen ────────────────────────────────────────────
function HomeScreenBody() {
  const SZ = 52, R = SZ * 0.22;
  const placeholder = (hue, label) => (
    <div key={label} style={{ display: 'flex', flexDirection: 'column',
      alignItems: 'center', gap: 5 }}>
      <div style={{ width: SZ, height: SZ, borderRadius: R,
        background: `oklch(0.55 0.09 ${hue})`, opacity: 0.6 }} />
      <span style={{ fontFamily: SANS, fontSize: 10,
        color: 'rgba(255,255,255,0.45)' }}>{label}</span>
    </div>
  );
  const rows = [
    [[210,'Mail'],[88,'Maps'],[285,'Music'],[38,'Camera']],
    [[152,'Photos'], null, [22,'Clock'],[0,'Notes']],
    [[180,'Files'],[320,'Wallet'],[60,'News'],[245,'Settings']],
  ];
  return (
    <div style={{ background: WALL, width: '100%', height: '100%',
      display: 'flex', flexDirection: 'column', alignItems: 'center',
      justifyContent: 'center', gap: 16, boxSizing: 'border-box', padding: 24 }}>
      {rows.map((row, ri) => (
        <div key={ri} style={{ display: 'flex', gap: 16 }}>
          {row.map((item, ci) => item === null
            ? (
              <div key="feed" style={{ display: 'flex', flexDirection: 'column',
                alignItems: 'center', gap: 5 }}>
                <FIcon size={SZ} r={R} />
                <span style={{ fontFamily: SANS, fontSize: 10,
                  color: '#fff', fontWeight: 500 }}>Feed</span>
              </div>
            )
            : placeholder(item[0], item[1])
          )}
        </div>
      ))}
    </div>
  );
}

// ── Main ──────────────────────────────────────────────────────────────
function FeedIconSet() {
  return (
    <DesignCanvas>
      <DCSection id="mark" title="Feed. — The Mark"
        subtitle="Canonical proportions · Source Serif 4 500 · −0.02em · dot = 15% of font-size · baseline-aligned">
        <DCArtboard id="wordmark" label="The Wordmark" width={780} height={210}>
          <MarkBody />
        </DCArtboard>
      </DCSection>

      <DCSection id="web" title="Web"
        subtitle="favicon · apple-touch-icon · PWA · Open Graph">
        <DCArtboard id="favicons"  label="Favicons"      width={500} height={280}>
          <FaviconsBody />
        </DCArtboard>
        <DCArtboard id="app-icons" label="App Icons"     width={520} height={270}>
          <WebAppIconsBody />
        </DCArtboard>
        <DCArtboard id="social"    label="OG / Social"   width={670} height={380}>
          <SocialBody />
        </DCArtboard>
      </DCSection>

      <DCSection id="android" title="Android"
        subtitle="Adaptive icon · launcher densities · notification icon">
        <DCArtboard id="adaptive"     label="Adaptive Icon"       width={720} height={290}>
          <AdaptiveBody />
        </DCArtboard>
        <DCArtboard id="densities"    label="Launcher Densities"  width={700} height={340}>
          <DensitiesBody />
        </DCArtboard>
        <DCArtboard id="notification" label="Notification Icon"   width={560} height={240}>
          <NotificationBody />
        </DCArtboard>
      </DCSection>

      <DCSection id="context" title="In Context"
        subtitle="Browser tab · Android home screen">
        <DCArtboard id="browser"    label="Browser Tab"        width={520} height={280}>
          <BrowserTabBody />
        </DCArtboard>
        <DCArtboard id="homescreen" label="Android Home Screen" width={340} height={380}>
          <HomeScreenBody />
        </DCArtboard>
      </DCSection>
    </DesignCanvas>
  );
}

const __root = ReactDOM.createRoot(document.getElementById('root'));
__root.render(<FeedIconSet />);

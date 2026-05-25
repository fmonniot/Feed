// Login page — Editorial / Paper palette.
// Per FEATURES.md AUTH-1/1a/1b/2/4: form submits on Enter (web) or IME Go (android);
// invalid-creds shows an inline error and keeps focus on password; logout returns here.
// Two layouts:
//   <LoginDesktop authError onSignIn />   — centred single-column form
//   <LoginMobile  authError onSignIn />   — stacked, full-width form for Android

const LoginUi = (() => {
  const uiFont    = '"IBM Plex Sans", ui-sans-serif, system-ui, sans-serif';
  const serifFont = '"Source Serif 4", "Source Serif Pro", "Iowan Old Style", Georgia, serif';

  // ── Shared atoms ─────────────────────────────────────────────────
  function Wordmark({ T, size = 22 }) {
    return (
      <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
        <div style={{
          width: size * 0.95, height: size * 0.95,
          border: `1.5px solid ${T.ink}`,
          display: 'grid', placeItems: 'center',
          fontFamily: serifFont, fontStyle: 'italic',
          fontSize: size * 0.6, color: T.ink, lineHeight: 1,
        }}>F</div>
        <div style={{
          fontFamily: serifFont, fontSize: size, color: T.ink,
          letterSpacing: '-.01em', fontWeight: 500,
        }}>Feed</div>
      </div>
    );
  }

  function Field({ T, label, type = 'text', value, placeholder, trailing, autoFocus, inputMode, enterKeyHint }) {
    return (
      <label style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
        <span style={{
          fontFamily: uiFont, fontSize: 11, letterSpacing: '.14em',
          textTransform: 'uppercase', color: T.ink3,
        }}>{label}</span>
        <div style={{
          display: 'flex', alignItems: 'center',
          borderBottom: `1px solid ${T.borderStrong}`,
          paddingBottom: 8,
        }}>
          <input
            type={type}
            defaultValue={value}
            placeholder={placeholder}
            autoFocus={autoFocus}
            inputMode={inputMode}
            enterKeyHint={enterKeyHint}
            style={{
              flex: 1, border: 'none', outline: 'none',
              background: 'transparent', color: T.ink,
              fontFamily: uiFont, fontSize: 16, padding: 0,
            }}
          />
          {trailing}
        </div>
      </label>
    );
  }

  function PrimaryButton({ T, children, full, onClick, type = 'submit' }) {
    return (
      <button type={type} onClick={onClick} style={{
        width: full ? '100%' : 'auto',
        background: T.ink, color: T.panel,
        border: 'none', padding: '14px 22px',
        fontFamily: uiFont, fontSize: 14, fontWeight: 500,
        letterSpacing: '.02em', cursor: 'pointer',
        display: 'inline-flex', alignItems: 'center', justifyContent: 'center', gap: 10,
      }}>
        {children}
        <span aria-hidden style={{ fontFamily: serifFont, fontSize: 18, lineHeight: 1 }}>→</span>
      </button>
    );
  }

  function GhostButton({ T, children, icon, full, onClick }) {
    return (
      <button type="button" onClick={onClick} style={{
        width: full ? '100%' : 'auto',
        background: 'transparent', color: T.ink,
        border: `1px solid ${T.borderStrong}`, padding: '12px 18px',
        fontFamily: uiFont, fontSize: 14, fontWeight: 500,
        cursor: 'pointer',
        display: 'inline-flex', alignItems: 'center', justifyContent: 'center', gap: 10,
      }}>
        {icon}
        {children}
      </button>
    );
  }

  const GIcon = ({ T }) => (
    <span style={{
      width: 18, height: 18, display: 'grid', placeItems: 'center',
      border: `1px solid ${T.borderStrong}`, fontFamily: serifFont,
      fontSize: 12, fontWeight: 600, color: T.ink2, lineHeight: 1,
    }}>G</span>
  );
  const AtIcon = ({ T }) => (
    <span style={{
      width: 18, height: 18, display: 'grid', placeItems: 'center',
      border: `1px solid ${T.borderStrong}`, fontFamily: serifFont,
      fontSize: 12, fontWeight: 600, color: T.ink2, lineHeight: 1,
    }}>@</span>
  );

  function Divider({ T, label = 'or' }) {
    return (
      <div style={{
        display: 'flex', alignItems: 'center', gap: 12,
        color: T.ink3, fontFamily: uiFont, fontSize: 11,
        letterSpacing: '.18em', textTransform: 'uppercase',
      }}>
        <div style={{ flex: 1, height: 1, background: T.border }} />
        <span>{label}</span>
        <div style={{ flex: 1, height: 1, background: T.border }} />
      </div>
    );
  }

  function AuthError({ T, visible, compact }) {
    if (!visible) return null;
    return (
      <div style={{
        fontFamily: uiFont, fontSize: compact ? 12 : 13,
        color: T.danger || T.accent,
        background: T.accentSoft || 'transparent',
        border: `1px solid ${T.danger || T.accent}`,
        padding: compact ? '8px 12px' : '10px 14px',
        display: 'flex', alignItems: 'center', gap: 8,
      }}>
        <span aria-hidden style={{ fontFamily: serifFont, fontStyle: 'italic' }}>!</span>
        Invalid username or password.
      </div>
    );
  }

  // ── Desktop ──────────────────────────────────────────────────────
  function LoginDesktop({ theme = 'paper', authError = false, onSignIn }) {
    const T = ED_PALETTES[theme] || ED_PALETTES.paper;
    const submit = (e) => { e && e.preventDefault(); onSignIn && onSignIn(); };
    return (
      <div style={{
        width: '100%', height: '100%',
        display: 'flex', alignItems: 'center', justifyContent: 'center',
        background: T.bg, color: T.ink, fontFamily: uiFont,
      }}>
        <form onSubmit={submit} style={{
          width: 420, display: 'flex', flexDirection: 'column', gap: 32,
        }}>
          <Wordmark T={T} />

          <div style={{ display: 'flex', flexDirection: 'column', gap: 26 }}>
            <div>
              <div style={{
                fontFamily: uiFont, fontSize: 11, letterSpacing: '.18em',
                textTransform: 'uppercase', color: T.ink3, marginBottom: 14,
              }}>Sign in</div>
              <h1 style={{
                fontFamily: serifFont, fontWeight: 500,
                fontSize: 38, lineHeight: 1.08, letterSpacing: '-.02em',
                margin: 0, color: T.ink, textWrap: 'balance',
              }}>Welcome back to your reading room.</h1>
              <p style={{
                fontFamily: serifFont, fontStyle: 'italic',
                fontSize: 15, lineHeight: 1.5, color: T.ink2,
                margin: '12px 0 0', textWrap: 'pretty',
              }}>
                Your feeds, quietly waiting. No algorithm, no infinite scroll —
                just the few writers you chose.
              </p>
            </div>

            <div style={{ display: 'flex', flexDirection: 'column', gap: 22 }}>
              <Field T={T} label="Username" type="text" autoFocus={!authError}
                placeholder="admin" value="admin" />
              <Field T={T} label="Password" type="password"
                placeholder="••••••••••"
                value={authError ? '' : 'hunter2hunter2'}
                autoFocus={authError}
                trailing={
                  <button type="button" style={{
                    background: 'transparent', border: 'none', padding: 0,
                    color: T.ink3, fontFamily: uiFont, fontSize: 12,
                    letterSpacing: '.06em', cursor: 'pointer',
                  }}>Show</button>
                }
              />
              <AuthError T={T} visible={authError} />
            </div>

            <div style={{
              display: 'flex', alignItems: 'center', justifyContent: 'space-between',
              fontSize: 13,
            }}>
              <label style={{ display: 'flex', alignItems: 'center', gap: 8, color: T.ink2, cursor: 'pointer' }}>
                <span style={{
                  width: 14, height: 14, border: `1px solid ${T.borderStrong}`,
                  background: T.panel, display: 'inline-block',
                }} />
                Keep me signed in
              </label>
              <a href="#" style={{
                color: T.ink2, textDecoration: 'none',
                borderBottom: `1px solid ${T.border}`, paddingBottom: 1,
              }}>Forgot password?</a>
            </div>

            <PrimaryButton T={T} full>Sign in</PrimaryButton>

            <Divider T={T} />

            <div style={{ display: 'flex', gap: 10 }}>
              <GhostButton T={T} full icon={<GIcon T={T} />}>Continue with Google</GhostButton>
              <GhostButton T={T} full icon={<AtIcon T={T} />}>Magic link</GhostButton>
            </div>

            <div style={{
              display: 'flex', justifyContent: 'space-between',
              color: T.ink3, fontSize: 12, paddingTop: 4,
            }}>
              <span>New here? <a href="#" style={{ color: T.ink, borderBottom: `1px solid ${T.borderStrong}`, textDecoration: 'none', paddingBottom: 1 }}>Create an account</a></span>
              <span>© Feed Press</span>
            </div>
          </div>
        </form>
      </div>
    );
  }

  // ── Mobile (Android) ─────────────────────────────────────────────
  function LoginMobile({ theme = 'paper', topInset = 14, authError = false, onSignIn }) {
    const T = ED_PALETTES[theme] || ED_PALETTES.paper;
    const submit = (e) => { e && e.preventDefault(); onSignIn && onSignIn(); };
    return (
      <div style={{
        width: '100%', height: '100%',
        background: T.panel, color: T.ink, fontFamily: uiFont,
        display: 'flex', flexDirection: 'column',
        paddingTop: topInset, boxSizing: 'border-box',
      }}>
        {/* top bar */}
        <div style={{
          padding: '14px 22px',
          display: 'flex', alignItems: 'center', justifyContent: 'space-between',
        }}>
          <Wordmark T={T} size={18} />
          <a href="#" style={{
            fontSize: 12, color: T.ink2, textDecoration: 'none',
            borderBottom: `1px solid ${T.border}`, paddingBottom: 1,
          }}>Sign up</a>
        </div>

        {/* hero */}
        <div style={{ padding: '24px 22px 8px' }}>
          <div style={{
            fontSize: 10, letterSpacing: '.18em',
            textTransform: 'uppercase', color: T.ink3, marginBottom: 10,
          }}>Sign in</div>
          <h1 style={{
            fontFamily: serifFont, fontWeight: 500,
            fontSize: 30, lineHeight: 1.1, letterSpacing: '-.02em',
            margin: 0, color: T.ink, textWrap: 'balance',
          }}>Welcome back to your reading room.</h1>
          <p style={{
            fontFamily: serifFont, fontStyle: 'italic',
            fontSize: 14, lineHeight: 1.45, color: T.ink2,
            margin: '10px 0 0', textWrap: 'pretty',
          }}>
            Your feeds, quietly waiting.
          </p>
        </div>

        {/* form */}
        <form onSubmit={submit} style={{
          padding: '20px 22px 0',
          display: 'flex', flexDirection: 'column', gap: 20,
        }}>
          <Field T={T} label="Username" type="text" enterKeyHint="next"
            placeholder="admin" value="admin" />
          <Field T={T} label="Password" type="password" enterKeyHint="go"
            placeholder="••••••••••" value={authError ? '' : 'hunter2hunter2'}
            autoFocus={authError}
            trailing={
              <button type="button" style={{
                background: 'transparent', border: 'none', padding: 0,
                color: T.ink3, fontFamily: uiFont, fontSize: 12,
                letterSpacing: '.06em', cursor: 'pointer',
              }}>Show</button>
            }
          />
          <AuthError T={T} visible={authError} compact />

          <div style={{
            display: 'flex', alignItems: 'center', justifyContent: 'space-between',
            fontSize: 12, marginTop: 2,
          }}>
            <label style={{ display: 'flex', alignItems: 'center', gap: 8, color: T.ink2 }}>
              <span style={{
                width: 14, height: 14, border: `1px solid ${T.borderStrong}`,
                background: T.bg, display: 'inline-block',
              }} />
              Keep me signed in
            </label>
            <a href="#" style={{
              color: T.ink2, textDecoration: 'none',
              borderBottom: `1px solid ${T.border}`, paddingBottom: 1,
            }}>Forgot?</a>
          </div>

          <PrimaryButton T={T} full>Sign in</PrimaryButton>

          <Divider T={T} />

          <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
            <GhostButton T={T} full icon={<GIcon T={T} />}>Continue with Google</GhostButton>
            <GhostButton T={T} full icon={<AtIcon T={T} />}>Email me a magic link</GhostButton>
          </div>
        </form>
      </div>
    );
  }

  return { LoginDesktop, LoginMobile };
})();

const LoginDesktop = LoginUi.LoginDesktop;
const LoginMobile  = LoginUi.LoginMobile;

Object.assign(window, { LoginDesktop, LoginMobile });

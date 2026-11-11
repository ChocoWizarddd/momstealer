import { Hono } from 'hono'

const VALID_CODE = /^[A-Z2-9]{4,8}$/i

/**
 * Bridge page served at /l/:code.  When a user taps a shared Listen Together
 * link (https://nightlight.app/l/ABC123) from a browser or messaging app,
 * this page:
 *
 *  1. Immediately attempts to open the native NightLight scheme.
 *  2. Provides a visible fallback button if that didn't work.
 *  3. Shows the session code so the user can still join manually.
 *  4. Fails gracefully if NightLight is not installed.
 */
const ListenBridge = new Hono()

ListenBridge.get('/l/:code', (ctx) => {
  const raw = ctx.req.param('code') ?? ''
  const code = raw.trim().toUpperCase().slice(0, 8)
  if (!VALID_CODE.test(code)) {
    return ctx.html(invalidCodePage(), 404)
  }
  return ctx.html(bridgePage(code), 200, {
    'Cache-Control': 'no-cache, no-store, must-revalidate'
  })
})

function bridgePage(code: string): string {
  return `<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>NightLight – Listen Together</title>
<style>
  *{margin:0;padding:0;box-sizing:border-box}
  body{min-height:100vh;display:flex;align-items:center;justify-content:center;
    background:#0a0c1a;color:#e8e4df;font-family:system-ui,-apple-system,sans-serif;
    text-align:center;padding:1.5rem}
  .card{max-width:360px;width:100%}
  .logo{font-size:2.5rem;margin-bottom:.5rem}
  h1{font-size:1.3rem;font-weight:600;margin-bottom:.35rem;color:#d4af37}
  .code{display:inline-block;background:#14162a;border:1px solid #2a2d4a;border-radius:8px;
    padding:.5rem 1.1rem;font-size:1.1rem;letter-spacing:.12em;margin:1rem 0;font-weight:600;color:#e8e4df}
  p{font-size:.9rem;color:#a0a0b0;margin-bottom:1.5rem;line-height:1.5}
  .btn{display:block;width:100%;padding:.85rem;border:none;border-radius:12px;font-size:1rem;
    font-weight:600;cursor:pointer;text-decoration:none;text-align:center;margin-bottom:.65rem}
  .primary{background:linear-gradient(135deg,#d4af37,#b8962e);color:#0a0c1a}
  .primary:hover{filter:brightness(1.1)}
  .secondary{background:#14162a;border:1px solid #2a2d4a;color:#e8e4df}
  .secondary:hover{background:#1c1e3a}
  .hint{font-size:.75rem;color:#606070;margin-top:1rem}
</style>
</head>
<body>
<div class="card">
  <div class="logo">🌙</div>
  <h1>Listen Together</h1>
  <p>Join this NightLight session to listen in sync with friends.</p>
  <div class="code">${code}</div>
  <p class="code">Session code</p>
  <a class="btn primary" href="nightlight://listen/${code}">Open NightLight</a>
  <p style="font-size:.8rem;color:#808090;margin-bottom:.5rem">Don't have NightLight yet?</p>
  <a class="btn secondary" href="https://nightlight.app" target="_blank" rel="noopener">Download NightLight</a>
  <p class="hint">If the app doesn't open, try copying the code <strong>${code}</strong> and opening it in NightLight.</p>
</div>
<script>
  // Attempt to open the native app immediately.
  window.location.href = 'nightlight://listen/${code}';
</script>
</body>
</html>`
}

function invalidCodePage(): string {
  return `<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>NightLight – Invalid Link</title>
<style>
  *{margin:0;padding:0;box-sizing:border-box}
  body{min-height:100vh;display:flex;align-items:center;justify-content:center;
    background:#0a0c1a;color:#e8e4df;font-family:system-ui,-apple-system,sans-serif;
    text-align:center;padding:1.5rem}
  .card{max-width:360px;width:100%}
  h1{font-size:1.3rem;color:#d4af37;margin-bottom:.5rem}
  p{font-size:.9rem;color:#a0a0b0;margin-bottom:1.2rem;line-height:1.5}
  .btn{display:inline-block;padding:.75rem 1.5rem;border-radius:10px;text-decoration:none;
    background:#14162a;border:1px solid #2a2d4a;color:#e8e4df;font-weight:600}
</style>
</head>
<body>
<div class="card">
  <h1>🌙 NightLight</h1>
  <p>This link doesn't seem right. Ask your friend to send a fresh NightLight link.</p>
  <a class="btn" href="https://nightlight.app">Visit NightLight</a>
</div>
</body>
</html>`
}

export default ListenBridge

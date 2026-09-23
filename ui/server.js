// Standalone Unity Catalog UI server.
//
// Serves the CRA production build (`yarn build` -> ./build) and proxies the
// Unity Catalog REST API. It adds no credentials of its own: every visitor
// signs in and carries their own UC_TOKEN cookie, so who you are in the UI is
// who the server says you are.
//
// It used to inject the admin bearer token into every proxied call, which made
// this address an unauthenticated way into an authorized server -- opening it
// was enough to be admin. The token now has an entry point of its own,
// /login/token, which the server verifies before opening a session.
//
// Used two ways:
//   - local "binary package" mode:  `yarn build` then `node server.js`
//   - allinone one-container mode:   started as the 2nd process next to the
//     UC server by bin/start-uc-with-ui.sh (UC_ENABLE_UI=true)
//
// Env:
//   PORT           listen port                          (default 3000)
//   HOST           bind address                         (default 0.0.0.0)
//   UC_TARGET      Unity Catalog server base URL        (default http://localhost:8089)
//                  This is the server's own port, which is the port it is started with plus
//                  one: the started port runs a URL transcoder that the UI does not need and
//                  that drops bodyless answers such as the sign-in redirect.
const path = require('path');
const fs = require('fs');
const express = require('express');
const { createProxyMiddleware } = require('http-proxy-middleware');

const PORT = process.env.PORT || 3000;
const HOST = process.env.HOST || '0.0.0.0';
const UC_TARGET = process.env.UC_TARGET || 'http://localhost:8089';
const BUILD_DIR = path.join(__dirname, 'build');

function die(msg) {
  console.error(`[uc-ui] ${msg}`);
  process.exit(1);
}

if (!fs.existsSync(path.join(BUILD_DIR, 'index.html'))) {
  die(`UI build not found at ${BUILD_DIR}. Run "yarn build" first.`);
}
const app = express();

// Proxy the UC REST API first (before static/fallback). Requests go through as
// the browser sent them, cookie included; no body parser runs ahead of this, so
// POST/PATCH bodies stream through untouched.
app.use(
  ['/api/1.0', '/api/2.1'],
  createProxyMiddleware({
    target: UC_TARGET,
    changeOrigin: true,
    // X-Forwarded-Proto/Host let the server build the OAuth callback URL the
    // browser actually uses (this origin), not the proxied target's.
    xfwd: true,
  }),
);

// Static assets, then SPA fallback to index.html for client-side routes.
app.use(express.static(BUILD_DIR));
app.get('*', (_req, res) => {
  res.sendFile(path.join(BUILD_DIR, 'index.html'));
});

app.listen(PORT, HOST, () => {
  console.log(
    `[uc-ui] serving ${BUILD_DIR} on http://${HOST}:${PORT} -> API ${UC_TARGET}`,
  );
});
